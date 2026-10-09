import { expect, test } from "@playwright/test";
import { board, manifest, SESSION } from "./mocks.ts";

// FF-05 on the loader's tablet: a voice message plays in Notifications with its
// machine transcript, and a reported issue can carry an optional voice note,
// heard by the dispatcher on the trip's thread beside the report.

test.use({
  permissions: ["microphone"],
  launchOptions: { args: ["--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream"] },
});

const json = (body: unknown) => ({ status: 200, contentType: "application/json", body: JSON.stringify(body) });

test("a voice message plays in Notifications with what was said", async ({ page }) => {
  const current = manifest("trip-v", false, 2);
  await page.route("**/api/**", async (route) => {
    const { pathname } = new URL(route.request().url());
    if (pathname === "/api/session") return route.fulfill(json(SESSION));
    if (pathname === "/api/loading/trips") return route.fulfill(json(board(current)));
    if (pathname === "/api/reference/outlets") return route.fulfill(json([]));
    if (pathname === "/api/notifications/unread-count") return route.fulfill(json({ count: 1 }));
    if (pathname === "/api/notifications/stream") return route.fulfill({ status: 503, body: "" });
    if (pathname === "/api/notifications") {
      return route.fulfill(json({ items: [{
        notificationId: "n-v", eventType: "message.posted", title: "Dinusha Bawantha · VEH043", body: "Voice message",
        subjectType: "thread", subjectId: "thread-1", createdAt: new Date().toISOString(), readAt: null,
        facts: { voiceNoteId: "voice-1", messageId: "msg-1", voiceDurationMs: "5000" },
      }], nextCursor: null }));
    }
    if (pathname === "/api/threads/thread-1/messages/msg-1/transcript") {
      return route.fulfill(json({ status: "done", text: "Load the chilled crates last, VEH043 leaves at 04:30", language: "en" }));
    }
    return route.fulfill({ status: 404, body: "not mocked" });
  });
  await page.goto("/");
  await page.getByRole("button", { name: "Notifications, 1 unread" }).click();
  const sheet = page.getByRole("dialog", { name: "Notifications" });
  const note = sheet.getByRole("group", { name: /^Voice message from/ });
  await expect(note).toContainText("Load the chilled crates last, VEH043 leaves at 04:30");
  await expect(note.getByRole("button", { name: "Play", exact: true })).toBeVisible();
});

test("an issue can carry an optional voice note to the dispatcher", async ({ page }) => {
  const open = manifest("trip-test", false, 4);
  const commands: Array<{ kind: string; payload: Record<string, unknown> }> = [];
  const voiceUploads: string[] = [];
  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.pathname === "/api/session") return route.fulfill(json(SESSION));
    if (url.pathname === "/api/loading/trips") return route.fulfill(json(board(open)));
    if (url.pathname === "/api/reference/outlets") return route.fulfill(json([]));
    if (url.pathname === "/api/loading/trips/trip-test/manifest") return route.fulfill(json(open));
    if (url.pathname === "/api/threads/by-subject") {
      return route.fulfill(json({ threadId: "thread-t", subjectType: "trip", subjectId: "trip-test", depotCode: "KDY", vehicleId: "VEH043",
        serviceDate: "2027-03-01", outletIds: [], memberRole: "loader", myOutlets: [], open: true, channelOpen: false }));
    }
    if (request.method() === "PUT" && url.pathname.startsWith("/api/threads/thread-t/voice/")) {
      voiceUploads.push(url.pathname);
      return route.fulfill(json({ voiceNoteId: url.pathname.split("/").pop(), stored: true }));
    }
    if (url.pathname === "/api/commands" && request.method() === "POST") {
      const body = request.postDataJSON() as { kind: string; payload: Record<string, unknown> };
      commands.push(body);
      return route.fulfill(json({ commandId: "c", kind: body.kind, replayed: false, result: { rowVersion: 5 } }));
    }
    return route.fulfill({ status: 404, body: "not mocked" });
  });

  await page.goto("/");
  await page.getByRole("button", { name: "Continue" }).click();
  await page.getByRole("button", { name: "Report", exact: true }).first().click();
  const sheet = page.getByRole("dialog", { name: "Report an issue" });
  await sheet.getByRole("textbox").fill("Carton crushed on the pallet");
  await sheet.getByRole("button", { name: "Add voice note (optional)" }).click();
  await page.waitForTimeout(1500);
  await sheet.getByRole("button", { name: "Recording · tap to stop" }).click();
  await expect(sheet.getByRole("button", { name: "Remove voice note" })).toBeVisible();
  await sheet.getByRole("button", { name: "Send to dispatcher" }).click();

  await expect.poll(() => commands.map((c) => c.kind)).toContain("message:Post");
  const post = commands.find((c) => c.kind === "message:Post")!.payload;
  expect(post).toMatchObject({ threadId: "thread-t", to: "dispatch" });
  expect(typeof post.voiceNoteId).toBe("string");
  expect(voiceUploads).toEqual([expect.stringContaining(String(post.voiceNoteId))]);
  expect(commands.findIndex((c) => c.kind !== "message:Post")).toBeLessThan(commands.findIndex((c) => c.kind === "message:Post"));
});
