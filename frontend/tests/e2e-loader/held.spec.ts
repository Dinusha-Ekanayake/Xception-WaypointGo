import { expect, test, type BrowserContext, type Page } from "@playwright/test";
import { board, manifest, SESSION } from "./mocks.ts";

// Issue #28: a loader check saved offline meets a trip that moved on. The
// server holds it as a conflict, never merges it, and the loader's owner
// either redoes it on the current version (sync:Resolve) or drops it with a
// reason (sync:Discard). Only the owner, on this device (decision D-O).

type Sent = { commandId: string; kind: string; expectedVersion: number | null; actingUserId?: string; payload: Record<string, unknown> };

async function holdOneCheck(page: Page, context: BrowserContext): Promise<{ batches: Sent[][] }> {
  const batches: Sent[][] = [];
  // The trip moved on to version 7 on the server while the device was offline at 4.
  let serverVersion = 4;
  const json = (body: unknown) => ({ status: 200, contentType: "application/json", body: JSON.stringify(body) });

  await page.route("**/api/**", async (route) => {
    const { pathname } = new URL(route.request().url());
    const current = manifest("trip-held", false, serverVersion);
    if (pathname === "/api/session") return route.fulfill(json(SESSION));
    if (pathname === "/api/loading/trips") return route.fulfill(json(board(current)));
    if (pathname === "/api/reference/outlets") return route.fulfill(json([]));
    if (pathname === "/api/loading/trips/trip-held/manifest") return route.fulfill(json(current));
    if (pathname === "/api/sync" && route.request().method() === "POST") {
      const body = route.request().postDataJSON() as { operations: Array<{ sequence: number; command: Sent }> };
      const sent = body.operations.map((o) => o.command);
      batches.push(sent);
      return route.fulfill(json({
        results: sent.map((c, i) => {
          // The first time, the check meets the newer trip and is held.
          const held = batches.length === 1 && c.kind === "loading:Check";
          return {
            operationId: c.commandId, sequence: i + 1,
            status: held ? "CONFLICT" : "APPLIED",
            problemCode: held ? "VERSION_CONFLICT" : null,
            detail: held ? "Row version did not match; the record changed" : null,
            replayed: false, rowVersion: 2,
          };
        }),
      }));
    }
    return route.fulfill({ status: 404, body: "not mocked" });
  });

  await page.goto("/");
  await page.getByRole("button", { name: "Continue" }).click();
  const markLoaded = page.getByRole("button", { name: /Mark item 1 of ORD0092336 loaded/ });
  await expect(markLoaded).toBeVisible();
  await context.setOffline(true);
  await markLoaded.click();
  serverVersion = 7;
  await context.setOffline(false);

  await expect(page.getByRole("button", { name: "1 to review" }).first()).toBeVisible();
  expect(batches).toHaveLength(1);
  return { batches };
}

test("a held check is redone on the trip's current version, then the held one is settled as resolved", async ({ page, context }) => {
  const { batches } = await holdOneCheck(page, context);
  const held = batches[0]![0]!;

  await page.getByRole("button", { name: "1 to review" }).first().click();
  const dialog = page.getByRole("dialog", { name: "Changes to review" });
  await expect(dialog).toContainText("Row version did not match");
  await dialog.getByRole("button", { name: "Redo on the current version" }).click();

  await expect.poll(() => batches.length).toBe(2);
  const [redo, resolve] = batches[1]!;
  expect(redo).toMatchObject({ kind: "loading:Check", expectedVersion: 7, actingUserId: "loader-user", payload: held.payload });
  expect(redo!.commandId).not.toBe(held.commandId);
  expect(resolve).toMatchObject({
    kind: "sync:Resolve",
    expectedVersion: 2,
    payload: { operationId: held.commandId, replacedBy: redo!.commandId },
  });
  await expect(dialog).toContainText("Nothing left to review.");
});

test("a held check is dropped with the reason the loader picked", async ({ page, context }) => {
  const { batches } = await holdOneCheck(page, context);
  const held = batches[0]![0]!;

  await page.getByRole("button", { name: "1 to review" }).first().click();
  const dialog = page.getByRole("dialog", { name: "Changes to review" });
  await dialog.getByRole("button", { name: "Discard…" }).click();
  await dialog.getByLabel("Why discard it?").selectOption("Entered by mistake");
  await dialog.getByRole("button", { name: "Discard", exact: true }).click();

  await expect.poll(() => batches.length).toBe(2);
  expect(batches[1]).toEqual([
    expect.objectContaining({
      kind: "sync:Discard",
      expectedVersion: 2,
      payload: { operationId: held.commandId, reason: "Entered by mistake" },
    }),
  ]);
  await expect(dialog).toContainText("Nothing left to review.");
});

test("Background Sync: the service worker's drain message sends what waits, with no other trigger", async ({ page, context }) => {
  let refusing = true;
  let batches = 0;
  const json = (body: unknown, status = 200) => ({ status, contentType: "application/json", body: JSON.stringify(body) });
  await page.route("**/api/**", async (route) => {
    const { pathname } = new URL(route.request().url());
    const current = manifest("trip-bg", false, 4);
    if (pathname === "/api/session") return route.fulfill(json(SESSION));
    if (pathname === "/api/loading/trips") return route.fulfill(json(board(current)));
    if (pathname === "/api/reference/outlets") return route.fulfill(json([]));
    if (pathname === "/api/loading/trips/trip-bg/manifest") return route.fulfill(json(current));
    if (pathname === "/api/sync" && route.request().method() === "POST") {
      // The connection comes back flaky: the first pass fails, so the check waits.
      if (refusing) return route.fulfill(json({ status: 503, title: "Unavailable", code: "DEPENDENCY_UNAVAILABLE" }, 503));
      batches++;
      const body = route.request().postDataJSON() as { operations: Array<{ command: { commandId: string } }> };
      return route.fulfill(json({ results: body.operations.map((o, i) => ({
        operationId: o.command.commandId, sequence: i + 1, status: "APPLIED", problemCode: null, detail: null, replayed: false, rowVersion: 2,
      })) }));
    }
    return route.fulfill({ status: 404, body: "not mocked" });
  });

  await page.goto("/");
  await page.getByRole("button", { name: "Continue" }).click();
  await context.setOffline(true);
  await page.getByRole("button", { name: /Mark item 1 of ORD0092336 loaded/ }).click();
  await context.setOffline(false);
  await expect(page.getByRole("button", { name: /1 to send/ }).first()).toBeVisible();

  refusing = false;
  expect(batches).toBe(0);
  // What the service worker posts on the waypoint-drain tag (scripts/build-sw.mjs).
  await page.evaluate(() => navigator.serviceWorker.dispatchEvent(new MessageEvent("message", { data: { type: "waypoint:drain" } })));
  await expect.poll(() => batches, { timeout: 5_000 }).toBe(1);
});

test("a check held before answers carried a version is still discarded on the server, after looking the version up", async ({ page, context }) => {
  const batches: Sent[][] = [];
  const lookups: string[] = [];
  let serverVersion = 4;
  const json = (body: unknown) => ({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
  await page.route("**/api/**", async (route) => {
    const { pathname } = new URL(route.request().url());
    const current = manifest("trip-old", false, serverVersion);
    if (pathname === "/api/session") return route.fulfill(json(SESSION));
    if (pathname === "/api/loading/trips") return route.fulfill(json(board(current)));
    if (pathname === "/api/reference/outlets") return route.fulfill(json([]));
    if (pathname === "/api/loading/trips/trip-old/manifest") return route.fulfill(json(current));
    if (pathname.startsWith("/api/sync/") && route.request().method() === "GET") {
      const id = pathname.slice("/api/sync/".length);
      lookups.push(id);
      return route.fulfill(json({ operationId: id, deviceId: "d", sequence: 1, kind: "loading:Check", status: "CONFLICT",
        problemCode: "VERSION_CONFLICT", baseRowVersion: 4, currentRowVersion: null, receivedAt: "2026-10-03T01:00:00Z", appliedAt: null, rowVersion: 2 }));
    }
    if (pathname === "/api/sync" && route.request().method() === "POST") {
      const body = route.request().postDataJSON() as { operations: Array<{ command: Sent }> };
      const sent = body.operations.map((o) => o.command);
      batches.push(sent);
      // An answer from before #109: no rowVersion, no problem code.
      return route.fulfill(json({ results: sent.map((c, i) => batches.length === 1
        ? { operationId: c.commandId, sequence: i + 1, status: "CONFLICT", problemCode: null, detail: "The record changed", replayed: false }
        : { operationId: c.commandId, sequence: i + 1, status: "APPLIED", problemCode: null, detail: null, replayed: false, rowVersion: 2 }) }));
    }
    return route.fulfill({ status: 404, body: "not mocked" });
  });

  await page.goto("/");
  await page.getByRole("button", { name: "Continue" }).click();
  await context.setOffline(true);
  await page.getByRole("button", { name: /Mark item 1 of ORD0092336 loaded/ }).click();
  serverVersion = 7;
  await context.setOffline(false);
  await page.getByRole("button", { name: "1 to review" }).first().click();
  const held = batches[0]![0]!;

  const dialog = page.getByRole("dialog", { name: "Changes to review" });
  await dialog.getByRole("button", { name: "Discard…" }).click();
  await dialog.getByRole("button", { name: "Discard", exact: true }).click();
  await expect.poll(() => batches.length).toBe(2);
  expect(lookups).toEqual([held.commandId]);
  expect(batches[1]).toEqual([expect.objectContaining({ kind: "sync:Discard", expectedVersion: 2, payload: { operationId: held.commandId, reason: "No longer needed" } })]);
});
