// Live refresh, phase 2 (docs/issues/live-refresh/PLAN.md): one live connection
// per tab. The change stream, the unread count and vehicle positions travel as
// named events on `/api/live/stream?parts=...`, so a dispatcher with two tabs
// holds two connections, not six, and never exhausts the browser's six per host.
//
// Each hook still asks for "its" stream by its own path and query and gets back
// something that behaves like an EventSource (addEventListener, onopen, onerror,
// close). This file decides how that is carried:
// - on the tab's one connection, when the part's query matches the one already
//   carried there (a second positions watch for another depot gets its own);
// - on the part's own endpoint, when the tab's connection never opened (an older
//   server, or a test that does not serve it). Once that happens the tab stays on
//   the separate endpoints until it reloads: they are what worked before.

export type LiveSource = {
  addEventListener(type: string, listener: (event: MessageEvent<string>) => void): void;
  onopen: (() => void) | null;
  onerror: (() => void) | null;
  close(): void;
};

type PartName = "changes" | "unread" | "positions";

/** A hook's own endpoint, and the part of the tab's connection that carries it. */
const PART_OF: Record<string, PartName> = {
  "/api/changes/stream": "changes",
  "/api/notifications/stream": "unread",
  "/api/execution/positions/stream": "positions",
};

class Shim implements LiveSource {
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  readonly listeners = new Map<string, Set<(event: MessageEvent<string>) => void>>();
  /** Its own connection, when it is not on the tab's. */
  own: EventSource | null = null;
  closed = false;

  readonly path: string;
  readonly part: PartName;
  readonly query: string;

  constructor(path: string, part: PartName, query: string) {
    this.path = path;
    this.part = part;
    this.query = query;
  }

  addEventListener(type: string, listener: (event: MessageEvent<string>) => void): void {
    let set = this.listeners.get(type);
    if (!set) {
      set = new Set();
      this.listeners.set(type, set);
      this.own?.addEventListener(type, (event) => this.dispatch(type, event as MessageEvent<string>));
    }
    set.add(listener);
  }

  dispatch(type: string, event: MessageEvent<string>): void {
    for (const listener of [...(this.listeners.get(type) ?? [])]) listener(event);
  }

  close(): void {
    if (this.closed) return;
    this.closed = true;
    this.own?.close();
    this.own = null;
    shims.delete(this);
    schedule();
  }

  /** Over to its own endpoint, with every listener it already has. */
  goAlone(): void {
    if (this.own || this.closed) return;
    const source = new EventSource(this.query ? `${this.path}?${this.query}` : this.path);
    source.onopen = () => this.onopen?.();
    source.onerror = () => this.onerror?.();
    for (const type of this.listeners.keys()) {
      source.addEventListener(type, (event) => this.dispatch(type, event as MessageEvent<string>));
    }
    this.own = source;
  }
}

const shims = new Set<Shim>();
let tab: EventSource | null = null;
let tabUrl = "";
let carried = new Set<Shim>();
/** null until the tab's connection opens once; false once it failed before ever opening. */
let tabWorks: boolean | null = null;
let scheduled = false;

function schedule(): void {
  if (scheduled) return;
  scheduled = true;
  queueMicrotask(() => {
    scheduled = false;
    reconcile();
  });
}

/** The tab's query: each carried part, its own parameters prefixed with its name. */
export function tabQuery(parts: Array<{ part: PartName; query: string }>): string {
  const out = new URLSearchParams();
  out.set("parts", parts.map((p) => p.part).join(","));
  for (const { part, query } of parts) {
    new URLSearchParams(query).forEach((value, name) => out.append(`${part}.${name}`, value));
  }
  return out.toString();
}

function reconcile(): void {
  const open = [...shims].filter((s) => !s.closed);
  // Each part rides the tab's connection with the first query asked for it; any
  // other query for the same part, or every one when the tab's connection failed, goes alone.
  const first = new Map<PartName, Shim>();
  const next = new Set<Shim>();
  for (const shim of open) {
    if (tabWorks === false || shim.own) {
      shim.goAlone();
      continue;
    }
    const lead = first.get(shim.part);
    if (!lead) {
      first.set(shim.part, shim);
      next.add(shim);
    } else if (lead.query === shim.query) {
      next.add(shim);
    } else {
      shim.goAlone();
    }
  }
  const url = next.size ? `/api/live/stream?${tabQuery([...first.values()].map((s) => ({ part: s.part, query: s.query })))}` : "";
  carried = next;
  if (url === tabUrl) return;
  tab?.close();
  tab = null;
  tabUrl = url;
  if (!url) return;
  const source = new EventSource(url);
  source.onopen = () => {
    tabWorks = true;
    for (const shim of carried) shim.onopen?.();
  };
  source.onerror = () => {
    if (tabWorks !== true) {
      // Never opened: an older server, or one that refuses a part. Go back to the separate endpoints.
      tabWorks = false;
      source.close();
      if (tab === source) {
        tab = null;
        tabUrl = "";
      }
      schedule();
      return;
    }
    // Dropped after working: the browser reconnects by itself, as before.
    for (const shim of carried) shim.onerror?.();
  };
  for (const type of ["change", "ping", "unread", "positions"]) {
    source.addEventListener(type, (event) => {
      for (const shim of carried) shim.dispatch(type, event as MessageEvent<string>);
    });
  }
  tab = source;
}

/**
 * A live stream by its own endpoint's path and query, carried on the tab's one
 * connection when it can be. Use where `new EventSource(path)` was used.
 */
export function liveSource(path: string, query = ""): LiveSource {
  const part = PART_OF[path];
  if (typeof EventSource === "undefined") {
    // No live connection here at all: a stream that never says anything, so reads poll.
    return { addEventListener: () => {}, onopen: null, onerror: null, close: () => {} };
  }
  if (!part) {
    // Not a part of the tab's connection: its own, as before.
    return new EventSource(query ? `${path}?${query}` : path) as unknown as LiveSource;
  }
  const shim = new Shim(path, part, query);
  shims.add(shim);
  schedule();
  return shim;
}

/** For tests: forget the tab's connection and what it learned. */
export function resetTabStream(): void {
  for (const shim of [...shims]) shim.close();
  shims.clear();
  tab?.close();
  tab = null;
  tabUrl = "";
  carried = new Set();
  tabWorks = null;
}
