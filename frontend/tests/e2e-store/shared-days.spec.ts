import { expect, test } from "@playwright/test";
import { mockStore, shift } from "./mocks.ts";

/** A tile's name starts with its day, "Sun 25 Oct". */
const tile = (days: number) =>
  new RegExp(`^${new Intl.DateTimeFormat("en-GB", { weekday: "short", day: "numeric", month: "short", timeZone: "UTC" }).format(new Date(`${shift(days)}T00:00:00Z`))}`);

// R-ORD-17: a day that already carries orders of the store's brand, district and
// temperature shows how many stores are booked, and the day picked says what it
// means: a trip is likely to go there anyway. Advice only, for every brand.

test("a day other stores have booked shows the count and says the order is likely to share the trip", async ({ page }) => {
  const asked: string[] = [];
  page.on("request", (r) => {
    if (r.url().includes("/api/orders/shared-days")) asked.push(new URL(r.url()).searchParams.get("temperature") ?? "");
  });
  await mockStore(page, { sharedDays: [{ date: shift(4), otherStores: 2, ownOrder: false }, { date: shift(6), otherStores: 2, ownOrder: true }, { date: shift(9), otherStores: 4, ownOrder: false }] });
  await page.goto("/");
  await page.getByRole("button", { name: /Place order for/ }).click();
  await expect.poll(() => asked).toContain("ambient");

  const strip = page.getByRole("group", { name: "Delivery day" });
  // A day near the bookings first: only days within two of the one picked are marked.
  await strip.getByRole("button", { name: tile(3) }).click();
  await strip.getByRole("button", { name: /· 2 stores already booked$/ }).click();
  const note = page.getByRole("region", { name: "Likely to share a trip" });
  await expect(note).toContainText("2 other Fresh stores in Kadugannawa already have ambient goods booked for");
  await expect(note).toContainText("This order is likely to travel on the same trip.");
  // Only days within two of the one picked are marked: two days on, yes; five days on, no.
  await expect(strip.getByRole("button", { name: /· 3 stores already booked$/ })).toHaveCount(1);
  await expect(strip.getByRole("button", { name: /· 4 stores already booked$/ })).toHaveCount(0);
  if (process.env.SHOTS_DIR) await page.waitForTimeout(500);
  if (process.env.SHOTS_DIR) await page.screenshot({ path: `${process.env.SHOTS_DIR}/11-store-shared-day.png` });

  // The store's own order that day is counted and named too.
  await strip.getByRole("button", { name: /· 3 stores already booked$/ }).click();
  await expect(note).toContainText("You already have ambient goods booked for");

  // The day between the two booked ones has none: it names the busier neighbour, without moving anything.
  const pressedBefore = strip.getByRole("button", { name: /· 2 stores already booked$/ });
  const next = strip.getByRole("button").nth((await strip.getByRole("button").evaluateAll((els) => els.findIndex((e) => /2 stores already booked/.test(e.getAttribute("aria-label") ?? "")))) + 1);
  await next.click();
  await expect(next).toHaveAttribute("aria-pressed", "true");
  await expect(pressedBefore).toHaveAttribute("aria-pressed", "false");
  await expect(page.getByRole("status").filter({ hasText: "No other store has booked" })).toContainText("already has 3 booked for Kadugannawa");
});

test("the count follows the order's temperature", async ({ page }) => {
  const asked: string[] = [];
  page.on("request", (r) => {
    if (r.url().includes("/api/orders/shared-days")) asked.push(new URL(r.url()).searchParams.get("temperature") ?? "");
  });
  await mockStore(page, { sharedDays: [] });
  await page.goto("/");
  await page.getByRole("button", { name: /Place order for/ }).click();
  await page.getByRole("tab", { name: /Chilled order/ }).or(page.getByRole("button", { name: /Chilled order/ })).first().click();
  await expect.poll(() => asked).toContain("chilled");
});

test("with the counts down the strip still works and says so", async ({ page }) => {
  await mockStore(page, { sharedDays: "down" });
  await page.goto("/");
  await page.getByRole("button", { name: /Place order for/ }).click();
  await expect(page.getByRole("status").filter({ hasText: "Shared trip counts are unavailable right now." })).toBeVisible();
  const strip = page.getByRole("group", { name: "Delivery day" });
  await strip.getByRole("button").nth(3).click();
  await expect(strip.getByRole("button").nth(3)).toHaveAttribute("aria-pressed", "true");
});
