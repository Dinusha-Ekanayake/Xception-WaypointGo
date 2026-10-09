import { expect, test } from "@playwright/test";
import { serve, startTrip } from "./mocks.ts";

// Roadmap F-06: a stop write refused because the stop moved on while the phone
// was offline can be redone on the stop's current version, not only discarded.

test("a stop write refused because the stop moved on is redone on its current version", async ({ page, context }) => {
  const server = await serve(page);
  await page.goto("/");
  await expect(page.getByRole("button", { name: "Start trip" })).toBeVisible();
  await page.evaluate(() => navigator.serviceWorker.ready.then(() => undefined));

  await server.goOffline(context);
  await startTrip(page);
  // Meanwhile the stop moves on elsewhere, so the start recorded on version 1 is refused.
  server.stops[0]!.rowVersion = 2;
  await server.goOnline(context);

  const review = page.getByRole("button", { name: "1 to review" });
  await expect(review).toBeVisible();
  await review.click();
  const dialog = page.getByRole("dialog", { name: "Changes to review" });
  await dialog.getByRole("button", { name: "Redo on the current version" }).click();

  await expect.poll(() => server.commands.map((c) => [c.kind, c.expectedVersion])).toContainEqual(["delivery:Start", 2]);
  await expect(dialog).toContainText("Nothing left to review.");
});
