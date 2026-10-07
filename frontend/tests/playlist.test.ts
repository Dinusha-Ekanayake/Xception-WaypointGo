import assert from "node:assert/strict";
import test from "node:test";
import { unheard } from "../src/shared/messaging/playlist.ts";
import type { MessageView } from "../src/shared/domain/messaging.ts";

const m = (id: string, at: string, over: Partial<MessageView> = {}) =>
  ({ messageId: id, createdAt: at, mine: false, body: id, voiceNoteId: null, ...over }) as MessageView;

test("play the messages not heard yet, oldest first, never my own", () => {
  const list = [m("c", "2027-03-01T02:03:00Z"), m("a", "2027-03-01T02:01:00Z"), m("mine", "2027-03-01T02:02:00Z", { mine: true }),
    m("voice", "2027-03-01T02:04:00Z", { body: "", voiceNoteId: "v1" }), m("empty", "2027-03-01T02:05:00Z", { body: " " })];
  assert.deepEqual(unheard(list, null).map((x) => x.messageId), ["a", "c", "voice"]);
  assert.deepEqual(unheard(list, "2027-03-01T02:01:00Z").map((x) => x.messageId), ["c", "voice"], "heard up to a, so a is not played again");
});
