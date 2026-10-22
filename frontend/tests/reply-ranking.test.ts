import assert from "node:assert/strict";
import { test } from "node:test";
import { rankReplies, type Reply } from "../src/shared/messaging/templates.ts";
import { rankingNote } from "../src/roles/dispatcher/data/replyRanking.ts";

// Roadmap AI-J5: the ranker orders the replies the rules offer, never chooses them.

const reply = (id: string): Reply => ({ id, version: 1, label: id, to: "driver", outletId: null, body: id });
const list = [reply("driver.a"), reply("driver.b"), reply("driver.new"), reply("driver.c")];
const ids = (rs: Reply[]) => rs.map((r) => r.id);

test("untrained, the rule order stays", () => {
  assert.deepEqual(ids(rankReplies(list, { trained: false, scores: { "driver.c": 9 } }, 0.5)), ids(list));
  assert.deepEqual(ids(rankReplies(list, null, 0.5)), ids(list));
});

test("trained, the known replies reorder among their places and a new one keeps its place", () => {
  const out = rankReplies(list, { trained: true, scores: { "driver.a": 0.1, "driver.b": 0.5, "driver.c": 2 } }, 0.5);
  assert.deepEqual(ids(out), ["driver.c", "driver.b", "driver.new", "driver.a"]);
  assert.equal(out.some((r) => r.explored), false);
});

test("one time in ten the second and third swap, and the list says so", () => {
  const out = rankReplies(list, null, 0.05);
  assert.deepEqual(ids(out), ["driver.a", "driver.new", "driver.b", "driver.c"]);
  assert.equal(out.every((r) => r.explored === true), true);
  assert.deepEqual(ids(rankReplies(list.slice(0, 2), null, 0.05)), ["driver.a", "driver.b"], "two replies cannot swap 2 and 3");
});

test("the screen says which order is in charge", () => {
  const base = { unavailable: false, minPosts: 200, topAcceptance: 0, scores: {}, placeEffect: [], from: "", to: "" };
  assert.equal(rankingNote({ ...base, trained: true, posts: 340 }, false), "Order learned from 340 replies");
  assert.equal(rankingNote({ ...base, trained: false, posts: 12 }, false), undefined, "the usual order needs no note");
  assert.equal(rankingNote(null, true), "Usual order · learning unavailable");
});
