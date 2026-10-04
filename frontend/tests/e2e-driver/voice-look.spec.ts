import { expect, test } from "@playwright/test";
import { said, tripThread } from "../thread-mocks.ts";
import { serve } from "./mocks.ts";

// Screenshots of the voice note's states for review (issue #136). Not an
// assertion of pixels: it saves each state under test-results/voice-look.

test.use({
  permissions: ["microphone"],
  launchOptions: { args: ["--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream"] },
});

const TRIP = "00000000-0000-7000-8000-0000000000aa";
const OUT = "test-results/voice-look";

test("voice note states, for review", async ({ page }) => {
  const server = await serve(page);
  const at = (m: number) => new Date(Date.now() - m * 60_000).toISOString();
  server.threads = [
    tripThread(TRIP, "VEH043", ["OUT0101", "OUT0202"], "driver", [
      said("m1", at(30), { body: "Take the bypass at Akuressa", audience: "driver" }),
      said("v1", at(25), { body: "", audience: "driver", voiceNoteId: "voice-a", voiceDurationMs: 14_000 }),
      said("r1", at(20), { body: "", mine: true, authorRole: "driver", authorName: "Nimal Perera", kind: "report", reportType: "road_disruption", audience: "dispatch", voiceNoteId: "voice-b", voiceDurationMs: 9_000 }),
      said("v2", at(10), { body: "", mine: true, authorRole: "driver", authorName: "Nimal Perera", audience: "dispatch", voiceNoteId: "voice-c", voiceDurationMs: 42_000 }),
    ]),
  ];
  await page.goto("/");
  await page.getByRole("button", { name: /^Messages/ }).click();
  const screen = page.getByRole("region", { name: "Messages" });
  await expect(screen.getByTestId("voice-note")).toHaveCount(3);
  await page.screenshot({ path: `${OUT}/1-thread.png` });

  const mic = screen.getByRole("button", { name: "Hold to record a voice note" });
  const box = (await mic.boundingBox())!;
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  await page.waitForTimeout(1500);
  await page.screenshot({ path: `${OUT}/2-holding.png` });
  await page.mouse.move(box.x - 60, box.y + box.height / 2, { steps: 5 });
  await page.screenshot({ path: `${OUT}/3-sliding-to-cancel.png` });
  await page.mouse.move(box.x + box.width / 2, box.y - 120, { steps: 6 });
  await page.mouse.up();
  await page.waitForTimeout(1200);
  await page.screenshot({ path: `${OUT}/4-locked.png` });
  await screen.getByRole("button", { name: "Stop and listen" }).click();
  await expect(screen.getByRole("group", { name: "Your voice note" })).toBeVisible();
  await page.screenshot({ path: `${OUT}/5-review.png` });
  await screen.getByRole("button", { name: "Send voice note" }).click();
  await expect(screen.getByTestId("thread-message").getByTestId("voice-note")).toHaveCount(4);
  await page.waitForTimeout(800);
  const last = screen.getByTestId("thread-message").getByTestId("voice-note").last();
  await last.getByRole("button", { name: "Play", exact: true }).click();
  await page.waitForTimeout(900);
  await page.screenshot({ path: `${OUT}/6-sent-playing.png` });

  await screen.getByRole("textbox", { name: "Message" }).fill("On it");
  await page.screenshot({ path: `${OUT}/7-typing-shows-send.png` });

});
