import assert from "node:assert/strict";
import test from "node:test";
import { drainThroughPages, navigateWithin, precacheAll } from "../scripts/sw-cache.mjs";

// Roadmap F-05: the service worker installs past a bad file, opens the kept
// shell when the network hangs, and lets Background Sync retry while work is left.

test("one failed file does not block the install, but a missing shell does", async () => {
  const added: string[] = [];
  const cache = { add: async (a: string) => (a === "/bad.js" ? Promise.reject(new Error("404")) : void added.push(a)) };
  assert.equal(await precacheAll(cache, ["/", "/a.js", "/bad.js", "/b.css"], "/"), 1);
  assert.deepEqual(added.sort(), ["/", "/a.js", "/b.css"]);

  const noShell = { add: async (a: string) => (a === "/" ? Promise.reject(new Error("offline")) : undefined) };
  await assert.rejects(precacheAll(noShell, ["/", "/a.js"], "/"));
});

const page = (body: string) => new Response(body);

test("opening the app takes the network page when it answers in time", async () => {
  const r = await navigateWithin(Promise.resolve(page("fresh")), async () => page("kept"), 50);
  assert.equal(await r.text(), "fresh");
});

test("a network that hangs gives way to the kept shell", async () => {
  const r = await navigateWithin(new Promise<Response>(() => {}), async () => page("kept"), 20);
  assert.equal(await r.text(), "kept");
});

test("a network that fails falls back at once, and with nothing kept the failure stands", async () => {
  const r = await navigateWithin(Promise.reject(new Error("offline")), async () => page("kept"), 5000);
  assert.equal(await r.text(), "kept");
  await assert.rejects(navigateWithin(Promise.reject(new Error("offline")), async () => undefined, 5000));
});

/** A page that answers the drain request with `done`, or never answers. */
function client(done: boolean | null) {
  return {
    postMessage: (_message: unknown, ports: Array<{ postMessage: (data: unknown) => void }>) => {
      if (done !== null) setTimeout(() => ports[0]!.postMessage({ done }), 1);
    },
  };
}

class Fake {
  port1: { onmessage: ((event: { data: unknown }) => void) | null } = { onmessage: null };
  port2 = { postMessage: (data: unknown) => this.port1.onmessage?.({ data }) };
}
const FakeChannel = Fake as unknown as typeof MessageChannel;

test("Background Sync settles only when every open page has sent all it can", async () => {
  await drainThroughPages([client(true), client(true)], 200, FakeChannel);
  await assert.rejects(drainThroughPages([client(true), client(false)], 200, FakeChannel), /still waiting/);
  await assert.rejects(drainThroughPages([client(null)], 20, FakeChannel), /still waiting/, "a page that never answers is retried");
});
