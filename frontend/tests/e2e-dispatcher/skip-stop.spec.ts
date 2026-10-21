import { expect, test, type Page } from "@playwright/test";
import { said, tripThread } from "../thread-mocks.ts";
import { LIVE_NOW, liveDay, serve, type Desk } from "./mocks.ts";
import { storeClosed } from "../playbook-fixture.ts";

// R-EXE-31: on a trip on the road the dispatcher skips a store (next day or
// cancel, with a reason) or ends the trip. Each sends the stop's version, says
// who is told, and the stop reads "Off the trip" once Execution took it off.

async function openTrip(page: Page): Promise<Desk> {
  await page.clock.install({ time: new Date(LIVE_NOW) });
  const desk = await serve(page, liveDay());
  await page.goto("/#/live");
  const needs = page.getByRole("region", { name: "Needs you" });
  await needs.getByRole("listitem").filter({ hasText: "OUT010" }).getByRole("button", { name: "Open trip" }).click();
  await expect(page.getByRole("heading", { name: /VEH030/ })).toBeVisible();
  return desk;
}

test("the dispatcher skips a store and cancels its order, with a reason", async ({ page }) => {
  const desk = await openTrip(page);
  const row = page.getByRole("row", { name: /OUT012/ });
  // A failed stop is done: nothing to skip there.
  await expect(page.getByRole("row", { name: /OUT010/ }).getByRole("button", { name: "Skip store" })).toHaveCount(0);

  await row.getByRole("button", { name: "Skip store" }).click();
  const sheet = page.getByRole("dialog", { name: "Skip OUT012" });
  await expect(sheet.getByRole("button", { name: "Skip to next day" })).toBeDisabled();
  await sheet.getByRole("radio", { name: "Cancel" }).click();
  await expect(sheet).toContainText("the warehouse is not called");
  await sheet.getByRole("textbox", { name: "Why" }).fill("Store refused the delivery");
  await sheet.getByRole("button", { name: "Skip and cancel" }).click();

  await expect(sheet).toBeHidden();
  const sent = desk.commands.find((c) => c.kind === "delivery:SkipStop");
  expect(sent?.payload).toEqual({ deliveryId: "d-VEH030-2", then: "CANCEL", reason: "Store refused the delivery" });
  expect(sent?.expectedVersion).toBe(1);
  // A driver with no signal may still drive there: the dispatcher sees it is not seen yet, and phones.
  await expect(row).toContainText("Off the trip · cancelled · not seen by the driver yet");
  await expect(row.getByRole("button", { name: "Skip store" })).toHaveCount(0);
});

test("ending the trip says how many stops go to the next day and sends one command", async ({ page }) => {
  const desk = await openTrip(page);
  await page.getByRole("button", { name: "End trip" }).click();
  const sheet = page.getByRole("dialog", { name: "End trip" });
  await expect(sheet).toContainText("1 stop not yet done goes to the next delivery day");
  await expect(sheet).toContainText("OUT012");
  await sheet.getByRole("textbox", { name: "Why" }).fill("Vehicle broke down");
  await sheet.getByRole("button", { name: "End trip · 1 stop" }).click();

  await expect(sheet).toBeHidden();
  expect(desk.commands.filter((c) => c.kind === "delivery:EndTrip").map((c) => c.payload)).toEqual([{ tripId: "t-VEH030", reason: "Vehicle broke down" }]);
  await expect(page.getByRole("row", { name: /OUT012/ })).toContainText("Off the trip · next day · not seen by the driver yet");
  await expect(page.getByRole("button", { name: "End trip" })).toHaveCount(0);
});

test("the dispatcher decides from the trip's chat: Skip beside the report, End trip in the header", async ({ page }) => {
  await page.clock.install({ time: new Date(LIVE_NOW) });
  const thread = tripThread("t-VEH020", "VEH020", ["OUT061", "OUT063"], "dispatcher", [
    said("r1", "2027-03-01T10:05:00Z", {
      kind: "report", reportType: "failed_delivery", authorName: "Dilan R.", authorRole: "driver", audience: "dispatch",
      body: "OUT063 shutter is down, nobody answers", aboutOutletId: "OUT063",
    }),
  ]);
  const desk = await serve(page, { ...liveDay(), threads: [thread] });
  await page.goto("/#/live");
  await page.getByRole("button", { name: "Timeline", exact: true }).click();
  await page.getByRole("region", { name: "Timeline" }).getByTestId("report-sign").first().click();

  const chat = page.getByRole("dialog", { name: "Trip messages" });
  await expect(chat.getByRole("button", { name: "End trip" })).toBeVisible();
  const report = chat.locator("#message-r1");
  await report.getByRole("button", { name: "Skip OUT063" }).click();
  const form = report.getByRole("group", { name: "Skip OUT063" });
  await form.getByRole("textbox", { name: "Why" }).fill("Store closed, nobody answers");
  await form.getByRole("button", { name: "Skip to next day" }).click();

  await expect.poll(() => desk.commands.find((c) => c.kind === "delivery:SkipStop")?.payload).toEqual({
    deliveryId: "d-VEH020-2", then: "NEXT_DAY", reason: "Store closed, nobody answers",
  });
  // The stop is off the trip: no skip left beside the report, and nothing left for End trip.
  await expect(report.getByRole("button", { name: "Skip OUT063" })).toHaveCount(0);
  await expect(chat.getByRole("button", { name: "End trip" })).toHaveCount(0);
});

test("the chat suggests the likely decisions beside a report, and keeps which one was taken (#269)", async ({ page }) => {
  await page.clock.install({ time: new Date(LIVE_NOW) });
  const thread = tripThread("t-VEH020", "VEH020", ["OUT061", "OUT063"], "dispatcher", [
    said("r1", "2027-03-01T10:05:00Z", {
      kind: "report", reportType: "failed_delivery", authorName: "Dilan R.", authorRole: "driver", audience: "dispatch",
      body: "OUT063 shutter is down, nobody answers", aboutOutletId: "OUT063",
    }),
  ]);
  const desk = await serve(page, { ...liveDay(), threads: [thread] });
  await page.goto("/#/live");
  await page.getByRole("button", { name: "Timeline", exact: true }).click();
  await page.getByRole("region", { name: "Timeline" }).getByTestId("report-sign").first().click();

  const report = page.getByRole("dialog", { name: "Trip messages" }).locator("#message-r1");
  const chips = report.getByRole("group", { name: "Suggested decisions" });
  await expect(chips).toContainText("Suggested by the rules");
  await expect(chips.getByRole("button")).toHaveText(["Call the store", "Skip to next day", "Wait a little"]);
  await expect(chips).toContainText("skipped twice in a row");

  await chips.getByRole("button", { name: "Skip to next day" }).click();
  const form = report.getByRole("group", { name: "Skip OUT063" });
  await expect(form.getByRole("radio", { name: "Next day" })).toHaveAttribute("aria-checked", "true");
  await form.getByRole("textbox", { name: "Why" }).fill("Nobody came to the door");
  await form.getByRole("button", { name: "Skip to next day" }).click();

  await expect.poll(() => desk.commands.find((c) => c.kind === "ml:RecordSuggestion")?.payload).toEqual({
    deliveryId: "d-VEH020-2", situation: "STORE_CLOSED", shown: ["CALL_STORE", "SKIP_NEXT_DAY", "WAIT"], chosen: "SKIP_NEXT_DAY",
  });
  expect(desk.commands.find((c) => c.kind === "delivery:SkipStop")?.payload).toMatchObject({ then: "NEXT_DAY" });
});

test("once learned, a chip says how often dispatchers chose it here", async ({ page }) => {
  await page.clock.install({ time: new Date(LIVE_NOW) });
  const thread = tripThread("t-VEH020", "VEH020", ["OUT061", "OUT063"], "dispatcher", [
    said("r1", "2027-03-01T10:05:00Z", { kind: "report", reportType: "failed_delivery", authorName: "Dilan R.", authorRole: "driver", audience: "dispatch", body: "Shutter down", aboutOutletId: "OUT063" }),
  ]);
  await serve(page, { ...liveDay(), threads: [thread] });
  await page.route("**/api/ml/decision-suggestions**", (route) => {
    const learned = { ...storeClosed("d-VEH020-2"), basis: "learned" };
    learned.suggestions = learned.suggestions.map((x, i) => ({ ...x, chosenTimes: [21, 3, 1][i]!, shownTimes: 30 }));
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(learned) });
  });
  await page.goto("/#/live");
  await page.getByRole("button", { name: "Timeline", exact: true }).click();
  await page.getByRole("region", { name: "Timeline" }).getByTestId("report-sign").first().click();
  const chips = page.getByRole("dialog", { name: "Trip messages" }).locator("#message-r1").getByRole("group", { name: "Suggested decisions" });
  await expect(chips).toContainText("Suggested from what dispatchers chose here");
  await expect(chips.getByRole("button").first()).toHaveText("Call the store · chosen 21 of 30 times");
});
