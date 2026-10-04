import assert from "node:assert/strict";
import test from "node:test";
import { drivenLine, readTrail, trailPoints } from "../src/shared/ui/map/trail.ts";

// The trail the driver's map draws: every page, no 0,0 points, no point twice.

const point = (at: string, lat: number | string | null, lon: number | string | null, lowQuality = false) =>
  ({ recordedAt: at, latitude: lat, longitude: lon, lowQuality }) as never;

test("a missing coordinate or a low quality fix is skipped, never drawn at 0,0", () => {
  const out = trailPoints([point("2026-10-04T08:00:00Z", null, 80.6), point("2026-10-04T08:00:10Z", 7.2, 80.6, true), point("2026-10-04T08:00:20Z", "7.29", "80.63")]);
  assert.deepEqual(out.map(({ lat, lon }) => ({ lat, lon })), [{ lat: 7.29, lon: 80.63 }]);
});

test("every page is read, so a long trip keeps its newest part", async () => {
  const pages: Record<string, { items: never[]; nextCursor: string | null }> = {
    first: { items: [point("2026-10-04T08:00:00Z", 7.1, 80.1)], nextCursor: "a" },
    a: { items: [point("2026-10-04T09:00:00Z", 7.2, 80.2)], nextCursor: null },
  };
  const out = await readTrail(async (cursor) => pages[cursor ?? "first"]!);
  assert.deepEqual(out.map((p) => p.lat), [7.1, 7.2]);
});

test("a point the server already holds is not drawn twice", () => {
  const server = trailPoints([point("2026-10-04T08:00:00.000Z", 7.1, 80.1)]);
  const local = [{ lat: 7.1, lon: 80.1, at: Date.parse("2026-10-04T08:00:00Z") }, { lat: 7.3, lon: 80.3, at: Date.parse("2026-10-04T08:05:00Z") }];
  assert.deepEqual(drivenLine(server, local), [{ lat: 7.1, lon: 80.1 }, { lat: 7.3, lon: 80.3 }]);
});
