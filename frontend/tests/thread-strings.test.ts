import assert from "node:assert/strict";
import { test } from "node:test";
import { translate as driver } from "../src/roles/driver/data/strings.ts";
import { translate as loader } from "../src/roles/loader/data/strings.ts";
import { translate as store } from "../src/roles/store/data/strings.ts";

// FF-00, FF-05: the trip thread reads in the phone's language for every role:
// the voice note, its machine transcript, listening to a typed message and the
// report types. Kept once in shared/messaging/threadStrings.ts.

const SEEN_ON_A_PHONE = [
  "Voice note", "Play", "Pause", "Listen", "Stop reading", "Machine transcript, check audio", "Writing down the voice note…",
  "Sinhala", "Tamil", "English", "Road disruption", "Vehicle fault", "Running late", "Play {n} new messages",
];

test("the thread's words are in Sinhala and Tamil for the driver, the loader and the store", () => {
  for (const [role, t] of [["driver", driver], ["loader", loader], ["store", store]] as const) {
    for (const key of SEEN_ON_A_PHONE) {
      assert.notEqual(t("si", key), key, `${role} has Sinhala for "${key}"`);
      assert.notEqual(t("ta", key), key, `${role} has Tamil for "${key}"`);
      assert.equal(t("en", key), key);
    }
  }
});
