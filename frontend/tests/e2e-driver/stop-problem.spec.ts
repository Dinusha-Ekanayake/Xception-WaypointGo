import { expect, test } from "@playwright/test";
import { arrive, serve, startTrip } from "./mocks.ts";

// R-EXE-32, owner's decisions of 2026-10-21: a closed store goes to the dispatcher,
// who decides; the driver cannot mark it not delivered and goes on to the next stop.

test("a closed store is reported for the dispatcher, and the driver goes on to the next stop", async ({ page }) => {
  const server = await serve(page);
  await page.goto("/");
  await startTrip(page);
  await arrive(page);

  await page.getByRole("button", { name: "Report problem" }).first().click();
  const sheet = page.getByRole("dialog", { name: /problem/i });
  await expect(sheet.getByRole("slider", { name: "Running late" })).toHaveCount(0);
  const row = sheet.getByRole("slider", { name: "Store closed" });
  await row.click({ trial: true });
  const box = (await row.boundingBox())!;
  await page.mouse.move(box.x + 32, box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width - 16, box.y + box.height / 2, { steps: 18 });
  await page.mouse.up();

  await expect.poll(() => server.commands.map((c) => c.kind)).toContain("delivery:ReportStopProblem");
  const report = server.commands.find((c) => c.kind === "delivery:ReportStopProblem")!;
  expect(report).toMatchObject({ expectedVersion: null, payload: { deliveryId: server.stops[0]!.deliveryId, problem: "STORE_CLOSED" } });
  expect(server.commands.some((c) => c.kind === "delivery:Record")).toBe(false);
  const sent = page.getByRole("dialog", { name: "Report sent" });
  await expect(sent).toContainText("Store closed");
  await sent.getByRole("button", { name: "Okay" }).click();
  // On to the next stop: the reported one waits for the dispatcher, and says so.
  await expect(page.getByText("The dispatcher decides about this stop. Go on to your next stop.")).toBeVisible();
  await expect(page.getByRole("heading", { name: "OUT0202" })).toBeVisible();
  await expect(page.getByRole("button", { name: /OUT0101 · With the dispatcher/ })).toBeVisible();
});
