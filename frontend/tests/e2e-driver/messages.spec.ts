import { expect, test } from "@playwright/test";
import { said, tripThread } from "../thread-mocks.ts";
import { serve } from "./mocks.ts";

// Issue #136: the driver's side of the trip's thread. The driver reads what the
// dispatcher wrote to them or to everyone, replies to the dispatcher or a store
// on the trip, and reports a problem, which only the dispatcher reads. Typed
// messages keep on the phone with no signal.

const TRIP = "00000000-0000-7000-8000-0000000000aa";

async function open(page: import("@playwright/test").Page) {
  const server = await serve(page);
  server.threads = [
    tripThread(TRIP, "VEH043", ["OUT0101", "OUT0202"], "driver", [
      said("m1", new Date(Date.now() - 20 * 60_000).toISOString(), { body: "Take the bypass at Akuressa", audience: "driver" }),
    ]),
  ];
  server.notifications = [
    {
      notificationId: "n1", eventType: "message.posted", title: "Dinusha Bawantha · VEH043", body: "Take the bypass at Akuressa",
      subjectType: "thread", subjectId: `thread-${TRIP}`, createdAt: new Date().toISOString(), readAt: null, facts: null,
    } as never,
  ];
  await page.goto("/");
  return server;
}

test("the driver reads the dispatcher's message, replies, and reports a problem to the dispatcher", async ({ page }) => {
  const server = await open(page);
  await page.getByRole("button", { name: "Messages, 1 new" }).click();
  const screen = page.getByRole("region", { name: "Messages" });
  await expect(screen.getByText("Take the bypass at Akuressa")).toBeVisible();
  await expect(screen.getByText("To the driver")).toBeVisible();

  await screen.getByRole("textbox", { name: "Message" }).fill("On it, about 20 minutes more");
  await screen.getByRole("button", { name: "Send", exact: true }).click();
  await expect(screen.getByText("On it, about 20 minutes more")).toBeVisible();

  await screen.getByRole("checkbox", { name: /Report a problem to the dispatcher/ }).check();
  await screen.getByRole("combobox", { name: "Problem" }).selectOption("road_disruption");
  await screen.getByRole("textbox", { name: "Message" }).fill("Road closed after the bridge");
  await screen.getByRole("button", { name: "Report", exact: true }).click();
  await expect(screen.getByText("Report · Road disruption")).toBeVisible();

  const posts = server.commands.filter((c) => c.kind === "message:Post").map((c) => c.payload);
  expect(posts[0]).toMatchObject({ threadId: `thread-${TRIP}`, to: "dispatch", body: "On it, about 20 minutes more" });
  expect(posts[1]).toMatchObject({ to: "dispatch", report: "road_disruption", body: "Road closed after the bridge" });
  // Opening the thread reads its notification.
  expect(server.commands.some((c) => c.kind === "notification:MarkRead")).toBe(true);
});

test("with no signal a typed message is kept on the phone and sent when the signal returns", async ({ page, context }) => {
  const server = await open(page);
  await page.getByRole("button", { name: /^Messages/ }).click();
  const screen = page.getByRole("region", { name: "Messages" });
  await expect(screen.getByText("Take the bypass at Akuressa")).toBeVisible();

  await server.goOffline(context);
  await expect(screen.getByText(/No signal\. What you write or record is kept on this phone/)).toBeVisible();
  // A voice note is kept with no signal too (voice.spec.ts).
  await expect(screen.getByRole("button", { name: "Hold to record a voice note" })).toBeEnabled();
  await screen.getByRole("textbox", { name: "Message" }).fill("Signal gone near Deniyaya");
  await screen.getByRole("button", { name: "Send", exact: true }).click();
  await expect(screen.getByText("Saved on this device. It sends when the connection returns.")).toBeVisible();
  await expect(screen.getByTestId("waiting-message")).toContainText("Signal gone near Deniyaya");
  expect(server.commands.some((c) => c.kind === "message:Post")).toBe(false);

  await server.goOnline(context);
  await expect.poll(() => server.commands.find((c) => c.kind === "message:Post")?.payload.body).toBe("Signal gone near Deniyaya");
});

/** Half a second of a quiet tone, as a WAV file the browser can play and decode. */
function wav(): Buffer {
  const rate = 8000, n = 4000, data = Buffer.alloc(n * 2);
  for (let i = 0; i < n; i++) data.writeInt16LE(Math.round(Math.sin(i / 6) * 8000 * (i / n)), i * 2);
  const head = Buffer.alloc(44);
  head.write("RIFF", 0); head.writeUInt32LE(36 + data.length, 4); head.write("WAVE", 8); head.write("fmt ", 12);
  head.writeUInt32LE(16, 16); head.writeUInt16LE(1, 20); head.writeUInt16LE(1, 22); head.writeUInt32LE(rate, 24);
  head.writeUInt32LE(rate * 2, 28); head.writeUInt16LE(2, 32); head.writeUInt16LE(16, 34); head.write("data", 36);
  head.writeUInt32LE(data.length, 40);
  return Buffer.concat([head, data]);
}

test("a voice message under the driver's notifications plays the voice itself, never text to speech", async ({ page }) => {
  const server = await serve(page);
  server.threads = [tripThread(TRIP, "VEH043", ["OUT0101"], "driver")];
  server.notifications = [
    {
      notificationId: "n-voice", eventType: "message.posted", title: "Dinusha Bawantha · VEH043", body: "Voice message",
      subjectType: "thread", subjectId: `thread-${TRIP}`, createdAt: new Date().toISOString(), readAt: null,
      facts: { voiceNoteId: "voice-1", voiceDurationMs: "500", voicePeaks: "20,60,100,40" },
    } as never,
  ];
  await page.route(`**/api/threads/thread-${TRIP}/voice/voice-1`, (route) => route.fulfill({ status: 200, contentType: "audio/wav", body: wav() }));
  await page.goto("/");

  // The top bar carries Messages as an icon, with the count of new ones.
  await expect(page.getByRole("button", { name: "Messages, 1 new" })).toBeVisible();

  const note = page.getByRole("group", { name: /^Voice message from/ });
  await expect(note).toBeVisible();
  await expect(note.locator("[role=slider] > span")).toHaveCount(4);
  await note.getByRole("button", { name: "Play", exact: true }).click();
  await expect(note.getByRole("button", { name: "Pause", exact: true })).toBeVisible();
  // The position moves with the audio, then rests at the start when it ends.
  await expect(note.getByRole("button", { name: "Play", exact: true })).toBeVisible({ timeout: 4000 });
  const spoke = await page.evaluate(() => (window.speechSynthesis ? window.speechSynthesis.speaking : false));
  expect(spoke).toBe(false);
});
