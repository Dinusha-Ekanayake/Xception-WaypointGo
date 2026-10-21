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
  await page.getByRole("button", { name: /^Notifications/ }).first().click();
  await page.getByRole("dialog", { name: "Notifications" }).getByRole("button", { name: /Running 20 min late/ }).click();

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

test("a store still waiting writes to the stores' channel, and is asked before naming another store (R-MSG-08)", async ({ page }) => {
  const t = thread();
  t.members = [
    { to: "dispatch", outletId: null, label: "Dispatcher" },
    { to: "channel", outletId: null, label: "Dispatcher and stores still waiting" },
  ];
  const { sent } = await mockStore(page, { threads: [t] });
  await page.goto("/");
  await page.getByRole("button", { name: /^Deliveries/ }).click();
  await page.getByRole("region", { name: "Today" }).getByRole("article", { name: "VEH043" }).getByRole("button", { name: "Message" }).click();

  const sheet = page.getByRole("dialog", { name: "Messages · VEH043" });
  // The private line to the dispatcher stays the default.
  await expect(sheet.getByRole("radio", { name: "Dispatcher", exact: true })).toHaveAttribute("aria-checked", "true");
  const message = sheet.getByRole("textbox", { name: "Message" });
  await sheet.getByRole("radio", { name: "Dispatcher and stores still waiting" }).click();
  await message.fill("Is OUT063 before us today?");
  await sheet.getByRole("button", { name: "Send", exact: true }).click();
  await expect(sheet.getByTestId("channel-nudge")).toContainText("names OUT063");
  await sheet.getByRole("button", { name: "Send to the dispatcher only" }).click();
  await expect(sheet.getByText("Is OUT063 before us today?")).toBeVisible();
  expect(sent.find((c) => c.kind === "message:Post")?.payload).toMatchObject({ to: "dispatch" });

  await sheet.getByRole("radio", { name: "Dispatcher and stores still waiting" }).click();
  await message.fill("Our gate opens at 05:00");
  await sheet.getByRole("button", { name: "Send", exact: true }).click();
  await expect(sheet.getByText("To the stores still waiting")).toBeVisible();
  expect(sent.filter((c) => c.kind === "message:Post").at(-1)?.payload).toMatchObject({ to: "channel" });
});

test("a store the driver found closed is offered to say it is open now, to the dispatcher (decision suggestions part 4)", async ({ page }) => {
  const { sent } = await mockStore(page, { threads: [thread()] });
  // The driver reported the store closed (R-EXE-32): the store's ready replies follow that.
  const { DELIVERY } = await import("./mocks.ts");
  await page.route("**/api/execution/deliveries**", (route) =>
    route.fulfill({ status: 200, contentType: "application/json",
      body: JSON.stringify([{ ...DELIVERY, outcome: "ARRIVED", arrivedAt: new Date().toISOString(), reportedProblem: "STORE_CLOSED" }]) }));
  await page.goto("/");
  await page.getByRole("button", { name: /^Deliveries/ }).click();
  await page.getByRole("region", { name: "Today" }).getByRole("article", { name: "VEH043" }).getByRole("button", { name: "Message" }).click();

  const sheet = page.getByRole("dialog", { name: "Messages · VEH043" });
  const chips = sheet.getByRole("group", { name: "Suggested replies" });
  await expect(chips.getByRole("button", { name: "We are open now" })).toBeVisible();
  await expect(chips.getByRole("button", { name: "Deliver on the next day" })).toBeVisible();
  await chips.getByRole("button", { name: "We are open now" }).click();
  await expect(sheet.getByRole("textbox", { name: "Message" })).toHaveValue("We are open now and can receive. Please send the driver back.");
  await sheet.getByRole("button", { name: "Send", exact: true }).click();
  await expect.poll(() => sent.find((c) => c.kind === "message:Post")?.payload).toMatchObject({ to: "dispatch", templateId: "storereply.open_now" });
});

test("a delay notice opened from a notification offers the store's answers: later today, or the next day", async ({ page }) => {
  const delayed = tripThread("trip-1", "VEH043", ["OUT085"], "store_manager", [
    said("m1", new Date(Date.now() - 5 * 60_000).toISOString(), { body: "VEH043 is delayed: breakdown", audience: "all" }),
  ]);
  const { sent } = await mockStore(page, { threads: [delayed] });
  const { DELIVERY } = await import("./mocks.ts");
  await page.route("**/api/execution/deliveries**", (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([{ ...DELIVERY, outcome: "PENDING", startedAt: new Date().toISOString() }]) }));
  const n = {
    notificationId: "n-delay", eventType: "message.posted", title: "Dispatcher · VEH043", body: "VEH043 is delayed: breakdown",
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
  await page.getByRole("button", { name: /^Notifications/ }).first().click();
  await page.getByRole("dialog", { name: "Notifications" }).getByRole("button", { name: /is delayed: breakdown/ }).click();

  // Opened by the thread, not the delivery's row: the chips still follow this trip's delivery and the newest word.
  const sheet = page.getByRole("dialog", { name: "Trip messages" });
  const chips = sheet.getByRole("group", { name: "Suggested replies" });
  await expect(chips.getByRole("button")).toHaveText(["We can receive later today", "Deliver on the next day"]);
  await chips.getByRole("button", { name: "We can receive later today" }).click();
  await expect(sheet.getByRole("textbox", { name: "Message" })).toHaveValue("We can still receive later today. Please come when you can.");
  if (process.env.SHOTS_DIR) await page.screenshot({ path: `${process.env.SHOTS_DIR}/16-store-chat-chips.png` });
  await sheet.getByRole("button", { name: "Send", exact: true }).click();
  await expect.poll(() => sent.find((c) => c.kind === "message:Post")?.payload).toMatchObject({ to: "dispatch", templateId: "storereply.later_today" });
});
