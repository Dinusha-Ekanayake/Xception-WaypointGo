import { productNameOf } from "./productNames.ts";

// Counts in the glossary's words (docs/architecture/GLOSSARY.md, "Quantities").
// The order quantity is units: the data has no case or package.

/** "1 unit", "12 units". */
export const units = (n: number): string => `${n} ${n === 1 ? "unit" : "units"}`;

/** "1 product line", "3 product lines". */
export const productLines = (n: number): string => `${n} ${n === 1 ? "product line" : "product lines"}`;

/** "1 order", "4 orders". */
export const orders = (n: number): string => `${n} ${n === 1 ? "order" : "orders"}`;

/** "1 stop", "8 stops". */
export const stops = (n: number): string => `${n} ${n === 1 ? "stop" : "stops"}`;

/** "1 trip", "2 trips". */
export const trips = (n: number): string => `${n} ${n === 1 ? "trip" : "trips"}`;

/**
 * A product line as people read it. The delivery-day seed books each order as
 * one line named `SEED-<brand>-<temperature>` (it has totals, not products), so
 * that line reads as the mixed goods it stands for rather than as a code.
 */
export function productLabel(productId: string): string {
  // The warehouse's name when it gives one (docs/issues/product-names/PLAN.md), still a reconstruction.
  // No comma or full stop: a store's note lists products as "Missing: A x1, B x2." (noteOf).
  const named = productNameOf(productId);
  if (named) return `${named.replace(/,|\.(?=\s|$)/g, " ").replace(/\s+/g, " ").trim()} (inferred)`;
  const seed = /^SEED-[A-Za-z]+-(ambient|chilled)$/.exec(productId);
  if (seed) return seed[1] === "chilled" ? "Chilled goods (mixed)" : "Dry goods (mixed)";
  const candidate = CANDIDATE.exec(productId);
  if (candidate) {
    const [, brand, number, kind] = candidate;
    const goods = kind ? GOODS[kind] : undefined;
    // No comma or full stop: a store's note lists products as "Missing: A x1, B x2." (noteOf).
    return `${brand!.charAt(0)}${brand!.slice(1).toLowerCase()} ${goods ?? "item"} ${number} (inferred)`;
  }
  return productId;
}

/**
 * A product id the warehouse catalogue reconstructed from order totals, such as
 * `ADAPT_FRESH_263_AMB`: brand, a number and the temperature. It has no name, so
 * it reads as "Fresh dry goods 263 (inferred)", never as a real SKU (catalogue rule 3).
 */
const CANDIDATE = /^ADAPT_([A-Z]+)_(\d+)(?:_([A-Z]+))?$/;
const CANDIDATES = /\bADAPT_[A-Z]+_\d+(?:_[A-Z]+)?\b/g;
const GOODS: Record<string, string> = { AMB: "dry goods", CHL: "chilled goods", CHI: "chilled goods", FRZ: "frozen goods" };

const LOADING_KIND: Record<string, string> = { SHORT: "Short", MISSING: "Missing", DAMAGED: "Damaged", DOES_NOT_FIT: "Doesn't fit" };

/**
 * An issue's stored description as people read it. The Issues module writes
 * "SHORT at loading: 1 units. ..." and "shortage investigation: ...", which the
 * store also parses, so the stored words stay and only the screen reads them
 * as sentences, with counts and seeded product lines in plain words.
 */
export function issueText(description: string): string {
  return description
    .replace(/^([A-Z_]+) at loading: (\d+) units?\./, (_, kind: string, n: string) => `${LOADING_KIND[kind] ?? kind} at loading: ${units(Number(n))}.`)
    .replace(/^shortage investigation: /, "Shortage to check: ")
    .replace(/SEED-[A-Za-z]+-(?:ambient|chilled)/g, (id) => productLabel(id))
    .replace(CANDIDATES, (id) => productLabel(id));
}

/** The id the server acts under for its own jobs and consumers. */
export const SYSTEM_ACTOR_ID = "00000000-0000-0000-0000-000000000001";
