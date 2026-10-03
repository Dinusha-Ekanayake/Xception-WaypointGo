import assert from "node:assert/strict";
import test from "node:test";
import { outcomeAction } from "../src/shared/offline/outcome.ts";

test("only an applied write counts as sent", () => {
  assert.equal(outcomeAction("APPLIED"), "sent");
});

test("a write discarded on the server leaves the device instead of blocking the queue", () => {
  assert.equal(outcomeAction("DISCARDED"), "dropped");
});

test("conflicts and refusals wait for a person", () => {
  assert.equal(outcomeAction("CONFLICT"), "review");
  assert.equal(outcomeAction("REJECTED"), "review");
});

test("a write recorded but not decided is sent again later", () => {
  assert.equal(outcomeAction("RECEIVED"), "wait");
});
