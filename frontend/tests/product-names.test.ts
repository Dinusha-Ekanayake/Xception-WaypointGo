import assert from "node:assert/strict";
import { test } from "node:test";
import { productLabel } from "../src/shared/wording/terms.ts";
import { rememberNames, takeWantedNames } from "../src/shared/wording/productNames.ts";
import { matches } from "../src/roles/store/data/lines.ts";
import type { ProductView } from "../src/shared/domain/warehouse.ts";

// docs/issues/product-names/PLAN.md: the warehouse's names, still labelled inferred.

test("a product the warehouse named reads by its name, still inferred; one it did not keeps today's label", () => {
  assert.equal(productLabel("ADAPT_FRESH_263_AMB"), "Fresh dry goods 263 (inferred)");
  assert.deepEqual(takeWantedNames(), ["ADAPT_FRESH_263_AMB"], "an unknown product is asked for once");
  rememberNames(["C32_FRESH_019_CHL", "ADAPT_FRESH_263_AMB"], { C32_FRESH_019_CHL: "Prawns 500g x 13" });
  assert.equal(productLabel("C32_FRESH_019_CHL"), "Prawns 500g x 13 (inferred)");
  assert.equal(productLabel("ADAPT_FRESH_263_AMB"), "Fresh dry goods 263 (inferred)");
  assert.deepEqual(takeWantedNames(), [], "a product with no name is not asked again");
});

test("a name keeps its decimal point but loses the comma and full stop a store's note splits on", () => {
  rememberNames(["P-JUICE"], { "P-JUICE": "Juice 1.5L, orange." });
  assert.equal(productLabel("P-JUICE"), "Juice 1.5L orange (inferred)");
});

test("the store's picker finds a product by its name or its id", () => {
  const product = (productId: string, name: string | null): ProductView => ({
    productId, name, brandCode: "Fresh", unitWeightKg: "1", unitVolumeM3: "0.01", temperature: "chilled", verifiedRealSku: false, basis: "cluster_center",
  });
  const products = [product("C32_FRESH_019_CHL", "Prawns 500g x 13"), product("C32_FRESH_020_CHL", null)];
  assert.deepEqual(matches("prawns", "chilled", products, []).map((m) => m.product.productId), ["C32_FRESH_019_CHL"]);
  assert.deepEqual(matches("020", "chilled", products, []).map((m) => m.product.productId), ["C32_FRESH_020_CHL"]);
});
