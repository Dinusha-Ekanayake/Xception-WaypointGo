import { expect, test, type Page } from "@playwright/test";
import { issue, order, serve } from "./mocks.ts";

// The dispatcher's global search (issue #usability): Ctrl+K from anywhere,
// grouped results over what the shell already read, Enter to land on the
// right screen already filtered or focused where that screen supports it.

const SEARCH = "Search orders, vehicles, trips, issues and depots";

// The header can draw before its key listener attaches, so a press that lands
// first is lost: press again until the search opens.
async function openSearch(page: Page, key: string) {
  const combobox = page.getByRole("combobox", { name: SEARCH });
  await expect(async () => {
    await page.keyboard.press(key);
    await expect(combobox).toBeVisible({ timeout: 1_000 });
  }).toPass();
  return combobox;
}

test("Ctrl+K opens the search, shows grouped results, and Enter opens the matching issue", async ({ page }) => {
  await serve(page, {
    orders: [order(1, "CONFIRMED")],
    issues: [issue(2, { type: "DAMAGED_GOODS", description: "Crate cracked open" })],
  });
  await page.goto("/#/overview");

  // The shortcut is live once the header has drawn.
  await expect(page.getByRole("button", { name: "Search", exact: true })).toBeVisible();
  const combobox = await openSearch(page, "Control+k");
  await expect(combobox).toBeFocused();

  await combobox.fill("crack");
  const listbox = page.getByRole("listbox", { name: "Search results" });
  await expect(listbox).toBeVisible();
  const issueGroup = page.getByRole("group", { name: "Issues" });
  await expect(issueGroup).toContainText("Damaged goods");
  await expect(issueGroup).toContainText("Crate cracked open");

  await page.keyboard.press("Enter");
  await expect(combobox).toHaveCount(0);
  await expect(page).toHaveURL(/#\/issues$/);
  const detail = page.getByRole("region", { name: "Selected issue" });
  await expect(detail).toContainText("Crate cracked open");
});

test("Escape closes the search with no navigation", async ({ page }) => {
  await serve(page, { orders: [order(1, "CONFIRMED")] });
  await page.goto("/#/overview");

  // The shortcut is live once the header has drawn.
  await expect(page.getByRole("button", { name: "Search", exact: true })).toBeVisible();
  const combobox = await openSearch(page, "Control+k");
  await combobox.fill("ord");
  await expect(page.getByRole("listbox", { name: "Search results" })).toBeVisible();

  await page.keyboard.press("Escape");
  await expect(combobox).toHaveCount(0);
  await expect(page).toHaveURL(/#\/overview$/);
});

test("the slash shortcut opens search from outside a text field, and a touch user can open it from the icon button", async ({ page }) => {
  await serve(page, { orders: [order(1, "CONFIRMED", { orderRef: "ORD-7700" })] });
  await page.goto("/#/orders");

  // Orders has its own search box; "/" there must type a slash, not open global search.
  const ownSearch = page.getByRole("searchbox", { name: "Search orders" });
  await ownSearch.fill("/");
  await expect(page.getByRole("combobox", { name: "Search orders, vehicles, trips, issues and depots" })).toHaveCount(0);
  await ownSearch.fill("");
  await ownSearch.blur();

  const combobox = await openSearch(page, "/");
  await expect(combobox).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(combobox).toHaveCount(0);

  // The icon button opens it too, for a mouse or touch user with no keyboard hint.
  await page.getByRole("button", { name: "Search" }).click();
  await expect(combobox).toBeFocused();
});

test("choosing an order lands on Orders with its ref already in the kept search box", async ({ page }) => {
  await serve(page, { orders: [order(1, "CONFIRMED", { orderRef: "ORD-8800", outletId: "OUT099" })] });
  await page.goto("/#/overview");

  await expect(page.getByRole("button", { name: "Search", exact: true })).toBeVisible();
  const combobox = await openSearch(page, "Control+k");
  await combobox.fill("8800");
  await page.getByRole("option", { name: /ORD-8800/ }).click();

  await expect(page).toHaveURL(/#\/orders$/);
  await expect(page.getByRole("searchbox", { name: "Search orders" })).toHaveValue("ORD-8800");
  await expect(page.getByRole("table", { name: "Orders due" })).toContainText("ORD-8800");
});
