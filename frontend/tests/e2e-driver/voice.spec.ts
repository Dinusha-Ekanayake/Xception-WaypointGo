import { expect, test } from "@playwright/test";
import { tripThread } from "../thread-mocks.ts";
import { serve } from "./mocks.ts";

// Issue #136: a voice note recorded on the phone's microphone. Chromium's fake
// microphone stands in for a real one, so this records, plays back and sends
// real audio through MediaRecorder.

test.use({
  permissions: ["microphone"],
  launchOptions: { args: ["--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream"] },
});

const TRIP = "00000000-0000-7000-8000-0000000000aa";

async function open(page: import("@playwright/test").Page) {
  const server = await serve(page);
  server.threads = [tripThread(TRIP, "VEH043", ["OUT0101", "OUT0202"], "driver")];
  await page.goto("/");
  await page.getByRole("button", { name: /^Messages/ }).click();
  const screen = page.getByRole("region", { name: "Messages" });
  await expect(screen.getByRole("textbox", { name: "Message" })).toBeVisible();
  return { server, screen };
}

async function record(screen: import("@playwright/test").Locator, page: import("@playwright/test").Page) {
  await screen.getByRole("button", { name: "Voice", exact: true }).click();
  await expect(screen.getByRole("status").filter({ hasText: /Recording/ })).toBeVisible();
  await page.waitForTimeout(1500);
  await screen.getByRole("button", { name: "Stop" }).click();
  await expect(screen.getByLabel("Your voice note")).toBeVisible();
}

test("the driver records a voice report, hears it back, and sends the audio before the message", async ({ page }) => {
  const { server, screen } = await open(page);
  await screen.getByRole("checkbox", { name: /Report a problem to the dispatcher/ }).check();
  await screen.getByRole("combobox", { name: "Problem" }).selectOption("vehicle_fault");
  await record(screen, page);
  await screen.getByRole("button", { name: "Report", exact: true }).click();

  await expect(screen.getByText("Report · Vehicle fault")).toBeVisible();
  expect(server.voices).toHaveLength(1);
  expect(server.voices[0]!.contentType).toMatch(/^audio\/(webm|ogg|mp4)$/);
  expect(server.voices[0]!.bytes).toBeGreaterThan(0);
  const post = server.commands.find((c) => c.kind === "message:Post")!.payload;
  expect(post).toMatchObject({ to: "dispatch", report: "vehicle_fault", body: "" });
  expect(server.voices[0]!.path).toBe(`/api/threads/thread-${TRIP}/voice/${post.voiceNoteId}`);
  const order = server.calls.filter((c) => c.startsWith("PUT /api/threads") || c === "POST /api/commands");
  expect(order[0]).toMatch(/^PUT \/api\/threads/);
});

test("with no signal a voice note is kept on the phone, then the audio and the message go in that order", async ({ page, context }) => {
  const { server, screen } = await open(page);
  await server.goOffline(context);
  await record(screen, page);
  await screen.getByRole("textbox", { name: "Message" }).fill("Tyre going flat near Deniyaya");
  await screen.getByRole("button", { name: "Send", exact: true }).click();
  await expect(screen.getByText("Saved on this device. It sends when the connection returns.")).toBeVisible();
  await expect(screen.getByTestId("waiting-message")).toContainText("Voice note");
  expect(server.voices).toHaveLength(0);

  await server.goOnline(context);
  await expect.poll(() => server.commands.find((c) => c.kind === "message:Post")?.payload.body).toBe("Tyre going flat near Deniyaya");
  expect(server.voices).toHaveLength(1);
  const upload = server.calls.findIndex((c) => c.startsWith("PUT /api/threads"));
  const batch = server.calls.findIndex((c) => c === "POST /api/sync");
  expect(upload).toBeGreaterThan(-1);
  expect(upload).toBeLessThan(batch);
  await expect(screen.getByTestId("waiting-message")).toHaveCount(0);
});
