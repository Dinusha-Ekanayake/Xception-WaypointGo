import { expect, test } from "@playwright/test";
import { said, tripThread } from "../thread-mocks.ts";
import { mockStore } from "./mocks.ts";

// FF-05 on the store's phone: a voice note shows its machine transcript under
// the play button, in the reader's language; a typed message can be listened to.

const at = (m: number) => new Date(Date.now() - m * 60_000).toISOString();

export const transcriptThread = () =>
  tripThread("trip-1", "VEH043", ["OUT085"], "store_manager", [
    said("m1", at(12), { body: "Running 20 min late, now expected 06:05", audience: "outlet", audienceOutlet: "OUT085" }),
    said("v1", at(6), {
      body: "", audience: "outlet", audienceOutlet: "OUT085", voiceNoteId: "voice-1", voiceDurationMs: 8000,
      transcript: { status: "done", text: "අපි පාරේ ඉන්නවා, විනාඩි දහයකින් එනවා", language: "si" },
    }),
  ]);

export async function openThread(page: import("@playwright/test").Page) {
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
  return page.getByRole("dialog", { name: "Trip messages" });
}

test("a voice note shows its transcript under the play button, and a typed message can be heard", async ({ page }) => {
  await mockStore(page, { threads: [transcriptThread()] });
  const sheet = await openThread(page);
  const transcript = sheet.getByTestId("transcript");
  await expect(transcript).toContainText("Machine transcript, check audio · Sinhala");
  await expect(transcript).toContainText("අපි පාරේ ඉන්නවා");
  await expect(sheet.getByRole("button", { name: "Play" }).first()).toBeVisible();
  await expect(sheet.getByRole("button", { name: /Listen/ }).first()).toBeVisible();
});

test("a transcript the machine heard unclearly says so plainly, in the reader's words (R-ML-37)", async ({ page }) => {
  const thread = tripThread("trip-1", "VEH043", ["OUT085"], "store_manager", [
    said("m1", at(12), { body: "Running 20 min late, now expected 06:05", audience: "outlet", audienceOutlet: "OUT085" }),
    said("v1", at(6), {
      body: "", audience: "outlet", audienceOutlet: "OUT085", voiceNoteId: "voice-1", voiceDurationMs: 8000,
      transcript: { status: "done", text: "lorry ... break ... wela", language: "en", unclear: true },
    }),
  ]);
  await mockStore(page, { threads: [thread] });
  const sheet = await openThread(page);
  await expect(sheet.getByTestId("transcript")).toContainText("Machine transcript, check audio · heard unclearly, some words may be wrong");
});
