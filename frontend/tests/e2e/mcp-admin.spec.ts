import { expect, test, type Page } from "@playwright/test";

// Issue #177: the live AI assistants console beside the admin sample console,
// blocking an app as a versioned command with a reason.

const CLIENT = "0198a000-0000-7000-8000-0000000000c1";

async function signedInAdmin(page: Page, commands: unknown[]) {
  let blocked = false;
  await page.route("**/api/**", (route) => {
    const { pathname } = new URL(route.request().url());
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
    if (pathname === "/api/session") {
      return json({ userId: "0198a000-0000-7000-8000-000000000001", displayName: "Admin One", roles: ["admin"], operator: null, scope: [] });
    }
    if (pathname === "/api/mcp/clients") {
      return json([{ clientId: CLIENT, clientName: "Example assistant", registeredAt: "2026-10-03T01:00:00Z", lastUsedAt: "2026-10-03T04:30:00Z",
        activeConnections: blocked ? 0 : 3, blockedAt: blocked ? "2026-10-03T05:00:00Z" : null, blockReason: blocked ? "probing other depots" : null,
        rowVersion: blocked ? 2 : 1 }]);
    }
    if (pathname === "/api/audit/mcp-usage") {
      return json([
        { tool: "list_orders", clientId: CLIENT, calls: 40, ok: 38, denied: 2, rateLimited: 0, notFound: 0, errors: 0, p95Ms: 41.2, attention: false },
        { tool: "list_pending_receipts", clientId: CLIENT, calls: 12, ok: 0, denied: 12, rateLimited: 0, notFound: 0, errors: 0, p95Ms: 9, attention: true },
      ]);
    }
    if (pathname === "/api/commands") {
      const command = route.request().postDataJSON();
      commands.push(command);
      blocked = command.kind === "mcp:BlockClient";
      return json({ commandId: command.commandId, kind: command.kind, replayed: false, result: {} });
    }
    return json({ items: [], nextCursor: null });
  });
}

test("an administrator sees assistant apps and their usage and blocks one with a reason", async ({ page }) => {
  const commands: { kind: string; expectedVersion: number; payload: { clientId: string; reason?: string } }[] = [];
  await signedInAdmin(page, commands);
  await page.goto("/#assistants");

  await expect(page.getByRole("heading", { name: "AI assistants" })).toBeVisible();
  const apps = page.getByRole("region", { name: "Assistant apps" });
  await expect(apps.getByText("Example assistant", { exact: true })).toBeVisible();
  await expect(apps.getByText("3 connected", { exact: false })).toBeVisible();
  await expect(page.getByRole("cell", { name: "Pending receipts" })).toBeVisible();
  await expect(page.getByText("Needs attention", { exact: true })).toBeVisible();
  await expect(page.getByText("list_pending_receipts")).toHaveCount(0);

  await apps.getByRole("button", { name: "Block", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "Block Example assistant" });
  await expect(dialog.getByRole("button", { name: "Block app" })).toBeDisabled();
  await dialog.getByLabel(/Reason/).fill("probing other depots");
  await dialog.getByRole("button", { name: "Block app" }).click();

  await expect(page.getByRole("status")).toContainText("Example assistant is blocked");
  expect(commands).toHaveLength(1);
  expect(commands[0]).toMatchObject({ kind: "mcp:BlockClient", expectedVersion: 1, payload: { clientId: CLIENT, reason: "probing other depots" } });
  await expect(apps.getByText("Blocked", { exact: true })).toBeVisible();

  await apps.getByRole("button", { name: "Unblock" }).click();
  await expect(page.getByRole("status")).toContainText("is unblocked");
  expect(commands[1]).toMatchObject({ kind: "mcp:UnblockClient", expectedVersion: 2 });

  await page.getByRole("link", { name: "People" }).click();
  await expect(page.getByRole("heading", { name: "People & access" })).toBeVisible();
});
