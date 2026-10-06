import { expect, test } from "@playwright/test";
import { tripThread } from "../thread-mocks.ts";
import { serve, startTrip } from "./mocks.ts";

const TRIP = "00000000-0000-7000-8000-0000000000aa";
async function open(page: import("@playwright/test").Page) {
  const server = await serve(page, undefined, { askLocation: true });
  server.threads = [tripThread(TRIP, "VEH043", ["OUT0101", "OUT0202"], "driver")];
  return server;
}

// R-EXE-28: no phone use while driving. While the vehicle moves the driver's
// messages are covered and cannot be used; a draft waits underneath. With no
// location the driver says they have stopped. Report problem always works.

test.use({ geolocation: { latitude: 7.25, longitude: 80.6 }, permissions: ["geolocation"] });

test("with no location the driver says they have stopped before writing", async ({ page }) => {
  await open(page);
  await page.goto("/");
  await startTrip(page);
  await page.getByRole("button", { name: "Not now" }).click();
  await page.getByRole("button", { name: /^Messages/ }).click();
  const screen = page.getByRole("region", { name: "Messages" });
  const gate = screen.getByTestId("driving-gate");
  await expect(gate).toContainText("Stop to read and write messages");
  await expect(screen.getByRole("textbox", { name: "Message" })).toHaveCount(0);
  await gate.getByRole("button", { name: "I have stopped" }).click();
  await expect(gate).toHaveCount(0);
  await expect(screen.getByRole("textbox", { name: "Message" })).toBeVisible();
});

test("moving covers the messages and keeps the draft; stopping uncovers them", async ({ page, context }) => {
  test.setTimeout(90_000);
  await open(page);
  await page.goto("/");
  await startTrip(page);
  await page.getByRole("button", { name: "Share location" }).click();
  await page.getByRole("button", { name: /^Messages/ }).click();
  const screen = page.getByRole("region", { name: "Messages" });
  const gate = screen.getByTestId("driving-gate");

  // Standing still: the phone reports the same place, so the driver may write.
  await expect(gate).toHaveCount(0, { timeout: 30_000 });
  await screen.getByRole("textbox", { name: "Message" }).fill("Gate was locked at OUT0101");

  // Driving off: about 110 m every 5 s.
  let lat = 7.25;
  const drive = async () => {
    lat += 0.001;
    await context.setGeolocation({ latitude: lat, longitude: 80.6 });
  };
  await expect
    .poll(async () => {
      await drive();
      await page.waitForTimeout(2_500);
      return gate.count();
    }, { timeout: 45_000, intervals: [0] })
    .toBe(1);
  await expect(gate).toContainText("You are driving");
  await expect(gate).toContainText("Report problem");

  // Stopped again: the cover lifts and the draft is still there.
  await expect(gate).toHaveCount(0, { timeout: 45_000 });
  await expect(screen.getByRole("textbox", { name: "Message" })).toHaveValue("Gate was locked at OUT0101");
});
