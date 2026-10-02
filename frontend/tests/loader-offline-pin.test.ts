import assert from "node:assert/strict";
import test from "node:test";
import { pinMatches } from "../src/app-shell/offlinePin.ts";

// The vector identity's OfflineOperatorTest pins on the server side.
const VERIFIER = "pbkdf2-sha256$1000$AQIDBAUGBwgJCgsMDQ4PEA==$q/XmQuQ246Dwn9znhO4Ubr59wowIXIZcj+uxlcc2AwQ=";

test("the device checks a PIN against the server's verifier with WebCrypto", async () => {
  assert.equal(await pinMatches("2468", VERIFIER), true);
  assert.equal(await pinMatches("2469", VERIFIER), false);
});

test("malformed verifiers and PINs never match", async () => {
  assert.equal(await pinMatches("2468", "garbage"), false);
  assert.equal(await pinMatches("2468", "pbkdf2-sha256$0$AA==$AA=="), false);
  assert.equal(await pinMatches("24680", VERIFIER), false);
});
