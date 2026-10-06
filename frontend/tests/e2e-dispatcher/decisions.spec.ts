import { expect, test } from "@playwright/test";
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

  const asked = desk.commands.find((c) => c.kind === "issue:AskDecision")!;
  expect(asked.payload).toMatchObject({ kind: "receive_late" });
  await expect(ask.getByTestId("decision-standing")).toContainText(/^Asked OUT\d+ · answer by \d\d:\d\d$/);
  await expect(ask).toContainText("Can someone receive the delivery at about");

  // No answer in the app yet: the dispatcher phones and records it.
  await ask.getByRole("button", { name: "Record what the store said" }).click();
  await ask.getByRole("button", { name: "Yes, by phone" }).click();
  expect(desk.commands.find((c) => c.kind === "issue:AnswerDecision")?.payload).toMatchObject({ answer: "yes", byPhone: true });
  await expect(ask.getByTestId("decision-standing")).toContainText(/said Yes by phone/);
});

test("goods that stayed on the vehicle ask the next store on the trip to help at its dock", async ({ page }) => {
  const desk = await serve(page, liveDay());
  await page.goto("/#/live");
  const card = page.getByRole("region", { name: "Needs you" }).getByRole("listitem").filter({ hasText: "OUT010 · not delivered" });
  const ask = card.getByTestId("decision-ask");
  await expect(ask).toContainText("stay on the vehicle, in front of OUT012's goods");
  await ask.getByRole("button", { name: "Ask OUT012 to help at the dock" }).click();
  expect(desk.commands.find((c) => c.kind === "issue:AskDecision")?.payload).toEqual({
    kind: "dock_help", deliveryId: "d-VEH030-2", aboutDeliveryId: "d-VEH030-1",
  });
  await expect(ask).toContainText("Goods for OUT010");
  await expect(ask.getByTestId("decision-standing")).toContainText("Asked OUT012");
});
