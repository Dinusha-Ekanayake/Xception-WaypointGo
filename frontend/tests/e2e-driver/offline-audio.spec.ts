import { expect, test } from "@playwright/test";
import { said, tripThread } from "../thread-mocks.ts";
import { serve } from "./mocks.ts";

// R-MSG-11: while there is signal the driver's phone keeps the audio of the
// trip's messages, so Listen plays it later in a valley with none. The phone's
// player is stood in for here, so the test sees which copy it was given.

const TRIP = "00000000-0000-7000-8000-0000000000aa";

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    const played: string[] = [];
    (window as unknown as { played: string[] }).played = played;
    class FakeAudio {
      src: string;
      onended: (() => void) | null = null;
      onerror: (() => void) | null = null;
      constructor(src: string) {
        this.src = src;
      }
      play(): Promise<void> {
        played.push(this.src);
        setTimeout(() => (this.src.startsWith("blob:") ? this.onended?.() : this.onerror?.()), 20);
        return Promise.resolve();
      }
      pause(): void {}
    }
    (window as unknown as { Audio: unknown }).Audio = FakeAudio;
  });
});

test("a message heard with no signal plays the copy kept while online", async ({ page, context }) => {
  const server = await serve(page);
  server.threads = [
    tripThread(TRIP, "VEH043", ["OUT0101"], "driver", [
      said("m1", new Date(Date.now() - 10 * 60_000).toISOString(), { body: "Take the bypass at Akuressa", audience: "driver" }),
    ]),
  ];
  const fetched: string[] = [];
  await page.route("**/api/threads/*/messages/*/speech", async (route) => {
    if (server.offline) return route.abort("internetdisconnected");
    fetched.push(new URL(route.request().url()).pathname);
    await route.fulfill({ status: 200, contentType: "audio/mp4", body: Buffer.from([0, 0, 0, 24, 102, 116, 121, 112]) });
  });

  await page.goto("/");
  await expect.poll(() => fetched.length).toBe(1);
  await page.getByRole("button", { name: /^Messages/ }).click();
  const screen = page.getByRole("region", { name: "Messages" });
  await expect(screen.getByText("Take the bypass at Akuressa")).toBeVisible();

  await server.goOffline(context);
  await screen.getByRole("button", { name: "Listen" }).first().click();
  await expect.poll(() => page.evaluate(() => (window as unknown as { played: string[] }).played)).toEqual([expect.stringMatching(/^blob:/)]);
  expect(fetched).toHaveLength(1);
});
