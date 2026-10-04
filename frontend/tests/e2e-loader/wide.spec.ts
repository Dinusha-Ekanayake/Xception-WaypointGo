import { expect, test, type Page } from "@playwright/test";
import { board, manifest, OPERATOR, SESSION } from "./mocks.ts";

// Figma 07, 09 and 10: on a dock tablet, desk or terminal the board is a table
// and the PIN keypad sits beside the crew list. The fourth digit signs in.

// identity OfflineOperatorTest's vector: PIN 2468.
const AMAYA = {
  userId: "amaya-user",
  displayName: "Amaya Dissanayake",
  employeeCode: "LDR-00041",
  offlineVerifier: "pbkdf2-sha256$1000$AQIDBAUGBwgJCgsMDQ4PEA==$q/XmQuQ246Dwn9znhO4Ubr59wowIXIZcj+uxlcc2AwQ=",
};
const ISURU = { userId: OPERATOR.userId, displayName: OPERATOR.displayName, employeeCode: OPERATOR.employeeCode, offlineVerifier: null };
const json = (body: unknown, status = 200) => ({ status, contentType: "application/json", body: JSON.stringify(body) });

async function serve(page: Page, answer: (pin: string) => { status: number; body: unknown }): Promise<string[]> {
  const pins: string[] = [];
  const current = manifest("trip-wide", false, 2);
  await page.route("**/api/**", async (route) => {
    const { pathname } = new URL(route.request().url());
    const method = route.request().method();
    if (pathname === "/api/session") return route.fulfill(json(SESSION));
    if (pathname === "/api/session/crew") {
      return route.fulfill(json({ members: [ISURU, AMAYA], expiresAt: new Date(Date.now() + 3_600_000).toISOString() }));
    }
    if (pathname === "/api/session/operator" && method === "POST") {
      const { pin } = route.request().postDataJSON() as { pin: string };
      pins.push(pin);
      const a = answer(pin);
      return route.fulfill(json(a.body, a.status));
    }
    if (pathname === "/api/session/operator") return route.fulfill({ status: 204 });
    if (pathname === "/api/session/operator/offline") return route.fulfill(json({ operator: null }));
    if (pathname === "/api/loading/trips") return route.fulfill(json(board(current)));
    if (pathname === "/api/reference/outlets") return route.fulfill(json([]));
    if (pathname === "/api/sync") return route.fulfill(json({ results: [] }));
    return route.fulfill({ status: 404, body: "not mocked" });
  });
  return pins;
}

async function toSignIn(page: Page): Promise<void> {
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Tonight's departures" })).toBeVisible();
  await page.getByRole("button", { name: /Switch user/ }).first().click();
  await expect(page.getByRole("heading", { name: "Who's loading?" })).toBeVisible();
}

const tap = async (page: Page, digits: string) => {
  for (const d of digits) await page.getByRole("button", { name: d, exact: true }).click();
};

test.describe("landscape dock tablet", () => {
  test.use({ viewport: { width: 1280, height: 800 } });

  test("the board is a table with the vehicle's load against its capacity", async ({ page }) => {
    await serve(page, () => ({ status: 401, body: { triesLeft: 4 } }));
    await page.goto("/");
    const table = page.getByRole("table", { name: "Tonight's departures" });
    await expect(table).toBeVisible();
    const row = table.getByRole("row").filter({ hasText: "VEH043" });
    await expect(row).toContainText("0.2 / 26.4");
    await expect(row).toContainText("You");
    await expect(row.getByRole("button", { name: "Continue" })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  });

  test("the keypad waits for a name, says a wrong PIN, then signs in on the fourth digit", async ({ page }) => {
    const pins = await serve(page, (pin) =>
      pin === "2468"
        ? { status: 200, body: { userId: AMAYA.userId, displayName: AMAYA.displayName, employeeCode: AMAYA.employeeCode, since: new Date().toISOString() } }
        : { status: 401, body: { triesLeft: 2 } });
    await toSignIn(page);
    await expect(page.getByText("Select your name to continue.")).toBeVisible();
    await expect(page.getByRole("button", { name: "1", exact: true })).toBeDisabled();

    await page.getByRole("button", { name: /Amaya/ }).click();
    await expect(page.getByRole("button", { name: /Amaya/ })).toHaveAttribute("aria-pressed", "true");
    await tap(page, "1111");
    await expect(page.getByText("Incorrect PIN. 2 tries left.")).toBeVisible();

    await tap(page, "2468");
    await expect(page.getByRole("heading", { name: "Tonight's departures" })).toBeVisible();
    await expect(page.locator("text=Amaya Dissanayake >> visible=true").first()).toBeVisible();
    expect(pins).toEqual(["1111", "2468"]);
  });

  test("locked, the keypad sits beside who locked it and their trip, and their PIN unlocks", async ({ page }) => {
    const pins = await serve(page, (pin) =>
      pin === "2468"
        ? { status: 200, body: { userId: ISURU.userId, displayName: ISURU.displayName, employeeCode: ISURU.employeeCode, since: new Date().toISOString() } }
        : { status: 401, body: { triesLeft: 2 } });
    await page.goto("/");
    await expect(page.getByRole("heading", { name: "Tonight's departures" })).toBeVisible();
    await page.getByRole("button", { name: "Lock loader" }).click();

    await expect(page.getByRole("heading", { name: "Device locked" })).toBeVisible();
    await expect(page.getByText("Enter PIN to unlock")).toBeVisible();
    await expect(page.getByRole("button", { name: /Enter PIN to unlock/ })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Switch user" })).toBeVisible();
    await tap(page, "2468");
    await expect(page.getByRole("heading", { name: "Tonight's departures" })).toBeVisible();
    expect(pins).toEqual(["2468"]);
  });

  test("too many tries pauses the keypad with a countdown", async ({ page }) => {
    await serve(page, () => ({ status: 429, body: { triesLeft: 0, retryAfterSeconds: 300 } }));
    await toSignIn(page);
    await page.getByRole("button", { name: /Amaya/ }).click();
    await tap(page, "0000");
    await expect(page.getByRole("heading", { name: "PIN entry paused" })).toBeVisible();
    await expect(page.getByText(/Wait [45]:\d\d, or ask your supervisor\./)).toBeVisible();
    await expect(page.getByRole("button", { name: "5", exact: true })).toBeDisabled();
  });
});

test.describe("desk terminal with a keyboard", () => {
  test.use({ viewport: { width: 1920, height: 1080 } });

  test("typing the PIN on the keyboard signs in, offline, against the kept crew list", async ({ page, context }) => {
    await serve(page, () => ({ status: 500, body: {} }));
    await toSignIn(page);
    await expect(page.getByRole("button", { name: /Amaya/ })).toBeEnabled();
    await context.setOffline(true);
    await page.getByRole("button", { name: /Amaya/ }).click();
    await expect(page.locator("#loader-pin-pad")).toBeFocused();
    await page.keyboard.type("2468");
    await expect(page.getByRole("heading", { name: "Tonight's departures" })).toBeVisible();
    await expect(page.locator("text=Amaya Dissanayake >> visible=true").first()).toBeVisible();
  });
});

test.describe("portrait tablet", () => {
  test.use({ viewport: { width: 768, height: 1024 } });

  test("keeps the crew list and the keypad side by side", async ({ page }) => {
    await serve(page, () => ({ status: 401, body: { triesLeft: 4 } }));
    await toSignIn(page);
    await expect(page.getByText("Select your name to continue.")).toBeVisible();
    await expect(page.getByRole("button", { name: /Amaya/ })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  });
});
