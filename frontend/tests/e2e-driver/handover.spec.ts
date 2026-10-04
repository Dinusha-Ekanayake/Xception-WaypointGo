import { expect, test, type Page } from "@playwright/test";
import { arrive, handOver, serve, startTrip, stop, storeAnswer } from "./mocks.ts";

// Issue #117 and #21: the store answers a handed-over delivery, the driver sees
// the store's report and accepts it with the store manager's PIN. The PIN is
// checked by the server, and is evidence, never a gate. The inbox is the
// driver's real notifications.

async function deliver(page: Page): Promise<void> {
  await startTrip(page);
  await arrive(page);
  await handOver(page);
}

test("the driver accepts the store's report with the store manager's PIN, and a wrong one says how many tries are left", async ({ page }) => {
  const server = await serve(page, [stop(1, "OUT0101")]);
  server.pins[server.stops[0]!.orderId] = "4821";
  server.answers[server.stops[0]!.orderId] = storeAnswer(server.stops[0]!);
  await page.goto("/");
  await deliver(page);

  await page.getByRole("button", { name: "Enter PIN to accept" }).click();
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

  await expect(page.getByText("Accepted with the store's PIN")).toBeVisible();
  await page.getByRole("button", { name: "Next stop" }).click();
  await expect(page.getByRole("heading", { name: "Run complete" })).toBeVisible();
});

test("before the store answers there is no PIN to enter, and the driver moves on saying why", async ({ page }) => {
  const server = await serve(page, [stop(1, "OUT0101")]);
  await page.goto("/");
  await deliver(page);
  await expect(page.getByText("Waiting for store confirmation")).toBeVisible();
  await expect(page.getByRole("button", { name: "Enter PIN to accept" })).toHaveCount(0);

  await page.getByRole("button", { name: "Continue to next stop" }).click();
  await page.getByRole("button", { name: "Store manager not available" }).click();
  await expect(page.getByRole("heading", { name: "Run complete" })).toBeVisible();
  expect(server.commands.filter((c) => c.kind === "delivery:Record")).toHaveLength(1);
  expect(server.commands.find((c) => c.kind === "delivery:LeaveWithoutStoreAnswer")!.payload).toMatchObject({ reason: "store_absent" });
  expect(server.stops[0]!.storeAnswerWaived).toBe("store_absent");
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
