import { expect, test, type Locator, type Page } from "@playwright/test";
import { tripThread } from "../thread-mocks.ts";
import { serve } from "./mocks.ts";

// Issue #136: a voice note as in a messaging app. Hold the mic to record and
// let go to send; slide up to lock, slide left to cancel. Chromium's fake
// microphone stands in for a real one, so this records real audio through
// MediaRecorder, and the sent note plays back from what the server kept.

test.use({
  permissions: ["microphone"],
  launchOptions: { args: ["--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream"] },
});

const TRIP = "00000000-0000-7000-8000-0000000000aa";

async function open(page: Page) {
  const server = await serve(page);
  server.threads = [tripThread(TRIP, "VEH043", ["OUT0101", "OUT0202"], "driver")];
  await page.goto("/");
  await page.getByRole("button", { name: /^Messages/ }).click();
  const screen = page.getByRole("region", { name: "Messages" });
  await expect(screen.getByRole("textbox", { name: "Message" })).toBeVisible();
  return { server, screen };
}

/** Puts a finger on the mic and keeps it there; answers where it went down. */
async function press(page: Page, screen: Locator) {
  const mic = screen.getByRole("button", { name: "Hold to record a voice note" });
  const box = (await mic.boundingBox())!;
  const at = { x: box.x + box.width / 2, y: box.y + box.height / 2 };
  await page.mouse.move(at.x, at.y);
  await page.mouse.down();
  await expect(screen.getByRole("status", { name: /^Recording/ })).toBeVisible();
  return at;
}

test("holding the mic records, and letting go sends the voice report, audio first", async ({ page }) => {
  const { server, screen } = await open(page);
  await expect(screen.getByRole("button", { name: "Voice", exact: true })).toHaveCount(0);
  await screen.getByRole("checkbox", { name: /Report a problem to the dispatcher/ }).check();
  await screen.getByRole("combobox", { name: "Problem" }).selectOption("vehicle_fault");

  await press(page, screen);
  await expect(screen.getByText("Slide to cancel")).toBeVisible();
  await page.waitForTimeout(1500);
  await page.mouse.up();

  const note = screen.getByTestId("thread-message").getByTestId("voice-note");
  await expect(note).toBeVisible();
  await expect(screen.getByText("Report · Vehicle fault")).toBeVisible();
  expect(server.voices).toHaveLength(1);
  expect(server.voices[0]!.contentType).toMatch(/^audio\/(webm|ogg|mp4)$/);
  const post = server.commands.find((c) => c.kind === "message:Post")!.payload;
  expect(post).toMatchObject({ to: "dispatch", report: "vehicle_fault", body: "" });
  expect(server.voices[0]!.path).toBe(`/api/threads/thread-${TRIP}/voice/${post.voiceNoteId}`);
  const order = server.calls.filter((c) => c.startsWith("PUT /api/threads") || c === "POST /api/commands");
  expect(order[0]).toMatch(/^PUT \/api\/threads/);

  // The sent note is a waveform card, not the browser's player, and it plays.
  await expect(note.locator("[role=slider] > span")).toHaveCount(36);
  await expect(page.locator("audio[controls]")).toHaveCount(0);
  await note.getByRole("button", { name: "Play", exact: true }).click();
  await expect(note.getByRole("button", { name: "Pause", exact: true })).toBeVisible();
  await expect(note.getByRole("button", { name: "Playback speed" })).toHaveText("1×");
  await note.getByRole("button", { name: "Playback speed" }).click();
  await expect(note.getByRole("button", { name: "Playback speed" })).toHaveText("1.5×");
});

test("a tap is not a voice note, and sliding left throws the recording away", async ({ page }) => {
  const { server, screen } = await open(page);
  const mic = screen.getByRole("button", { name: "Hold to record a voice note" });
  await mic.click();
  await expect(screen.getByRole("status").filter({ hasText: "Hold to record, release to send" })).toBeVisible();

  const at = await press(page, screen);
  await page.waitForTimeout(1000);
  await page.mouse.move(at.x - 60, at.y, { steps: 4 });
  await page.mouse.move(at.x - 160, at.y, { steps: 6 });
  await expect(screen.getByRole("status", { name: /^Recording/ })).toHaveCount(0);
  await page.mouse.up();
  await page.waitForTimeout(500);
  expect(server.voices).toHaveLength(0);
  expect(server.commands.filter((c) => c.kind === "message:Post")).toHaveLength(0);
});

test("sliding up locks the recording; it can be stopped, heard and sent, and offline it waits on the phone", async ({ page, context }) => {
  const { server, screen } = await open(page);
  await server.goOffline(context);

  const at = await press(page, screen);
  await page.mouse.move(at.x, at.y - 40, { steps: 4 });
  await page.mouse.move(at.x, at.y - 120, { steps: 6 });
  await page.mouse.up();
  // Locked: still recording after the finger is gone.
  await expect(screen.getByRole("status", { name: /^Recording/ })).toBeVisible();
  await page.waitForTimeout(1200);
  await screen.getByRole("button", { name: "Stop and listen" }).click();

  const draft = screen.getByRole("group", { name: "Your voice note" });
  await expect(draft).toBeVisible();
  await expect(draft.locator("[role=slider] > span")).toHaveCount(36);
  await screen.getByRole("button", { name: "Send voice note" }).click();

  await expect(screen.getByText("Saved on this device. It sends when the connection returns.")).toBeVisible();
  await expect(screen.getByTestId("waiting-message")).toContainText("Voice note");
  expect(server.voices).toHaveLength(0);

  await server.goOnline(context);
  await expect.poll(() => server.commands.find((c) => c.kind === "message:Post")?.payload.voiceNoteId).toBeTruthy();
  expect(server.voices).toHaveLength(1);
  const upload = server.calls.findIndex((c) => c.startsWith("PUT /api/threads"));
  const batch = server.calls.findIndex((c) => c === "POST /api/sync");
  expect(upload).toBeGreaterThan(-1);
  expect(upload).toBeLessThan(batch);
  await expect(screen.getByTestId("waiting-message")).toHaveCount(0);
});
