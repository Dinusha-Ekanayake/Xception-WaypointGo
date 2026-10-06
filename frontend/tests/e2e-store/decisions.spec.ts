import { expect, test } from "@playwright/test";
import { decisionOf } from "../decision-mocks.ts";
import { mockStore } from "./mocks.ts";

// FF-09, R-ISS-14: the dispatcher's question waits at the top of the store's
// home; the store taps Yes or No, with a reason if it wants, and nobody phones.

test("the store answers the dispatcher's question with a tap and an optional reason", async ({ page }) => {
  const { sent, decisions } = await mockStore(page, {
    decisions: [decisionOf({ requestId: "q1", deliveryId: "d1", outletId: "OUT085", receiveAt: "2027-03-01T02:50:00Z" })],
  });
  await page.goto("/");
  const card = page.getByRole("region", { name: "The dispatcher needs your answer" });
  await expect(card.getByTestId("decision-question")).toHaveText("Can someone receive the delivery at about 08:20?");
  await expect(card).toContainText(/Answer by \d\d:\d\d/);
  await card.getByRole("textbox", { name: "Reason (optional)" }).fill("Night staff stay until 09:00");
  await card.getByRole("button", { name: "Yes", exact: true }).click();

  expect(sent.find((c) => c.kind === "issue:AnswerDecision")).toMatchObject({
    payload: { requestId: "q1", answer: "yes", reason: "Night staff stay until 09:00" },
    expectedVersion: 1,
  });
  expect(decisions[0]!.answer).toBe("yes");
  await expect(card).toHaveCount(0);
});

test("the next store is asked to let the crew reach its goods, every name and number filled in", async ({ page }) => {
  const { sent } = await mockStore(page, {
    decisions: [decisionOf({ requestId: "q2", kind: "dock_help", deliveryId: "d2", outletId: "OUT085", aboutDeliveryId: "d1", aboutOutletId: "OUT084", aboutUnits: 24, receiveAt: null })],
  });
  await page.goto("/");
  const card = page.getByRole("region", { name: "The dispatcher needs your answer" });
  await expect(card.getByTestId("decision-question")).toHaveText(
    "Goods for OUT084 (24 units) are in front of yours. Can the crew unload them at your dock to reach your goods, and reload them?",
  );
  await card.getByRole("button", { name: "No", exact: true }).click();
  expect(sent.find((c) => c.kind === "issue:AnswerDecision")?.payload).toEqual({ requestId: "q2", answer: "no" });
});
