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
  const seed = /^SEED-[A-Za-z]+-(ambient|chilled)$/.exec(productId);
  if (seed) return seed[1] === "chilled" ? "Chilled goods (mixed)" : "Dry goods (mixed)";
  return productId;
}

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
    .replace(/SEED-[A-Za-z]+-(?:ambient|chilled)/g, (id) => productLabel(id));
}

/** The id the server acts under for its own jobs and consumers. */
export const SYSTEM_ACTOR_ID = "00000000-0000-0000-0000-000000000001";
