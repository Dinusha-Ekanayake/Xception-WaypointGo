import assert from "node:assert/strict";
import test from "node:test";
import { holdLapsed } from "../src/roles/loader/data/manifest.ts";

const holder = (lastActiveAt: string) => ({
  userId: "u1", name: "Isuru", employeeCode: null, since: "2026-10-01T08:00:00Z", lastActiveAt,
});

test("a hold lapses 30 minutes after the holder's last activity, not after the take", () => {
  const h = holder("2026-10-01T08:20:00Z");
  assert.equal(holdLapsed(h, new Date("2026-10-01T08:49:59Z")), false);
  assert.equal(holdLapsed(h, new Date("2026-10-01T08:50:00Z")), true);
});
