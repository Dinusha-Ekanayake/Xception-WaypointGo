import { expect, test } from "@playwright/test";
import { tripThread } from "../thread-mocks.ts";
import { LIVE_NOW, liveDay, serve } from "./mocks.ts";

// FF-09, R-ISS-12 to R-ISS-14: from a "Needs you" card the dispatcher asks the
// store in one tap instead of phoning. Every name and time is the system's.

test.beforeEach(async ({ page }) => {
  await page.clock.install({ time: new Date(LIVE_NOW) });
});

test("a closing window asks the store whether someone can receive later, and records what it said by phone", async ({ page }) => {
  const desk = await serve(page, liveDay());
  await page.goto("/#/live");
  const card = page.getByRole("region", { name: "Needs you" }).getByRole("listitem").filter({ hasText: "may miss its window" }).first();
  const ask = card.getByTestId("decision-ask");
  const times = ask.getByRole("group", { name: /^Times to ask OUT\d+ about$/ });
  await expect(times.getByRole("button")).toHaveCount(3);
  await times.getByRole("button").first().click();

  await expect.poll(() => desk.commands.find((c) => c.kind === "issue:AskDecision")?.payload).toMatchObject({ kind: "receive_late" });
  await expect(ask.getByTestId("decision-standing")).toContainText(/^Asked OUT\d+ · answer by \d\d:\d\d$/);
  await expect(ask).toContainText("Can someone receive the delivery at about");

  // No answer in the app yet: the dispatcher phones and records it.
  await ask.getByRole("button", { name: "Record what the store said" }).click();
  await ask.getByRole("button", { name: "Yes, by phone" }).click();
  await expect.poll(() => desk.commands.find((c) => c.kind === "issue:AnswerDecision")?.payload).toMatchObject({ answer: "yes", byPhone: true });
  await expect(ask.getByTestId("decision-standing")).toContainText(/said Yes by phone/);
});

test("goods that stayed on the vehicle ask the next store on the trip to help at its dock", async ({ page }) => {
  const desk = await serve(page, liveDay());
  await page.goto("/#/live");
  const card = page.getByRole("region", { name: "Needs you" }).getByRole("listitem").filter({ hasText: "OUT010 · not delivered" });
  const ask = card.getByTestId("decision-ask");
  await expect(ask).toContainText("stay on the vehicle, in front of OUT012's goods");
  await ask.getByRole("button", { name: "Ask OUT012 to help at the dock" }).click();
  await expect.poll(() => desk.commands.find((c) => c.kind === "issue:AskDecision")?.payload).toEqual({
    kind: "dock_help", deliveryId: "d-VEH030-2", aboutDeliveryId: "d-VEH030-1",
  });
  await expect(ask).toContainText("Goods for OUT010");
  await expect(ask.getByTestId("decision-standing")).toContainText("Asked OUT012");
});

test("the trip's thread offers ready replies filled from the run sheet, and a tap fills the message", async ({ page }) => {
  const desk = await serve(page, { ...liveDay(), threads: [tripThread("t-VEH020", "VEH020", ["OUT061", "OUT063"], "dispatcher")] });
  await page.goto("/#/live");
  await page.getByRole("button", { name: "Timeline", exact: true }).click();
  await page.getByRole("button", { name: "VEH020 · Style · Matara, At risk" }).click();
  await page.getByRole("button", { name: "All messages ›" }).click();
  const sheet = page.getByRole("dialog", { name: "Trip messages" });
  const ready = sheet.getByRole("group", { name: "Suggested replies" });
  await ready.getByRole("button", { name: "Tell OUT063 the new time" }).click();
  await expect(sheet.getByRole("textbox", { name: "Message" })).toHaveValue("Running 36 min late. Now expected at 17:06.");
  await expect(sheet.getByRole("radio", { name: "OUT063" })).toHaveAttribute("aria-checked", "true");
  await sheet.getByRole("button", { name: "Send", exact: true }).click();
  await expect.poll(() => desk.commands.find((c) => c.kind === "message:Post")?.payload).toMatchObject({ to: "outlet", outletId: "OUT063", body: "Running 36 min late. Now expected at 17:06." });
});

test("what happened on a stop is listed from the record", async ({ page }) => {
  await serve(page, liveDay());
  await page.goto("/#/live");
  const card = page.getByRole("region", { name: "Needs you" }).getByRole("listitem").filter({ hasText: "may miss its window" }).first();
  const ask = card.getByTestId("decision-ask");
  await ask.getByRole("group", { name: /^Times to ask/ }).getByRole("button").first().click();
  await ask.getByRole("button", { name: "Record what the store said" }).click();
  await ask.getByRole("button", { name: "No, by phone" }).click();
  const summary = ask.getByTestId("case-summary");
  await summary.locator("summary").click();
  await expect(summary).toContainText(/Dispatcher asked OUT\d+: receive at about \d\d:\d\d\?/);
  await expect(summary).toContainText(/OUT\d+: No · by phone/);
});
