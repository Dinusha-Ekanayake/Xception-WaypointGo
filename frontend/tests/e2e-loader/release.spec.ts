import { expect, test } from "@playwright/test";
import { board, manifest, SESSION } from "./mocks.ts";

test("a chilled trip releases after exactly the three confirmed checks, by holding", async ({ page }) => {
  let releaseCommand: unknown;
  const loaded = manifest("trip-test", true, 4);

  await page.route("**/api/**", async (route) => {
    const { pathname } = new URL(route.request().url());
    const json = (body: unknown) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
    if (pathname === "/api/session") return json(SESSION);
    if (pathname === "/api/loading/trips") return json(board(loaded));
    if (pathname === "/api/reference/outlets") return json([]);
    if (pathname === "/api/loading/trips/trip-test/manifest") return json(loaded);
    if (pathname === "/api/commands" && route.request().method() === "POST") {
      releaseCommand = route.request().postDataJSON();
      return json({ commandId: "receipt-test", kind: "loading:Release", replayed: false, result: { rowVersion: 5 } });
    }
    return route.fulfill({ status: 404, body: "not mocked" });
  });

  await page.goto("/");
  await page.getByRole("button", { name: "Continue" }).click();
  await page.getByRole("button", { name: "Release vehicle" }).click();

  const dialog = page.getByRole("dialog", { name: "Confirm and release" });
  const release = dialog.getByRole("slider", { name: "Swipe to release vehicle" });
  await expect(dialog.getByRole("group", { name: "Release checklist" })).toBeVisible();
  await expect(release).toHaveAttribute("aria-disabled", "true");
  await expect(dialog).not.toContainText(/reefer|4\s*°C|seal number/i);

  await dialog.getByRole("checkbox", { name: "Doors sealed" }).check();
  await dialog.getByRole("checkbox", { name: "Orders secured" }).check();
  await expect(release).toHaveAttribute("aria-disabled", "true");
  await dialog.getByRole("checkbox", { name: "Driver present" }).check();
  await expect(release).toHaveAttribute("aria-disabled", "false");

  // A tap is not enough: the release is a deliberate swipe across the track.
  await release.click();
  expect(releaseCommand).toBeUndefined();
  const box = (await release.boundingBox())!;
  await page.mouse.move(box.x + 30, box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width - 10, box.y + box.height / 2, { steps: 12 });
  await page.mouse.up();

  await expect(page.getByRole("heading", { name: "VEH043 released" })).toBeVisible();
  expect(releaseCommand).toMatchObject({
    kind: "loading:Release",
    actingUserId: "loader-user",
    expectedVersion: 4,
    payload: { tripId: "trip-test", doorsSealed: true, ordersSecured: true, driverPresent: true },
  });
});
