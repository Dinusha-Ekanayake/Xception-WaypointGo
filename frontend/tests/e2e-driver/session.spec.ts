import { expect, test } from "@playwright/test";
import { serve } from "./mocks.ts";

test("sign-out is refused while work is still only on the phone", async ({ page, context }) => {
  const server = await serve(page);
  await page.goto("/");
  await expect(page.getByRole("button", { name: "Start run" })).toBeVisible();
  await server.goOffline(context);
  await page.getByRole("button", { name: "Start run" }).click();
  await page.getByRole("button", { name: "Back" }).click();

  await page.getByRole("button", { name: "Sign out" }).click();
  const sheet = page.getByRole("dialog", { name: "Sign out" });
  await expect(sheet).toContainText("1 record is still only on this phone");
  await expect(sheet.getByRole("button", { name: "Sign out" })).toHaveCount(0);
  await sheet.getByRole("button", { name: "Stay signed in" }).click();
  await expect(sheet).toHaveCount(0);

  await server.goOnline(context);
  await expect.poll(() => server.commands.length).toBe(1);
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page.getByRole("dialog", { name: "Sign out" }).getByRole("button", { name: "Sign out" })).toBeVisible();
});

test("a session that ended while offline keeps the work and sends it after signing in again", async ({ page, context }) => {
  const server = await serve(page);
  await page.goto("/");
  await expect(page.getByRole("button", { name: "Start run" })).toBeVisible();
  await server.goOffline(context);
  await page.getByRole("button", { name: "Start run" }).click();
  await page.getByRole("button", { name: "I've arrived" }).click();
  await expect(page.getByText("Stop 01 of 02 · Delivery report")).toBeVisible();

  server.expired = true;
  await server.goOnline(context);
  await expect(page.getByText("You have been signed out")).toBeVisible();
  expect(server.commands).toHaveLength(0);

  // Signing in again as the same person finds the work where it was left.
  server.expired = false;
  await page.getByRole("button", { name: "Sign in again" }).click();
  await expect.poll(() => server.commands.map((c) => c.kind)).toEqual(["delivery:Start", "delivery:RecordArrival"]);
  await expect(page.getByRole("button", { name: /OUT0101/ })).toContainText("At the stop");
});

test("a stop replanned while the phone was offline is held for the driver, never merged", async ({ page, context }) => {
  const server = await serve(page);
  await page.goto("/");
  await expect(page.getByRole("button", { name: "Start run" })).toBeVisible();
  await server.goOffline(context);
  await page.getByRole("button", { name: "Start run" }).click();
  await page.getByRole("button", { name: "I've arrived" }).click();
  await expect(page.getByText("Stop 01 of 02 · Delivery report")).toBeVisible();

  // Meanwhile dispatch replans: the stop leaves the run and its version moves on.
  Object.assign(server.stops[0]!, { outcome: "SKIPPED", rowVersion: 2 });
  await server.goOnline(context);

  await expect(page.getByRole("button", { name: "2 to review" })).toBeVisible();
  expect(server.commands).toHaveLength(0);
  // The screen shows what the server holds, not the arrival it refused.
  await expect(page.getByText("Dispatch replanned this stop")).toBeVisible();
  await page.getByRole("button", { name: "Back" }).click();
  const first = page.getByRole("button", { name: /OUT0101/ });
  await expect(first).toContainText("Replanned");
  await expect(first).not.toContainText("on phone");

  await page.getByRole("button", { name: "2 to review" }).click();
  await expect(page.getByRole("dialog", { name: "Changes to review" })).toContainText("This stop changed on another device or in the plan");
});
