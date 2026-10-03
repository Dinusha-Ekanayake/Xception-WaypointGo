import { expect, test } from "@playwright/test";
import { board, manifest, SESSION } from "./mocks.ts";

test("an offline item check syncs once under the recorded operator", async ({ page, context }) => {
  let checkedOnServer = false;
  let syncBatch: { operations: Array<{ command: { commandId: string; actingUserId?: string; kind: string; payload: { lineNo: number | null } } }> } | null = null;
  const json = (body: unknown) => ({ status: 200, contentType: "application/json", body: JSON.stringify(body) });

  await page.route("**/api/**", async (route) => {
    const { pathname } = new URL(route.request().url());
    const current = manifest("trip-offline", checkedOnServer, checkedOnServer ? 5 : 4);
    if (pathname === "/api/session") return route.fulfill(json(SESSION));
    if (pathname === "/api/loading/trips") return route.fulfill(json(board(current)));
    if (pathname === "/api/reference/outlets") return route.fulfill(json([]));
    if (pathname === "/api/loading/trips/trip-offline/manifest") return route.fulfill(json(current));
    if (pathname === "/api/sync" && route.request().method() === "POST") {
      syncBatch = route.request().postDataJSON();
      checkedOnServer = true;
      return route.fulfill(json({ results: syncBatch!.operations.map((operation) => ({
        operationId: operation.command.commandId,
        sequence: 1, status: "APPLIED", problemCode: null, detail: null, replayed: false,
      })) }));
    }
    return route.fulfill({ status: 404, body: "not mocked" });
  });

  await page.goto("/");
  await page.getByRole("button", { name: "Continue" }).click();
  const markLoaded = page.getByRole("button", { name: /Mark item 1 of ORD0092336 loaded/ });
  await expect(markLoaded).toBeVisible();
  await context.setOffline(true);
  await markLoaded.click();
  await expect(page.getByRole("status").filter({ hasText: "1 saved on this device" }).first()).toBeVisible();
  expect(syncBatch).toBeNull();

  await context.setOffline(false);
  await expect.poll(() => syncBatch?.operations.length).toBe(1);
  await expect(page.getByRole("status").filter({ hasText: "1 saved on this device" })).toHaveCount(0);
  expect(syncBatch!.operations[0]!.command).toMatchObject({
    kind: "loading:Check",
    actingUserId: "loader-user",
    payload: { lineNo: 1 },
  });
});
