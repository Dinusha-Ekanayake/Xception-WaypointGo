import assert from "node:assert/strict";
import test from "node:test";
import { ApiError, friendlyError, type Problem } from "../src/shared/api/problem.ts";

// Usability round: no raw error.message reaches a screen (AGENTS.md). friendlyError
// is the one place that turns a network drop, a server outage, a rule refusal or an
// unrecognised failure (an HTML gateway page parsed with no body) into a sentence.

function problem(overrides: Partial<Problem> = {}): Problem {
  return {
    type: "about:blank",
    title: "Error",
    status: 500,
    detail: "",
    instance: "",
    code: "ERROR",
    correlationId: "",
    violations: [],
    extensions: {},
    ...overrides,
  };
}

test("a network failure reads as a short, plain sentence", () => {
  assert.equal(friendlyError(new TypeError("Failed to fetch")), "No connection. Try again.");
});

test("a server error (status 500 and over) reads as a server problem", () => {
  const error = new ApiError(problem({ status: 503, detail: "" }));
  assert.equal(friendlyError(error), "The server had a problem. Try again.");
});

test("an ApiError with a detail (4xx, a problem body) shows that detail as is", () => {
  const error = new ApiError(problem({ status: 409, detail: "This order changed since you opened it." }));
  assert.equal(friendlyError(error), "This order changed since you opened it.");
});

test("an unrecognised failure, such as an HTML gateway page with no body, reads as a generic sentence", () => {
  const error = new ApiError(problem({ status: 404, title: "Error", detail: "" }));
  assert.equal(friendlyError(error), "Something went wrong. Try again.");
  assert.equal(friendlyError(new Error("Error")), "Something went wrong. Try again.");
  assert.equal(friendlyError("not an error at all"), "Something went wrong. Try again.");
});
