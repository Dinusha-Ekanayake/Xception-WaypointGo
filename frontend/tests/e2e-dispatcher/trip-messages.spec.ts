import { expect, test } from "@playwright/test";
import { said, tripThread } from "../thread-mocks.ts";
import { LIVE_NOW, liveDay, serve, type Desk } from "./mocks.ts";

// Issue #136: one thread per trip. A report on it is the timeline's red sign,
// which opens the thread at the report; the dispatcher writes to the driver, a
// store or everyone, from the sheet, the trip page or "Notify store"; and a
// message under the bell opens its thread.

const at = (utc: string) => `2027-03-01T${utc}:00Z`;

async function open(page: import("@playwright/test").Page): Promise<Desk> {
  await page.clock.install({ time: new Date(LIVE_NOW) });
  const thread = tripThread("t-VEH020", "VEH020", ["OUT061", "OUT063"], "dispatcher", [
    said("m1", at("09:00"), { body: "Load is on, leaving now", authorName: "Dilan R.", authorRole: "driver", audience: "dispatch" }),
    said("r1", at("10:05"), { kind: "report", reportType: "road_disruption", authorName: "Dilan R.", authorRole: "driver", audience: "dispatch", body: "Road closed at Akuressa, taking the bypass" }),
    said("m2", at("10:20"), { body: "Two crates short at loading", authorName: "Kasun", authorRole: "loader", audience: "dispatch", kind: "report", reportType: "loading_shortfall" }),
  ]);
  const desk = await serve(page, { ...liveDay(), threads: [thread] });
  await page.goto("/#/live");
  return desk;
}

test("a report is a warning sign on its run, and the sign opens the thread at that report", async ({ page }) => {
  await open(page);
  await page.getByRole("button", { name: "Timeline", exact: true }).click();
  const timeline = page.getByRole("region", { name: "Timeline" });
  await expect(timeline.getByTestId("report-sign")).toHaveCount(2);
  await timeline.getByRole("button", { name: /Road disruption reported by the driver at 15:35/ }).click();

  const sheet = page.getByRole("dialog", { name: "Trip messages" });
  await expect(sheet.getByRole("heading", { name: "Messages · VEH020" })).toBeVisible();
  const report = sheet.locator("#message-r1");
  await expect(report).toContainText("Report · Road disruption");
  await expect(report).toContainText("Road closed at Akuressa");
  await expect(report).toBeInViewport();
  // The dispatcher reads everything on the trip, whoever it was for.
  await expect(sheet.getByTestId("thread-message")).toHaveCount(3);
  await expect(sheet.getByText("Two crates short at loading")).toBeVisible();
});

test("the dispatcher writes to the driver with @driver and to everyone on the trip", async ({ page }) => {
  const desk = await open(page);
  await page.getByRole("button", { name: "Timeline", exact: true }).click();
  await page.getByRole("region", { name: "Timeline" }).getByTestId("report-sign").first().click();
  const sheet = page.getByRole("dialog", { name: "Trip messages" });
  const message = sheet.getByRole("textbox", { name: "Message" });

  await sheet.getByRole("radio", { name: "Everyone on the trip" }).click();
  await message.fill("@driver take the bypass, OUT063 knows");
  await expect(sheet.getByRole("radio", { name: "Dilan R. (DRV-00133)" })).toHaveAttribute("aria-checked", "true");
  await expect(message).toHaveValue("take the bypass, OUT063 knows");
  await sheet.getByRole("button", { name: "Send", exact: true }).click();
  await expect(sheet.getByText("take the bypass, OUT063 knows")).toBeVisible();

  await sheet.getByRole("radio", { name: "Everyone on the trip" }).click();
  await message.fill("Road closed at Akuressa; expect about 20 minutes more");
  await sheet.getByRole("button", { name: "Send", exact: true }).click();
  await expect(sheet.getByText("To everyone on the trip").last()).toBeVisible();

  const posts = desk.commands.filter((c) => c.kind === "message:Post").map((c) => c.payload);
  expect(posts).toHaveLength(2);
  expect(posts[0]).toMatchObject({ threadId: "thread-t-VEH020", to: "driver", body: "take the bypass, OUT063 knows" });
  expect(posts[0]!.clientMessageId).toBeTruthy();
  expect(posts[1]).toMatchObject({ to: "all" });
  expect(posts[1]).not.toHaveProperty("outletId");
});

test("notify store opens the thread written to that store with the new expected arrival", async ({ page }) => {
  const desk = await open(page);
  const card = page.getByRole("region", { name: "Needs you" }).getByRole("listitem").filter({ hasText: "OUT063 · may miss its window" });
  await card.getByRole("button", { name: "Notify store" }).click();
  const sheet = page.getByRole("dialog", { name: "Trip messages" });
  await expect(sheet.getByRole("radio", { name: "OUT063" })).toHaveAttribute("aria-checked", "true");
  await expect(sheet.getByRole("textbox", { name: "Message" })).toHaveValue(/expected 17:06/);
  await sheet.getByRole("button", { name: "Send", exact: true }).click();
  await expect(sheet.getByText("To OUT063")).toBeVisible();
  expect(desk.commands.find((c) => c.kind === "message:Post")?.payload).toMatchObject({ to: "outlet", outletId: "OUT063" });
});

test("the trip page sends an update to the store expected next", async ({ page }) => {
  const desk = await open(page);
  await page.getByRole("button", { name: "Timeline", exact: true }).click();
  await page.getByRole("button", { name: "VEH020 · Style · Matara, At risk" }).click();
  await page.getByRole("button", { name: "Send to OUT063" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Sent to OUT063 at 16:12" })).toBeVisible();
  expect(desk.commands.find((c) => c.kind === "message:Post")?.payload).toMatchObject({ to: "outlet", outletId: "OUT063", body: expect.stringContaining("expected 17:06") });

  await page.getByRole("button", { name: "All messages ›" }).click();
  await expect(page.getByRole("dialog", { name: "Trip messages" })).toBeVisible();
});

test("every message is under the bell, and Reply opens its thread", async ({ page }) => {
  await open(page);
  const n = {
    notificationId: "n-msg", eventType: "message.posted", title: "Driver report · VEH020", body: "Road closed at Akuressa, taking the bypass",
    subjectType: "thread", subjectId: "thread-t-VEH020", createdAt: at("10:05"), readAt: null,
  };
  await page.route("**/api/notifications**", (route) => {
    const { pathname } = new URL(route.request().url());
    const json = (body: unknown) => ({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
    if (pathname.endsWith("/stream")) return route.fulfill({ status: 200, contentType: "text/event-stream", body: 'event: unread\ndata: {"count":1}\n\n' });
    if (pathname.endsWith("/unread-count")) return route.fulfill(json({ count: 1 }));
    return route.fulfill(json({ items: [n], nextCursor: null }));
  });
  await page.reload();
  await page.getByRole("button", { name: "Notifications, 1 unread" }).click();
  const row = page.getByRole("dialog", { name: "Notifications" }).getByRole("listitem").first();
  await expect(row).toContainText("Message");
  await expect(row).toContainText("Driver report · VEH020");
  await row.getByRole("button", { name: "Reply" }).click();
  await expect(page.getByRole("dialog", { name: "Trip messages" }).getByText("Road closed at Akuressa, taking the bypass")).toBeVisible();
});
