import { expect, test, type Page } from "@playwright/test";
import { arrive, openForm, serve, startTrip, stop } from "./mocks.ts";

// Issue #117: the store manager's handover PIN is checked by the server, and is
// evidence, never a gate. The inbox is the driver's real notifications.

async function deliver(page: Page): Promise<void> {
  await startTrip(page);
  await arrive(page);
  await openForm(page);
  await page.getByLabel("I can't capture a signature or a photo").check();
  await page.getByLabel("Why not?").fill("Receiver refused to sign");
  await page.getByRole("button", { name: "Confirm" }).click();
}

test("after a delivery the driver enters the store manager's PIN, and a wrong one says how many tries are left", async ({ page }) => {
  const server = await serve(page, [stop(1, "OUT0101")]);
  server.pins[server.stops[0]!.orderId] = "4821";
  await page.goto("/");
  await deliver(page);

  await page.getByRole("dialog", { name: "Delivery confirmed" }).getByRole("button", { name: "Enter store manager PIN" }).click();
  const pin = page.getByRole("dialog", { name: "Store manager PIN" });
  await expect(pin.getByLabel("Store manager PIN")).toHaveValue("");
  await pin.getByLabel("Store manager PIN").fill("1111");
  await pin.getByRole("button", { name: "Confirm" }).click();
  await expect(pin).toContainText("That PIN is not right. 4 tries left.");
  await expect(pin.getByLabel("Store manager PIN")).toHaveValue("");

  await pin.getByLabel("Store manager PIN").fill("4821");
  await pin.getByRole("button", { name: "Confirm" }).click();
  await expect(pin).toContainText("Handover confirmed");
  const sent = server.commands.filter((c) => c.kind === "receipt:VerifyHandover");
  expect(sent.map((c) => c.payload)).toEqual([
    { orderId: server.stops[0]!.orderId, pin: "1111" },
    { orderId: server.stops[0]!.orderId, pin: "4821" },
  ]);

  // The delivery was never waiting on it: the saved sheet comes back and the run finishes.
  await page.getByRole("dialog", { name: "Delivery confirmed" }).getByRole("button", { name: "Finish run" }).click();
  await expect(page.getByRole("heading", { name: "Run complete" })).toBeVisible();
});

test("before the store answers there is no PIN to check, and the driver can skip", async ({ page }) => {
  const server = await serve(page, [stop(1, "OUT0101")]);
  await page.goto("/");
  await deliver(page);
  await page.getByRole("button", { name: "Enter store manager PIN" }).click();
  const pin = page.getByRole("dialog", { name: "Store manager PIN" });
  await pin.getByLabel("Store manager PIN").fill("1234");
  await pin.getByRole("button", { name: "Confirm" }).click();
  await expect(pin).toContainText("The store manager has not confirmed the receipt yet");
  await pin.getByRole("button", { name: "Skip" }).click();
  await expect(page.getByRole("dialog", { name: "Delivery confirmed" })).toBeVisible();
  expect(server.commands.filter((c) => c.kind === "delivery:Record")).toHaveLength(1);
});

test("Home shows the driver's real notifications and marks them read", async ({ page }) => {
  const server = await serve(page);
  server.notifications = [
    {
      notificationId: "00000000-0000-7000-8000-0000000000n1",
      eventType: "plan.published",
      title: "Your run for today is ready",
      body: "2 stops from KDY.",
      subjectType: "plan",
      subjectId: null,
      createdAt: new Date().toISOString(),
      readAt: null,
    },
  ];
  await page.goto("/");
  const feed = page.getByLabel("Notifications", { exact: true });
  await expect(feed).toContainText("Run published");
  await expect(feed).toContainText("Your run for today is ready");
  await expect(page.getByText("Priya")).toHaveCount(0);

  await page.getByRole("button", { name: "1 new. Mark all read" }).click();
  await expect.poll(() => server.commands.some((c) => c.kind.startsWith("notification:"))).toBe(true);
});
