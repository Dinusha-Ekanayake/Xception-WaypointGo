import assert from "node:assert/strict";
import test from "node:test";
import { base64url, keyBytes } from "../src/shared/notifications/pushKeys.ts";

// Issue #118: the keys a push subscription sends, in the encodings each side reads.

test("a subscription key goes to the backend as unpadded base64url", () => {
  // 65 bytes starting 0x04, as a P-256 public key is, with bytes that need - and _ in base64url.
  const key = new Uint8Array(65).map((_, i) => (i === 0 ? 4 : (i * 37) % 256));
  const text = base64url(key.buffer);
  assert.doesNotMatch(text, /[+/=]/);
  assert.equal(text.length, 87, "65 bytes in base64url without padding");
  assert.deepEqual([...keyBytes(text)], [...key], "and reads back to the same bytes");
});

test("the server's VAPID key decodes to the bytes subscribe() takes", () => {
  const auth = new Uint8Array(16).map((_, i) => 250 - i);
  assert.deepEqual([...keyBytes(base64url(auth.buffer))], [...auth]);
  assert.equal(base64url(null), "");
});
