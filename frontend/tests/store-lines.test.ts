import assert from "node:assert/strict";
import { test } from "node:test";
import type { OrderView } from "../src/shared/domain/ordering.ts";
import type { ProductView } from "../src/shared/domain/warehouse.ts";
import { classOf, matches, rowsOf, usualOf } from "../src/roles/store/data/lines.ts";

// The rows of the store's order list (Figma "03 Place order", "03c Add item", "03d Item added").

const product = (productId: string, temperature: ProductView["temperature"]): ProductView => ({
  productId, brandCode: "FRESH", unitWeightKg: "1.00", unitVolumeM3: "0.0013", temperature, verifiedRealSku: false, basis: "reconstructed",
});

const PRODUCTS = [
  product("Basmati rice 5 kg", "ambient"),
  product("Chickpeas 1 kg", "ambient"),
  product("Chilli powder 250 g", "ambient"),
  product("Coconut oil 1 L", "ambient"),
  product("Fresh milk 1 L", "chilled"),
];

const order = (temperature: OrderView["temperature"], placedAt: string, lines: [string, number][]) =>
  ({ temperature, placedAt, lines: lines.map(([productId, quantity]) => ({ productId, quantity })) }) as OrderView;

const ORDERS = [
  order("ambient", "2026-09-20T06:00:00Z", [["Basmati rice 5 kg", 10], ["Coconut oil 1 L", 8]]),
  order("ambient", "2026-09-27T06:00:00Z", [["Basmati rice 5 kg", 12]]),
  order("chilled", "2026-09-27T06:00:00Z", [["Fresh milk 1 L", 6], ["Butter 200 g", 2]]),
];

test("the usual is the last quantity ordered, with the class of its order", () => {
  const usual = usualOf(ORDERS);
  assert.deepEqual(usual.get("Basmati rice 5 kg"), { quantity: 12, temperature: "ambient" });
  assert.equal(classOf("Butter 200 g", PRODUCTS, usual), "chilled", "not in the catalogue, so classed by its order");
  assert.equal(classOf("Chickpeas 1 kg", PRODUCTS, usual), "ambient");
});

test("the list is the usual items in catalogue order, then what was added, at the foot", () => {
  const usual = usualOf(ORDERS);
  assert.deepEqual(rowsOf("ambient", PRODUCTS, usual, {}, []), ["Basmati rice 5 kg", "Coconut oil 1 L"]);
  assert.deepEqual(rowsOf("ambient", PRODUCTS, usual, { "Chickpeas 1 kg": 0 }, ["Chickpeas 1 kg"]), ["Basmati rice 5 kg", "Coconut oil 1 L", "Chickpeas 1 kg"]);
  assert.deepEqual(rowsOf("ambient", PRODUCTS, usual, { "Chilli powder 250 g": 3 }, []), ["Basmati rice 5 kg", "Chilli powder 250 g", "Coconut oil 1 L"], "a restored draft keeps its line");
});

test("a usual item the catalogue no longer has drops out, unless the catalogue cannot be read", () => {
  const usual = usualOf(ORDERS);
  assert.deepEqual(rowsOf("chilled", PRODUCTS, usual, {}, []), ["Fresh milk 1 L"]);
  assert.deepEqual(rowsOf("chilled", [], usual, {}, []), ["Fresh milk 1 L", "Butter 200 g"]);
});

test("the picker finds the class's products by name, names that start with the search first, listed ones last", () => {
  const found = matches("chi", "ambient", PRODUCTS, ["Chilli powder 250 g"]);
  assert.deepEqual(found.map((m) => [m.product.productId, m.listed]), [["Chickpeas 1 kg", false], ["Chilli powder 250 g", true]]);
  assert.deepEqual(matches("milk", "ambient", PRODUCTS, []), [], "a chilled product is not offered on the ambient order");
  assert.deepEqual(matches("  ", "ambient", PRODUCTS, []), []);
});
