import { expect, test } from "@playwright/test";
import { mockStore } from "./mocks.ts";

// Figma "Overlay · Account menu": who is signed in, for which outlet, dock and
// window, and Sign out. Staff ID and the sign-in time are left out by decision.

test("the account menu says who is signed in and where, and closes with Escape", async ({ page }) => {
  await mockStore(page);
  await page.goto("/");
  const trigger = page.getByRole("button", { name: "Account: Nuwan Perera" });
  await trigger.click();

  const menu = page.getByRole("dialog", { name: "Account" });
  await expect(menu).toContainText("Nuwan Perera");
  for (const [label, value] of [
    ["Role", "Store manager"],
    ["Brand", "FRESH"],
    ["Outlet", "Kadugannawa · OUT085"],
    ["Receiving", "Rear dock · 05:00-07:30"],
    ["Depot", "KDY"],
  ]) {
    await expect(menu.getByRole("definition").filter({ hasText: value! })).toBeVisible();
    await expect(menu.getByRole("term").filter({ hasText: label! })).toBeVisible();
  }
  await expect(menu).not.toContainText("Staff ID");
  await expect(menu).not.toContainText("Signed in");

  await page.keyboard.press("Escape");
  await expect(menu).toBeHidden();
  await expect(trigger).toBeFocused();
});

test("Sign out in the account menu signs the manager out", async ({ page }) => {
  await mockStore(page);
  await page.goto("/");
  await page.getByRole("button", { name: "Account: Nuwan Perera" }).click();
  await page.getByRole("dialog", { name: "Account" }).getByRole("button", { name: "Sign out" }).click();
  await expect(page.getByText("You are signed out.")).toBeVisible();
});

test("the manager changes their own name and phone from the account menu", async ({ page }) => {
  const { sent } = await mockStore(page);
  await page.goto("/");
  await page.getByRole("button", { name: "Account: Nuwan Perera" }).click();
  await page.getByRole("dialog", { name: "Account" }).getByRole("button", { name: /Your profile/ }).click();

  const dialog = page.getByRole("dialog", { name: "Your profile" });
  await expect(dialog.getByLabel("Name")).toHaveValue("Nuwan Perera");
  await expect(dialog).toContainText("You sign in as nuwan@outlet085.test");
  await dialog.getByLabel("Name").fill("Nuwan K. Perera");
  await dialog.getByLabel("Phone number").fill("+94 77 123 4567");
  await dialog.getByRole("button", { name: "Save" }).click();

  await expect(page.getByRole("status").filter({ hasText: "Profile saved" })).toBeVisible();
  expect(sent.find((c) => c.kind === "iam:UpdateOwnProfile")).toEqual({
    kind: "iam:UpdateOwnProfile",
    expectedVersion: 4,
    payload: { displayName: "Nuwan K. Perera", phone: "+94 77 123 4567" },
  });
  await expect(page.getByRole("button", { name: "Account: Nuwan K. Perera" })).toBeVisible();
});

test("the manager changes the store's window, dock and contacts, which the next plan uses", async ({ page }) => {
  const { sent } = await mockStore(page);
  await page.goto("/");
  await page.getByRole("button", { name: "Account: Nuwan Perera" }).click();
  await page.getByRole("dialog", { name: "Account" }).getByRole("button", { name: /Store details/ }).click();

  const dialog = page.getByRole("dialog", { name: "Store details" });
  await expect(dialog.getByLabel("Opens at")).toHaveValue("05:00");
  await expect(dialog.getByLabel("Closes at")).toHaveValue("07:30");
  await dialog.getByLabel("Opens at").fill("05:30");
  await dialog.getByLabel("Closes at").fill("08:00");
  await dialog.getByLabel("Where goods are received").selectOption("street");
  await dialog.getByLabel("Contact person").fill("Kamal");
  await dialog.getByLabel("Store phone").fill("081 234 5678");
  await dialog.getByLabel("Notes for the driver (optional)").fill("Ring the bell at the side gate");
  await expect(dialog).toContainText("A new window or dock is used from the next plan.");
  await dialog.getByRole("button", { name: "Save" }).click();

  await expect(page.getByRole("status").filter({ hasText: "Store details saved" })).toBeVisible();
  expect(sent.find((c) => c.kind === "reference:UpdateOutletDetails")).toEqual({
    kind: "reference:UpdateOutletDetails",
    expectedVersion: 0,
    payload: {
      outletId: "OUT085",
      windowOpen: "05:30",
      windowClose: "08:00",
      dockType: "street",
      contactName: "Kamal",
      contactPhone: "081 234 5678",
      receivingNotes: "Ring the bell at the side gate",
    },
  });
});

test("a window that closes before it opens is not sent", async ({ page }) => {
  const { sent } = await mockStore(page);
  await page.goto("/");
  await page.getByRole("button", { name: "Account: Nuwan Perera" }).click();
  await page.getByRole("dialog", { name: "Account" }).getByRole("button", { name: /Store details/ }).click();
  const dialog = page.getByRole("dialog", { name: "Store details" });
  await dialog.getByLabel("Opens at").fill("09:00");
  await dialog.getByLabel("Closes at").fill("08:00");
  await dialog.getByRole("button", { name: "Save" }).click();
  await expect(dialog).toContainText("The window has to open before it closes.");
  expect(sent.filter((c) => c.kind === "reference:UpdateOutletDetails")).toHaveLength(0);
});
