import assert from "node:assert/strict";
import test from "node:test";
import { withTransition } from "../src/shared/ui/transition.ts";

// UX polish 4: screen changes run through the browser's View Transitions when
// it has them, and never wait on them: without support, or under reduced
// motion, the update runs at once exactly as before.

type Globals = { document?: unknown; window?: unknown };
const globals = globalThis as Globals;

function stub(reduced: boolean, withApi: boolean) {
  const saved = { document: globals.document, window: globals.window };
  const calls: string[] = [];
  const dataset: Record<string, string> = {};
  let finish: () => void = () => undefined;
  globals.window = { matchMedia: (query: string) => ({ matches: reduced && query.includes("reduce") }) };
  globals.document = {
    documentElement: { dataset },
    ...(withApi
      ? {
          startViewTransition(update: () => void) {
            calls.push(`start:${dataset.vt}`);
            update();
            return { finished: new Promise<void>((resolve) => (finish = resolve)) };
          },
        }
      : {}),
  };
  const restore = () => {
    globals.document = saved.document;
    globals.window = saved.window;
  };
  return { calls, dataset, finish: () => finish(), restore };
}

test("without a document the update runs at once", () => {
  assert.equal(typeof document, "undefined");
  let ran = false;
  withTransition(() => {
    ran = true;
  });
  assert.equal(ran, true);
});

test("without the View Transitions API the update runs at once", () => {
  const env = stub(false, false);
  try {
    let ran = false;
    withTransition(() => {
      ran = true;
    }, "forward");
    assert.equal(ran, true);
    assert.equal(env.dataset.vt, undefined);
  } finally {
    env.restore();
  }
});

test("with the API the update runs inside the transition, which carries the direction", async () => {
  const env = stub(false, true);
  try {
    const order: string[] = [];
    withTransition(() => order.push("update"), "back");
    assert.deepEqual(env.calls, ["start:back"]);
    assert.deepEqual(order, ["update"]);
    env.finish();
    await new Promise((resolve) => setTimeout(resolve, 0));
    assert.equal(env.dataset.vt, undefined, "the direction is cleared once the transition ends");
  } finally {
    env.restore();
  }
});

test("under reduced motion the transition is skipped and the update runs at once", () => {
  const env = stub(true, true);
  try {
    let ran = false;
    withTransition(() => {
      ran = true;
    }, "forward");
    assert.equal(ran, true);
    assert.deepEqual(env.calls, []);
    assert.equal(env.dataset.vt, undefined);
  } finally {
    env.restore();
  }
});
