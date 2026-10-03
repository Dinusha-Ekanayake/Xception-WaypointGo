import { expect, test } from "@playwright/test";
import { board, manifest, SESSION } from "./mocks.ts";

// identity OfflineOperatorTest's vector: PIN 2468.
const KASUN = {
  userId: "kasun-user",
  displayName: "Kasun",
  employeeCode: "LDR-00041",
  offlineVerifier: "pbkdf2-sha256$1000$AQIDBAUGBwgJCgsMDQ4PEA==$q/XmQuQ246Dwn9znhO4Ubr59wowIXIZcj+uxlcc2AwQ=",
};

test("offline, a loader switches with their PIN and the switch reaches the server before any queued work", async ({ page, context }) => {
  const calls: string[] = [];
  let replayed: { switches: Array<{ userId: string | null; at: string }> } | null = null;
  const json = (body: unknown) => ({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
  const current = manifest("trip-pin", false, 2);

  await page.route("**/api/**", async (route) => {
    const { pathname } = new URL(route.request().url());
    const method = route.request().method();
    calls.push(`${method} ${pathname}`);
    if (pathname === "/api/session") return route.fulfill(json(SESSION));
    if (pathname === "/api/session/crew") {
      return route.fulfill(json({ members: [KASUN], expiresAt: new Date(Date.now() + 3_600_000).toISOString() }));
    }
    if (pathname === "/api/session/operator/offline" && method === "POST") {
      replayed = route.request().postDataJSON();
      return route.fulfill(json({ operator: null }));
    }
    if (pathname === "/api/loading/trips") return route.fulfill(json(board(current)));
    if (pathname === "/api/reference/outlets") return route.fulfill(json([]));
    if (pathname === "/api/sync") return route.fulfill(json({ results: [] }));
    return route.fulfill({ status: 404, body: "not mocked" });
  });

  await page.goto("/");
  await expect(page.getByRole("button", { name: "Continue" })).toBeVisible();
  await expect.poll(() => calls.includes("GET /api/session/crew")).toBe(true);

  await context.setOffline(true);
  await page.getByRole("button", { name: "Lock loader" }).first().click();
  // Figma 08: the device is locked with the loader's name; someone else switches user.
  await expect(page.getByRole("heading", { name: "Device locked" })).toBeVisible();
  await page.getByRole("button", { name: "Switch user" }).click();
  await expect(page.getByText("You're offline. You can still sign in.")).toBeVisible();
  await page.getByRole("button", { name: /Kasun/ }).click();

  await page.getByLabel("4-digit PIN").fill("1111");
  await page.getByRole("button", { name: "Confirm" }).click();
  await expect(page.getByText("Incorrect PIN. 4 tries left.")).toBeVisible();

  await page.getByLabel("4-digit PIN").fill("2468");
  await page.getByRole("button", { name: "Confirm" }).click();
  await expect(page.getByRole("heading", { name: "Tonight's departures" })).toBeVisible();
  expect(replayed).toBeNull();

  await context.setOffline(false);
  await expect.poll(() => replayed?.switches.map((s) => s.userId)).toEqual([null, "kasun-user"]);
  const replayAt = calls.indexOf("POST /api/session/operator/offline");
  const firstSync = calls.indexOf("POST /api/sync");
  expect(firstSync === -1 || replayAt < firstSync).toBe(true);
});
