import assert from "node:assert/strict";
import test from "node:test";
import type { MemberView, MessageView } from "../src/shared/domain/messaging.ts";
import { audienceLabel, byDay, chronological, defaultAddress, parseMention, REPORTS_BY_ROLE, voiceLength } from "../src/shared/messaging/thread.ts";

// Issue #136: the pure half of a trip's thread.

const DISPATCHER_MEMBERS: MemberView[] = [
  { to: "driver", outletId: null, label: "Nimal Perera (D-104)" },
  { to: "loader", outletId: null, label: "Loaders at KDY" },
  { to: "outlet", outletId: "OUT063", label: "OUT063" },
  { to: "outlet", outletId: "OUT071", label: "OUT071" },
  { to: "all", outletId: null, label: "Everyone on the trip" },
];
const STORE_MEMBERS: MemberView[] = [{ to: "dispatch", outletId: null, label: "Dispatcher" }];

function message(id: string, at: string, over: Partial<MessageView> = {}): MessageView {
  return {
    messageId: id,
    threadId: "t1",
    authorName: "Dispatcher",
    authorRole: "dispatcher",
    kind: "message",
    reportType: null,
    audience: "all",
    audienceOutlet: null,
    body: id,
    voiceNoteId: null,
    voiceDurationMs: null,
    createdAt: at,
    mine: false,
    ...over,
  };
}

test("a leading mention picks someone the writer may address and leaves the rest of the text", () => {
  assert.deepEqual(parseMention("@driver please call OUT063", DISPATCHER_MEMBERS), {
    address: { to: "driver", outletId: null },
    body: "please call OUT063",
  });
  assert.deepEqual(parseMention("@out063, running late", DISPATCHER_MEMBERS).address, { to: "outlet", outletId: "OUT063" });
  assert.deepEqual(parseMention("@everyone: road closed", DISPATCHER_MEMBERS).address, { to: "all", outletId: null });
  assert.deepEqual(parseMention("@loaders two crates short", DISPATCHER_MEMBERS).address, { to: "loader", outletId: null });
});

test("a mention of someone the writer may not address is only text (R-MSG-02)", () => {
  assert.deepEqual(parseMention("@OUT999 hello", DISPATCHER_MEMBERS), { address: null, body: "@OUT999 hello" });
  // A store manager writes to the dispatcher alone.
  assert.equal(parseMention("@all hello", STORE_MEMBERS).address, null);
  assert.deepEqual(parseMention("@dispatcher short by 2", STORE_MEMBERS).address, { to: "dispatch", outletId: null });
  assert.equal(parseMention("no mention here", DISPATCHER_MEMBERS).address, null);
});

test("the dispatcher starts with the driver, everyone else with the dispatcher", () => {
  assert.deepEqual(defaultAddress("dispatcher", DISPATCHER_MEMBERS), { to: "driver", outletId: null });
  assert.deepEqual(defaultAddress("store_manager", STORE_MEMBERS), { to: "dispatch", outletId: null });
  assert.deepEqual(defaultAddress("loader", []), { to: "dispatch", outletId: null });
});

test("pages arrive newest first; the thread reads oldest first, each message once", () => {
  const a = message("a", "2026-10-04T03:00:00Z");
  const b = message("b", "2026-10-04T03:05:00Z");
  const c = message("c", "2026-10-04T03:10:00Z");
  assert.deepEqual(chronological([[c, b], [b, a]]).map((m) => m.messageId), ["a", "b", "c"]);
});

test("a thread splits at each depot day, not at the device's midnight", () => {
  // 18:20 UTC on the 3rd is 23:50 in Colombo; 18:40 UTC is 00:10 on the 4th.
  const groups = byDay([message("late", "2026-10-03T18:20:00Z"), message("early", "2026-10-03T18:40:00Z")]);
  assert.deepEqual(groups.map((g) => [g.day, g.messages.map((m) => m.messageId)]), [
    ["2026-10-03", ["late"]],
    ["2026-10-04", ["early"]],
  ]);
});

test("who a message reached reads in words, never a code", () => {
  assert.equal(audienceLabel("outlet", "OUT063"), "To OUT063");
  assert.equal(audienceLabel("all", null), "To everyone on the trip");
  assert.equal(audienceLabel("dispatch", null), "To the dispatcher");
});

test("each role reports what it sees; the dispatcher reports nothing (R-MSG-03)", () => {
  assert.deepEqual(REPORTS_BY_ROLE.dispatcher, []);
  assert.ok(REPORTS_BY_ROLE.loader.includes("loading_shortfall"));
  assert.ok(REPORTS_BY_ROLE.store_manager.includes("stock_discrepancy"));
  assert.ok(REPORTS_BY_ROLE.driver.includes("vehicle_fault"));
});

test("a voice note's length reads as minutes and seconds", () => {
  assert.equal(voiceLength(42_000), "0:42");
  assert.equal(voiceLength(120_000), "2:00");
  assert.equal(voiceLength(null), "");
});
