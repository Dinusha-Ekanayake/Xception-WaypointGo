import { expect, test } from "@playwright/test";
import { serve } from "./mocks.ts";

// Roadmap FF-06b: the vehicle's official National Fuel Pass on the phone. It is
// kept from the morning read, so it shows at a pump with no signal; with none
// added, the popup says so instead of drawing a code the pump would refuse.

// A 1x1 PNG: the image the depot uploaded.
const PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

test("with no pass added the popup says to ask the depot, and shows no code", async ({ page }) => {
  await serve(page);
  await page.goto("/");
  await page.getByRole("button", { name: "Fuel QR" }).click();
  const dialog = page.getByRole("dialog", { name: "Fuel QR" });
  await expect(dialog.getByText("No Fuel Pass has been added for this vehicle yet. Ask the depot.")).toBeVisible();
  await expect(dialog.getByRole("img")).toHaveCount(0);
});

test("the pass read with a signal shows at the pump with none", async ({ page, context }) => {
  const server = await serve(page);
  server.fuelPass = PNG;
  await page.goto("/");
  await expect(page.getByRole("button", { name: "Fuel QR" })).toBeVisible();
  // Read and kept on the phone as soon as the run loads with a signal.
  await expect.poll(() => server.calls.filter((c) => c === "GET /api/execution/vehicles/VEH043/fuel-pass").length).toBeGreaterThan(0);

  await server.goOffline(context);
  await page.getByRole("button", { name: "Fuel QR" }).click();
  const dialog = page.getByRole("dialog", { name: "Fuel QR" });
  await expect(dialog.getByRole("img", { name: "Fuel pass code for VEH043" })).toBeVisible();
  await expect(dialog.getByText(/Saved on this phone/)).toBeVisible();
});

test("a withdrawn pass is removed from the phone and not shown again", async ({ page, context }) => {
  const server = await serve(page);
  server.fuelPass = PNG;
  await page.goto("/");
  await expect.poll(() => server.calls.filter((c) => c.endsWith("/fuel-pass")).length).toBeGreaterThan(0);

  server.fuelPass = null;
  await page.getByRole("button", { name: "Fuel QR" }).click();
  await expect(page.getByText("No Fuel Pass has been added for this vehicle yet. Ask the depot.")).toBeVisible();
  await page.keyboard.press("Escape");

  await server.goOffline(context);
  await page.getByRole("button", { name: "Fuel QR" }).click();
  await expect(page.getByText(/No signal, and the Fuel Pass is not saved on this phone/)).toBeVisible();
});

test("signing out deletes the Fuel Pass and every saved read from the phone", async ({ page }) => {
  const server = await serve(page);
  server.fuelPass = PNG;
  await page.goto("/");
  await expect.poll(() => server.calls.filter((c) => c.endsWith("/fuel-pass")).length).toBeGreaterThan(0);
  const savedKeys = () =>
    page.evaluate(
      () =>
        new Promise<string[]>((resolve) => {
          const open = indexedDB.open("waypoint-driver-user");
          open.onsuccess = () => {
            const db = open.result;
            if (!db.objectStoreNames.contains("snapshots")) return resolve([]);
            const keys = db.transaction("snapshots").objectStore("snapshots").getAllKeys();
            keys.onsuccess = () => resolve((keys.result as string[]).filter((k) => !k.startsWith("waypoint.")).sort());
          };
        }),
    );
  await expect.poll(savedKeys).toContain("credential:fuel-pass:VEH043");
  expect((await savedKeys()).some((k) => k.startsWith("driver-run:"))).toBe(true);

  await page.getByRole("button", { name: /Settings:/ }).click();
  await page.getByRole("dialog", { name: "Settings" }).getByRole("button", { name: "Sign out" }).click();
  await page.getByRole("dialog", { name: "Sign out" }).getByRole("button", { name: "Sign out" }).click();
  await expect.poll(savedKeys).toEqual([]);
});
