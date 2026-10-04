import assert from "node:assert/strict";
import test from "node:test";
import { readKept, setStateScope, storageKey, writeKept } from "../src/shared/ui/usePersistentState.ts";

// UX polish 2: a screen's tab, filter and search come back when the person
// returns; another account on the same browser never sees them; storage that
// is blocked only means starting from the default.

function memory(): Storage {
  const map = new Map<string, string>();
  return {
    getItem: (k) => map.get(k) ?? null,
    setItem: (k, v) => void map.set(k, v),
    removeItem: (k) => void map.delete(k),
    clear: () => map.clear(),
    key: () => null,
    get length() {
      return map.size;
    },
  };
}

test("a kept choice comes back for the same account", () => {
  const store = memory();
  setStateScope("user-a");
  writeKept("dispatcher:orders:tab", "deferred", store);
  assert.equal(readKept("dispatcher:orders:tab", "current", store), "deferred");
  assert.deepEqual(readKept("missing", { brands: [] }, store), { brands: [] });
});

test("another account starts from the defaults", () => {
  const store = memory();
  setStateScope("user-a");
  writeKept("loader:board:filter", "mine", store);
  setStateScope("user-b");
  assert.equal(readKept("loader:board:filter", "all", store), "all");
  assert.notEqual(storageKey("k", "user-a"), storageKey("k", "user-b"));
  setStateScope(null);
});

test("blocked or broken storage falls back to the default without throwing", () => {
  const blocked = {
    getItem: () => {
      throw new Error("SecurityError");
    },
    setItem: () => {
      throw new Error("QuotaExceededError");
    },
  };
  assert.doesNotThrow(() => writeKept("x", 1, blocked));
  assert.equal(readKept("x", 7, blocked), 7);
  const garbled = { getItem: () => "{not json" };
  assert.equal(readKept("x", 7, garbled), 7);
});
