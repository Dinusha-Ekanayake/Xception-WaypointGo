import assert from "node:assert/strict";
import { test } from "node:test";
import type { IssueView } from "../src/shared/domain/issues.ts";
import { actionLabel, inquiryIssues, mayDecide, parseRupees, rupees } from "../src/roles/inquiry/data.ts";

// Roadmap FF-03: what the Operations workspace shows and offers; the server decides again.

const issue = (id: string, type: IssueView["type"], raisedAt: string) => ({ issueId: id, type, raisedAt }) as IssueView;

test("only damaged, short and disputed goods, newest first, each once", () => {
  const got = inquiryIssues([
    issue("a", "DAMAGED_GOODS", "2026-10-06T04:00:00Z"),
    issue("b", "VEHICLE_FAULT", "2026-10-07T04:00:00Z"),
    issue("c", "LOADING_SHORTFALL", "2026-10-07T03:00:00Z"),
    issue("a", "DAMAGED_GOODS", "2026-10-06T04:00:00Z"),
  ]);
  assert.deepEqual(got.map((i) => i.issueId), ["c", "a"]);
});

test("a write-off is decided by someone other than its recorder, and above the limit only by the head", () => {
  const w = { status: "recorded" as const, recordedBy: "me", needsHead: false };
  assert.equal(mayDecide(w, "me", false), false);
  assert.equal(mayDecide(w, "you", false), true);
  assert.equal(mayDecide({ ...w, needsHead: true }, "you", false), false);
  assert.equal(mayDecide({ ...w, needsHead: true }, "you", true), true);
  assert.equal(mayDecide({ ...w, status: "approved" }, "you", true), false);
});

test("a value is rupees with at most two decimals, and reads back in words", () => {
  assert.equal(parseRupees("4,500"), "4500");
  assert.equal(parseRupees("4500.5"), "4500.5");
  assert.equal(parseRupees("4500.555"), null);
  assert.equal(parseRupees("-1"), null);
  assert.equal(parseRupees("20000000"), null);
  assert.match(rupees("30000"), /^LKR 30,000\.00$/);
  assert.equal(actionLabel("write_off_approved"), "Write-off approved");
  assert.equal(actionLabel("something_new"), "something new");
});
