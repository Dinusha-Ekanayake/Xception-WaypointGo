import { expect, test } from "@playwright/test";
import { said, tripThread } from "../thread-mocks.ts";
import { LIVE_NOW, liveDay, serve } from "./mocks.ts";

// Roadmap FF-05: the dispatcher reads what a driver said without playing every
// note. The words are the engine's, labelled as a machine transcript; with none,
// the note says to play the audio.

const at = (utc: string) => `2027-03-01T${utc}:00Z`;

test("a voice report shows its machine transcript, and a note without one says to play it", async ({ page }) => {
  await page.clock.install({ time: new Date(LIVE_NOW) });
  const thread = tripThread("t-VEH020", "VEH020", ["OUT061", "OUT063"], "dispatcher", [
    said("v1", at("09:30"), {
      body: "", authorName: "Dilan R.", authorRole: "driver", audience: "dispatch", voiceNoteId: "voice-1", voiceDurationMs: 6000,
      transcript: { status: "none", text: null, language: null },
    }),
    said("r1", at("10:05"), {
      kind: "report", reportType: "road_disruption", body: "", authorName: "Dilan R.", authorRole: "driver", audience: "dispatch",
      voiceNoteId: "voice-2", voiceDurationMs: 9000,
      transcript: { status: "done", text: "අකුරැස්ස පාර වහලා, බයිපාස් එකෙන් යනවා", language: "si" },
    }),
  ]);
  await serve(page, { ...liveDay(), threads: [thread] });
  await page.goto("/#/live");
  await page.getByRole("button", { name: "Timeline", exact: true }).click();
  await page.getByRole("region", { name: "Timeline" }).getByTestId("report-sign").first().click();

  const sheet = page.getByRole("dialog", { name: "Trip messages" });
  const report = sheet.locator("#message-r1");
  await expect(report.getByTestId("transcript")).toContainText("Machine transcript, check audio · Sinhala");
  await expect(report.getByTestId("transcript")).toContainText("අකුරැස්ස පාර වහලා");
  await expect(sheet.locator("#message-v1").getByTestId("transcript")).toHaveText("No transcript, play audio");
});
