import { expect, test } from "@playwright/test";
import { said, tripThread } from "../thread-mocks.ts";
import { serve } from "./mocks.ts";

// Every message can be listened to: a typed one is read aloud in its own language
// by the phone's voice, and "Play messages" plays the new ones in order when the
// driver is stopped. The phone's voice is stood in for here, so the test hears
// what would be spoken.

const TRIP = "00000000-0000-7000-8000-0000000000aa";

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    const spoken: string[] = [];
    (window as unknown as { spoken: string[] }).spoken = spoken;
    const synth = {
      getVoices: () => [{ lang: "en-GB", name: "Test English", localService: true }, { lang: "si-LK", name: "Test Sinhala", localService: true }],
      cancel: () => undefined,
      speak: (u: { text: string; onend?: () => void }) => {
        spoken.push(u.text);
        setTimeout(() => u.onend?.(), 20);
      },
      addEventListener: () => undefined,
    };
    Object.defineProperty(window, "speechSynthesis", { value: synth, configurable: true });
    (window as unknown as { SpeechSynthesisUtterance: unknown }).SpeechSynthesisUtterance = class {
      text: string; voice: unknown; lang = ""; rate = 1; onend?: () => void; onerror?: () => void;
      constructor(text: string) { this.text = text; }
    };
  });
});

test("the driver plays the new messages in order, and each typed one can be listened to", async ({ page }) => {
  const server = await serve(page);
  server.threads = [
    tripThread(TRIP, "VEH043", ["OUT0101"], "driver", [
      said("m1", new Date(Date.now() - 20 * 60_000).toISOString(), { body: "Take the bypass at Akuressa", audience: "driver" }),
      said("m2", new Date(Date.now() - 10 * 60_000).toISOString(), { body: "OUT0101 ගේට්ටුව විවෘතයි", audience: "driver" }),
    ]),
  ];
  await page.goto("/");
  await page.getByRole("button", { name: /^Messages/ }).click();
  const screen = page.getByRole("region", { name: "Messages" });

  const bar = screen.getByTestId("play-messages");
  await bar.getByRole("button", { name: "Play 2 new messages" }).click();
  await expect.poll(() => page.evaluate(() => (window as unknown as { spoken: string[] }).spoken)).toEqual([
    "Take the bypass at Akuressa", "OUT0101 ගේට්ටුව විවෘතයි",
  ]);
  await expect(bar).toHaveCount(0, { timeout: 5_000 });

  await screen.getByRole("button", { name: "Listen" }).first().click();
  await expect.poll(() => page.evaluate(() => (window as unknown as { spoken: string[] }).spoken.length)).toBe(3);
});
