import { expect, test, type Page } from "@playwright/test";
import { board, manifest, OPERATOR, SESSION } from "./mocks.ts";

// R-LOD-13: a trip is loaded only once its vehicle's driver has reported at
// the depot. The board lists arrived vehicles by default, says why a trip whose
// vehicle is not here cannot be taken, and a tick on a trip nobody holds takes it
// and ticks the item, in one tap.

const json = (body: unknown) => ({ status: 200, contentType: "application/json", body: JSON.stringify(body) });

async function serve(page: Page, arrived: boolean, start: Partial<ReturnType<typeof manifest>> = {}) {
  const m: ReturnType<typeof manifest> = { ...manifest("trip-arrival", false, 1), status: "NOT_STARTED", holder: null, ...start };
  const sent: string[] = [];
  const trips = board(m).map((t) => ({ ...t, driverAtDepotAt: arrived ? "2026-07-02T10:00:00Z" : null }));
  await page.route("**/api/**", async (route) => {
    const { pathname } = new URL(route.request().url());
    if (pathname === "/api/session") return route.fulfill(json(SESSION));
    if (pathname === "/api/loading/trips") return route.fulfill(json(trips));
    if (pathname === "/api/reference/outlets") return route.fulfill(json([]));
    if (pathname === "/api/loading/trips/trip-arrival/manifest") return route.fulfill(json(m));
    if (pathname === "/api/commands" && route.request().method() === "POST") {
      const c = route.request().postDataJSON() as { commandId: string; kind: string };
      sent.push(c.kind);
      // The server's answer: taking gives the trip to the operator; a tick moves the version on.
      if (c.kind === "loading:Start") Object.assign(m, { status: "IN_PROGRESS", holder: { userId: OPERATOR.userId, name: OPERATOR.displayName, employeeCode: OPERATOR.employeeCode, since: OPERATOR.since, lastActiveAt: OPERATOR.since } });
      m.rowVersion += 1;
      return route.fulfill(json({ commandId: c.commandId, kind: c.kind, replayed: false, result: { rowVersion: m.rowVersion, status: m.status } }));
    }
    return route.fulfill({ status: 404, body: "not mocked" });
  });
  return { m, sent };
}

test("a vehicle not at the depot is left off the board, and taking its trip says why", async ({ page }) => {
  const { m } = await serve(page, false);
  await page.goto("/");
  await expect(page.getByText("No vehicle has arrived yet")).toBeVisible();

  // One filter at a time (the board never shows two picked): All lists every trip.
  await expect(page.getByRole("button", { name: /Arrived only/ })).toHaveAttribute("aria-pressed", "true");
  await page.getByRole("button", { name: /^All \(/ }).click();
  await expect(page.getByRole("button", { name: /Arrived only/ })).toHaveAttribute("aria-pressed", "false");
  await expect(page.getByText("Vehicle not at the depot yet").first()).toBeVisible();
  await page.getByRole("button", { name: "Take trip" }).first().click();
  await expect(page.getByText(`${m.vehicleId} has not arrived yet`)).toBeVisible();
});

test("once the vehicle is here its trip opens, and a tick before Start loading takes the trip and ticks the item", async ({ page }) => {
  const { sent } = await serve(page, true);
  await page.goto("/");
  await page.getByRole("button", { name: "Take trip" }).first().click();
  await page.getByRole("button", { name: /Mark item 1 of .* loaded/ }).first().click();
  await expect.poll(() => sent).toEqual(["loading:Start", "loading:Check"]);
  await expect(page.getByText("Take the trip first")).toHaveCount(0);
});

test("a trip handed back mid-way is free to take again, never shown as locked to anyone", async ({ page }) => {
  // Loading, half ticked, held by nobody: what a hand back leaves (R-LOD-11).
  const { sent } = await serve(page, true, { status: "IN_PROGRESS", holder: null });
  await page.goto("/");
  await page.getByRole("button", { name: "Take trip" }).first().click();
  await expect(page.getByText(/Locked to you/)).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Hand back" })).toHaveCount(0);
  if (process.env.SHOTS_DIR) {
    await page.waitForTimeout(800);
    await page.screenshot({ path: `${process.env.SHOTS_DIR}/9-loader-handed-back.png` });
  }
  await page.getByRole("button", { name: "Take the trip" }).click();
  await expect.poll(() => sent).toEqual(["loading:Start"]);
  await expect(page.getByText(/Locked to you/)).toBeVisible();
  if (process.env.SHOTS_DIR) {
    await page.waitForTimeout(800);
    await page.screenshot({ path: `${process.env.SHOTS_DIR}/10-loader-taken-again.png` });
  }
  await page.getByRole("button", { name: /Mark item 1 of .* loaded/ }).first().click();
  await expect.poll(() => sent).toEqual(["loading:Start", "loading:Check"]);
});
