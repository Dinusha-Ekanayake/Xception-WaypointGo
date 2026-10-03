import type { OrderView, ProductView, Temperature } from "@shared/domain/types";

// The rows of "03 Place order": what this outlet usually orders, then what this
// order adds from the catalogue ("03c Add item", "03d Item added"). The
// catalogue is the warehouse's, so a usual item is listed only while it is in
// it; while the catalogue cannot be read the usual items still show, classed by
// the order they came in, so the usual order can be placed with the warehouse down.

export type Usual = { quantity: number; temperature: Temperature };

/** The last quantity this outlet ordered of each product, and the class of the order it came in (R-ORD-06). */
export function usualOf(orders: OrderView[]): Map<string, Usual> {
  const usual = new Map<string, Usual>();
  for (const o of [...orders].sort((a, b) => a.placedAt.localeCompare(b.placedAt))) {
    for (const l of o.lines) usual.set(l.productId, { quantity: l.quantity, temperature: o.temperature });
  }
  return usual;
}

/** A product's class: the catalogue's, else the class of the order it last came in, else ambient. */
export function classOf(productId: string, products: ProductView[], usual: ReadonlyMap<string, Usual>): Temperature {
  return products.find((p) => p.productId === productId)?.temperature ?? usual.get(productId)?.temperature ?? "ambient";
}

/**
 * The rows of one class, in order: the usual items and whatever the order
 * already holds (a restored draft, a change to a placed order) in catalogue
 * order, then what was added here in the order it was added, so a new row lands
 * at the foot of the list. A row stays when its quantity goes back to 0.
 */
export function rowsOf(
  temperature: Temperature,
  products: ProductView[],
  usual: ReadonlyMap<string, Usual>,
  quantities: Readonly<Record<string, number>>,
  added: readonly string[],
): string[] {
  const listed = (id: string) => products.length === 0 || products.some((p) => p.productId === id);
  const ofClass = (id: string) => classOf(id, products, usual) === temperature;
  const candidates = new Set([...products.map((p) => p.productId), ...usual.keys(), ...Object.keys(quantities)]);
  const first = [...candidates].filter(
    (id) => ofClass(id) && !added.includes(id) && ((quantities[id] ?? 0) > 0 || (usual.has(id) && listed(id))),
  );
  return [...first, ...added.filter(ofClass)];
}

export type Match = { product: ProductView; listed: boolean };

/**
 * Catalogue products of this class whose name holds the search, for the picker
 * (03c): names that start with it first, and anything already in the list last,
 * since it needs no adding.
 */
export function matches(query: string, temperature: Temperature, products: ProductView[], rows: readonly string[], limit = 6): Match[] {
  const q = query.trim().toLowerCase();
  if (!q) return [];
  const rank = (m: Match) => (m.listed ? 2 : m.product.productId.toLowerCase().startsWith(q) ? 0 : 1);
  return products
    .filter((p) => (p.temperature ?? "ambient") === temperature && p.productId.toLowerCase().includes(q))
    .map((product) => ({ product, listed: rows.includes(product.productId) }))
    .sort((a, b) => rank(a) - rank(b))
    .slice(0, limit);
}
