import { expect, test, type Page } from "@playwright/test";

// Issue #231: the demo control room. Off by default and invisible to every
// role; on, a banner shows the demo clock; every change is a demo:* command
// with a reason and the settings version.

async function signedInAdmin(page: Page, commands: Array<{ kind: string; expectedVersion: number | null; payload: Record<string, unknown> }>) {
  let enabled = false;
  let version = 0;
  const sims: unknown[] = [];
  await page.route("**/api/**", (route) => {
    const { pathname } = new URL(route.request().url());
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
    if (pathname === "/api/session") {
      return json({ userId: "0198a000-0000-7000-8000-000000000001", displayName: "Admin One", roles: ["admin"], operator: null, scope: [] });
    }
    if (pathname === "/api/demo") {
      return json({ enabled, now: "2026-10-05T10:35:00Z", offsetSeconds: enabled ? 3600 : 0, banner: true, simPointIntervalMs: 2000, positionFlushMs: 5000, speed: 10, rowVersion: version });
    }
    if (pathname === "/api/demo/scenario-runs") return json([]);
    if (pathname === "/api/demo/simulations") return json(sims);
    if (pathname === "/api/commands") {
      const command = route.request().postDataJSON();
      commands.push(command);
      if (command.kind === "demo:Enable") enabled = true;
      if (command.kind === "demo:Disable") enabled = false;
      if (command.kind === "demo:StartSimulation") {
        sims.push({ id: "s1", vehicle_id: "V001", service_date: "2026-10-05", trip_id: null, tick: 3, waypoints: 4, status: "running", failure: null, started_at: "2026-10-05T10:36:00Z" });
      }
      if (command.kind.startsWith("demo:") && command.kind !== "demo:StartSimulation") version += 1;
      return json({ commandId: command.commandId, kind: command.kind, replayed: false, result: {} });
    }
    return json({ items: [], nextCursor: null });
  });
}

test("demo mode is off by default, turns on with a reason, drives vehicles and turns off again", async ({ page }) => {
  const commands: Array<{ kind: string; expectedVersion: number | null; payload: Record<string, unknown> }> = [];
  await signedInAdmin(page, commands);
  await page.goto("/#demo");
  await expect(page.getByRole("heading", { name: "Demo mode" })).toBeVisible();
  await expect(page.getByText("Off: Waypoint runs on real time")).toBeVisible();
  await expect(page.getByText(/Demo mode · demo clock/)).toHaveCount(0);
  await expect(page.getByRole("heading", { name: "Demo clock" })).toHaveCount(0);

  await page.getByRole("button", { name: "Turn demo mode on" }).click();
  await expect(page.getByRole("heading", { name: "Demo clock" })).toBeVisible();
  // Every role's banner reads demo mode on its own refresh; a reload shows it at once.
  await page.reload();
  await expect(page.getByText(/Demo mode · demo clock 16:05/)).toBeVisible();
  expect(commands[0]).toMatchObject({ kind: "demo:Enable", expectedVersion: 0, payload: { reason: "Live demo" } });

  await page.getByRole("button", { name: "After cutoff 16:05" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Clock 16:05: done." })).toBeVisible();
  const clockCommand = commands.find((c) => c.kind === "demo:SetClock")!;
  expect(clockCommand.expectedVersion).toBe(1);
  expect(new Date(String(clockCommand.payload.target)).toISOString()).toBe("2026-10-05T10:35:00.000Z");

  await page.getByRole("button", { name: "Start every released trip" }).click();
  await expect(page.getByText("V001")).toBeVisible();
  await page.getByRole("tab", { name: "Demand exceeds capacity" }).click();
  await expect(page.getByText("85 orders at Peliyagoda")).toBeVisible();

  await page.getByRole("button", { name: "Turn demo mode off" }).click();
  await expect(page.getByText("Off: Waypoint runs on real time")).toBeVisible();
  await page.reload();
  await expect(page.getByText("Off: Waypoint runs on real time")).toBeVisible();
  await expect(page.getByText(/Demo mode · demo clock/)).toHaveCount(0);
  expect(commands.map((c) => c.kind)).toEqual(["demo:Enable", "demo:SetClock", "demo:StartSimulation", "demo:Disable"]);
});

test("a short reason is refused before anything is sent", async ({ page }) => {
  const commands: Array<{ kind: string; expectedVersion: number | null; payload: Record<string, unknown> }> = [];
  await signedInAdmin(page, commands);
  await page.goto("/#demo");
  await page.getByLabel("Reason, kept in the audit log").fill("x");
  await page.getByRole("button", { name: "Turn demo mode on" }).click();
  await expect(page.getByText("Write a reason of at least 3 characters first.")).toBeVisible();
  expect(commands).toHaveLength(0);
});

test("the demo clock steps by the hour and the day, and takes any depot date and time within seven days", async ({ page }) => {
  const commands: Array<{ kind: string; expectedVersion: number | null; payload: Record<string, unknown> }> = [];
  await signedInAdmin(page, commands);
  await page.goto("/#demo");
  await page.getByRole("button", { name: "Turn demo mode on" }).click();
  const panel = page.getByRole("region", { name: "Demo clock" });
  // The mock's clock reads 16:05 on Mon 5 Oct in the depot, an hour ahead of real time.
  await expect(panel.getByTestId("demo-clock-now")).toHaveText("Mon 5 Oct · 16:05");
  await expect(panel).toContainText("1 h ahead of real time");

  const target = () => new Date(String(commands.filter((c) => c.kind === "demo:SetClock").at(-1)!.payload.target)).toISOString();

  await panel.getByRole("button", { name: /^Forward 1 day/ }).click();
  await expect(page.getByRole("status").filter({ hasText: "Clock +1 day: done." })).toBeVisible();
  expect(target()).toBe("2026-10-06T10:35:00.000Z");

  await panel.getByRole("button", { name: /^Back 1 h/ }).click();
  await expect(page.getByRole("status").filter({ hasText: "Clock -1 h: done." })).toBeVisible();
  expect(target()).toBe("2026-10-05T09:35:00.000Z");

  await panel.getByRole("button", { name: "Next day 05:00" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Clock next day 05:00: done." })).toBeVisible();
  expect(target()).toBe("2026-10-05T23:30:00.000Z");

  // Any depot date and time: 07:15 on the 9th is 01:45 UTC.
  await panel.getByLabel("Depot date").fill("2026-10-09");
  await panel.getByLabel("Depot time").fill("07:15");
  await panel.getByRole("button", { name: "Set date and time" }).click();
  await expect(page.getByRole("status").filter({ hasText: "done." }).last()).toBeVisible();
  expect(target()).toBe("2026-10-09T01:45:00.000Z");

  // Beyond seven days of real time (real time is 09:35 UTC on the 5th): refused before sending.
  const sent = commands.length;
  await panel.getByLabel("Depot date").fill("2026-10-20");
  await expect(panel.getByRole("button", { name: "Set date and time" })).toBeDisabled();
  await expect(panel).toContainText("the demo clock stays within 7 days of real time");
  expect(commands.length).toBe(sent);
  await page.screenshot({ path: "test-results/demo-clock.png", fullPage: true });
});
