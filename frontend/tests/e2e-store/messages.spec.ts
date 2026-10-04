import { expect, test } from "@playwright/test";
import { said, tripThread } from "../thread-mocks.ts";
import { mockStore } from "./mocks.ts";

// Issue #136: the store's side of a trip's thread. A message notification
// opens the thread; the store sees what is written to it or to everyone, and
// its replies and reports go to the dispatcher alone.

const thread = () =>
  tripThread("trip-1", "VEH043", ["OUT085"], "store_manager", [
    said("m1", new Date(Date.now() - 10 * 60_000).toISOString(), { body: "Running 20 min late, now expected 06:05", audience: "outlet", audienceOutlet: "OUT085" }),
  ]);

test("a message notification opens the trip's thread, and the store replies to the dispatcher", async ({ page }) => {
  const { sent } = await mockStore(page, { threads: [thread()] });
  const n = {
    notificationId: "n-msg", eventType: "message.posted", title: "Dinusha Bawantha · VEH043", body: "Running 20 min late, now expected 06:05",
    subjectType: "thread", subjectId: "thread-trip-1", createdAt: new Date().toISOString(), readAt: null,
  };
  await page.route("**/api/notifications**", (route) => {
    const { pathname } = new URL(route.request().url());
    const json = (body: unknown) => ({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
    if (pathname.endsWith("/stream")) return route.fulfill({ status: 200, contentType: "text/event-stream", body: 'event: unread\ndata: {"count":1}\n\n' });
    if (pathname.endsWith("/unread-count")) return route.fulfill(json({ count: 1 }));
    return route.fulfill(json({ items: [n], nextCursor: null }));
  });
  await page.goto("/");
  await page.getByRole("region", { name: "Notifications" }).getByRole("button", { name: /Running 20 min late/ }).click();

  const sheet = page.getByRole("dialog", { name: "Trip messages" });
  await expect(sheet.getByText("Running 20 min late, now expected 06:05")).toBeVisible();
  await expect(sheet.getByText("To OUT085")).toBeVisible();
  // A store writes to the dispatcher alone (R-MSG-02): no one else to choose.
  await expect(sheet.getByText("To Dispatcher")).toBeVisible();
  await sheet.getByRole("textbox", { name: "Message" }).fill("Noted, the dock is free until 07:00");
  await sheet.getByRole("button", { name: "Send", exact: true }).click();
  await expect(sheet.getByText("Noted, the dock is free until 07:00")).toBeVisible();
  expect(sent.find((c) => c.kind === "message:Post")?.payload).toMatchObject({ threadId: "thread-trip-1", to: "dispatch" });
});

test("a delivery's row opens its trip's thread, where the store reports a problem", async ({ page }) => {
  const { sent } = await mockStore(page, { threads: [thread()] });
  await page.goto("/");
  await page.getByRole("button", { name: /^Deliveries/ }).click();
  await page.getByRole("region", { name: "Today" }).getByRole("article", { name: "VEH043" }).getByRole("button", { name: "Message" }).click();

  const sheet = page.getByRole("dialog", { name: "Messages · VEH043" });
  await sheet.getByRole("checkbox", { name: /Report a problem to the dispatcher/ }).check();
  await sheet.getByRole("combobox", { name: "Problem" }).selectOption("stock_discrepancy");
  await sheet.getByRole("textbox", { name: "Message" }).fill("Two yoghurt trays missing from the crate");
  await sheet.getByRole("button", { name: "Report", exact: true }).click();
  await expect(sheet.getByText("Report · Stock problem")).toBeVisible();
  expect(sent.find((c) => c.kind === "message:Post")?.payload).toMatchObject({ to: "dispatch", report: "stock_discrepancy" });
});
