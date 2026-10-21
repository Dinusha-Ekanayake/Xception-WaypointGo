import assert from "node:assert/strict";
import { test } from "node:test";

// Live refresh phase 2: one connection per tab, with the separate endpoints as the fallback.

type Fake = { url: string; closed: boolean; onopen: (() => void) | null; onerror: (() => void) | null; listeners: Map<string, Array<(e: { data: string }) => void>> };
const opened: Fake[] = [];
class FakeEventSource {
  url: string;
  closed = false;
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  listeners = new Map<string, Array<(e: { data: string }) => void>>();
  constructor(url: string) {
    this.url = url;
    opened.push(this as unknown as Fake);
  }
  addEventListener(type: string, fn: (e: { data: string }) => void) {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), fn]);
  }
  close() {
    this.closed = true;
  }
  emit(type: string, data: string) {
    for (const fn of this.listeners.get(type) ?? []) fn({ data });
  }
}
(globalThis as unknown as { EventSource: unknown }).EventSource = FakeEventSource;

const { liveSource, resetTabStream } = await import("../src/shared/live/tabStream.ts");
const tick = () => new Promise((r) => setTimeout(r, 0));
const live = () => opened.filter((s) => !s.closed);

test("the change stream and the unread count share one connection, and each hears its own events", async () => {
  resetTabStream();
  opened.length = 0;
  const changes = liveSource("/api/changes/stream", "depot=KDY");
  const unread = liveSource("/api/notifications/stream");
  const heard: string[] = [];
  changes.addEventListener("change", (e) => heard.push(`change ${e.data}`));
  unread.addEventListener("unread", (e) => heard.push(`unread ${e.data}`));
  await tick();
  assert.equal(live().length, 1, "one connection for the tab");
  assert.equal(live()[0]!.url, "/api/live/stream?parts=changes%2Cunread&changes.depot=KDY");
  const tab = live()[0] as unknown as FakeEventSource;
  tab.onopen?.();
  tab.emit("change", '{"topic":"plan"}');
  tab.emit("unread", '{"count":2}');
  assert.deepEqual(heard, ['change {"topic":"plan"}', 'unread {"count":2}']);
  changes.close();
  unread.close();
  await tick();
  assert.equal(live().length, 0, "closed with its last listener");
});

test("a tab whose connection never opens goes back to the separate endpoints", async () => {
  resetTabStream();
  opened.length = 0;
  const changes = liveSource("/api/changes/stream", "depot=KDY");
  let heard = "";
  changes.addEventListener("change", (e) => (heard = e.data));
  await tick();
  (live()[0] as unknown as FakeEventSource).onerror?.();
  await tick();
  assert.deepEqual(live().map((s) => s.url), ["/api/changes/stream?depot=KDY"]);
  (live()[0] as unknown as FakeEventSource).emit("change", '{"topic":"trip"}');
  assert.equal(heard, '{"topic":"trip"}', "its listeners came along");
  changes.close();
});

test("a second positions watch for another depot gets a connection of its own", async () => {
  resetTabStream();
  opened.length = 0;
  liveSource("/api/execution/positions/stream", "depot=KDY&date=2026-10-21");
  liveSource("/api/execution/positions/stream", "depot=CMB&date=2026-10-21");
  await tick();
  assert.deepEqual(live().map((s) => s.url).sort(), [
    "/api/execution/positions/stream?depot=CMB&date=2026-10-21",
    "/api/live/stream?parts=positions&positions.depot=KDY&positions.date=2026-10-21",
  ]);
  resetTabStream();
});
