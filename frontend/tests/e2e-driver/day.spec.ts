import { expect, test } from "@playwright/test";
import { serve, sign, stop } from "./mocks.ts";

test("a stop worked with no signal survives a reload and is sent once, in order, when the signal returns", async ({ page, context }) => {
  const server = await serve(page);
  await page.goto("/");
  await expect(page.getByRole("button", { name: "Start run" })).toBeVisible();
  // The shell must be on the phone before the signal goes, or a reload has nothing to show.
  await page.evaluate(() => navigator.serviceWorker.ready.then(() => undefined));

  await server.goOffline(context);
  await page.getByRole("button", { name: "Start run" }).click();
  await expect(page.getByRole("heading", { name: "OUT0101" })).toBeVisible();
  await page.getByRole("button", { name: "I've arrived" }).click();
  await expect(page.getByText("Stop 01 of 02 · Delivery report")).toBeVisible();

  await page.getByLabel("Received by").fill("Kumari Silva");
  await sign(page);
  await expect(page.getByText("Signed", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Confirm" }).click();

  const saved = page.getByRole("dialog", { name: "Delivery confirmed" });
  await expect(saved).toContainText("Saved on this phone");
  expect(server.commands).toHaveLength(0);

  // The phone restarts with still no signal: the day, and the work, are on it.
  await page.reload();
  await expect(page.getByText("Showing the run saved on this phone")).toBeVisible();
  const first = page.getByRole("button", { name: /OUT0101/ });
  await expect(first).toContainText("Delivered · on phone");
  await expect(page.getByRole("status").filter({ hasText: /^Offline$/ })).toBeVisible();
  await expect(page.getByRole("button", { name: "4 saved on this device" })).toBeVisible();

  await server.goOnline(context);
  await expect.poll(() => server.commands.length).toBe(4);
  expect(server.commands.map((c) => [c.kind, c.expectedVersion])).toEqual([
    ["delivery:Start", 1],
    ["delivery:RecordArrival", 2],
    ["delivery:Record", 3],
    ["delivery:CaptureProof", 4],
  ]);
  expect(server.commands[2]!.payload).toMatchObject({ outcome: "DELIVERED", deliveredUnits: 12 });
  const proof = server.commands[3]!.payload;
  expect(proof).toMatchObject({ recipientName: "Kumari Silva", photoAttachmentId: null, fallbackReason: null });

  // The signature travels on its own, under the id the proof named.
  await expect.poll(() => server.uploads.length).toBe(1);
  expect(server.uploads[0]!.path).toContain(`/attachments/${proof.signatureAttachmentId}?kind=signature`);
  expect(server.uploads[0]!.contentType).toBe("image/png");
  expect(server.uploads[0]!.bytes).toBeGreaterThan(100);

  await expect(first).toContainText("Delivered");
  await expect(first).not.toContainText("on phone");
  await expect(page.getByRole("status").filter({ hasText: /^Synced/ })).toBeVisible();
  // Sent once: a second pass finds nothing to send.
  expect(server.commands).toHaveLength(4);
});

test("with a signal, a delivery is sent at once and the run completes", async ({ page }) => {
  const server = await serve(page, [stop(1, "OUT0101")]);
  await page.goto("/");
  await page.getByRole("button", { name: "Start run" }).click();
  await page.getByRole("button", { name: "I've arrived" }).click();
  await page.getByRole("button", { name: "One unit fewer delivered" }).click();
  await page.getByLabel("Why were some units not delivered?").fill("One crate crushed in transit");
  await page.getByLabel("What happened to the goods not delivered?").fill("Kept on the vehicle");
  await page.getByLabel("I can't capture a signature or a photo").check();
  await page.getByLabel("Why not?").fill("Receiver refused to sign");
  await page.getByRole("button", { name: "Confirm" }).click();

  const saved = page.getByRole("dialog", { name: "Partial delivery recorded" });
  await expect(saved).toContainText("Saved and sent to dispatch");
  expect(server.batches).toBe(0);
  expect(server.commands.map((c) => c.kind)).toEqual(["delivery:Start", "delivery:RecordArrival", "delivery:Record", "delivery:CaptureProof"]);
  expect(server.commands[2]!.payload).toMatchObject({ outcome: "PARTIAL", deliveredUnits: 11, reason: "One crate crushed in transit", dispositionNote: "Kept on the vehicle" });
  expect(server.commands[3]!.payload).toMatchObject({ photoAttachmentId: null, signatureAttachmentId: null, fallbackReason: "Receiver refused to sign" });

  await saved.getByRole("button", { name: "Finish run" }).click();
  await expect(page.getByRole("heading", { name: "Run complete" })).toBeVisible();
  await expect(page.getByText("1 of 1")).toBeVisible();
});

test("a delivery cannot be confirmed without proof or a reason for its absence", async ({ page }) => {
  const server = await serve(page, [stop(1, "OUT0101", { outcome: "ARRIVED", arrivedAt: new Date().toISOString(), startedAt: new Date().toISOString(), waitMinutes: 0, lateMinutes: 0, rowVersion: 3 })]);
  await page.goto("/");
  await page.getByRole("button", { name: /OUT0101/ }).click();
  await page.getByRole("button", { name: "Confirm" }).click();
  await expect(page.getByText("Add a signature or a photo, or say why neither could be captured.")).toBeVisible();
  expect(server.commands).toHaveLength(0);
});
