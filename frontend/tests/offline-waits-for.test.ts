import assert from "node:assert/strict";
import test from "node:test";
import { readyToSend } from "../src/shared/offline/review.ts";
import type { StoredEntry } from "../src/shared/offline/store.ts";

// Issue #136: a queued voice message names its audio; the page's queue sends
// the audio first and holds the message, and everything after it, until then.

const e = (id: string, waitsFor?: string[]): StoredEntry => ({
  commandId: id, kind: "message:Post", payload: {}, enqueuedAt: "2026-10-04T05:00:00Z", attempts: 0, ...(waitsFor ? { waitsFor } : {}),
});

test("writes up to the first one whose upload is still on the device are sent", () => {
  const entries = [e("a"), e("b", ["voice-1"]), e("c")];
  assert.deepEqual(readyToSend(entries, new Set(["voice-1"])).map((x) => x.commandId), ["a"]);
  assert.deepEqual(readyToSend(entries, new Set()).map((x) => x.commandId), ["a", "b", "c"]);
  assert.deepEqual(readyToSend(entries, new Set(["other"])).map((x) => x.commandId), ["a", "b", "c"]);
});
