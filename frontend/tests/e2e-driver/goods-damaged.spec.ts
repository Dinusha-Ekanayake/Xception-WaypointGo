import { expect, test } from "@playwright/test";
import { arrive, serve, startTrip } from "./mocks.ts";

// Owner's decision of 2026-10-21: goods damaged with some usable is a partial
// delivery of what is usable, plus a quiet goods problem for the end of the day.

test("goods damaged becomes a partial delivery of the usable units and a quiet goods problem", async ({ page }) => {
  const server = await serve(page);
  await page.goto("/");
  await startTrip(page);
  await arrive(page);

  await page.getByRole("button", { name: "Report problem" }).first().click();
  const sheet = page.getByRole("dialog", { name: /problem/i });
  const row = sheet.getByRole("slider", { name: "Goods damaged" });
  await row.click({ trial: true });
  const box = (await row.boundingBox())!;
  await page.mouse.move(box.x + 32, box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width - 16, box.y + box.height / 2, { steps: 18 });
  await page.mouse.up();

  await expect(page.getByText("Set the units handed over in good condition.", { exact: false })).toBeVisible();
  await page.getByRole("checkbox", { name: "I can't capture a signature or a photo" }).check();
  await page.getByLabel(/Why not/i).fill("Store manager not at the counter");
  await page.getByRole("button", { name: "Confirm" }).click();

  await expect.poll(() => server.commands.map((c) => c.kind)).toContain("issue:Raise");
  const record = server.commands.find((c) => c.kind === "delivery:Record")!;
  expect(record.payload).toMatchObject({ outcome: "PARTIAL", deliveredUnits: server.stops[0]!.itemCount - 1, reason: "Goods damaged" });
  const raised = server.commands.find((c) => c.kind === "issue:Raise")!;
  expect(raised.payload).toMatchObject({
    type: "DAMAGED_GOODS",
    outletId: "OUT0101",
    subjects: [{ type: "delivery", id: server.stops[0]!.deliveryId }, { type: "order", id: server.stops[0]!.orderId }],
  });
});
