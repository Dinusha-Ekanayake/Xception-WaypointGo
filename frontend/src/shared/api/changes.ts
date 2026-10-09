// Roadmap F-07b: one live connection per set of depots, shared by every read on
// the page. The server sends `change` with a topic and a depot when something
// committed, and `ping` every 25 s; a read that listens for a topic re-reads
// itself through its usual endpoint, so the stream carries no data of its own.
//
// Silence is not trusted: with nothing heard for LIVE_SILENT_MS the connection
// counts as down, and reads go back to their normal polling until it returns.

/** With the stream up, a read still polls this often, as a safety net. */
export const LIVE_FALLBACK_MS = 15_000;
/** Nothing heard for this long (heartbeats are every 25 s) and the stream counts as down. */
export const LIVE_SILENT_MS = 45_000;

export type Change = { topic: string; depot?: string };
type Listener = (change: Change) => void;
type StatusListener = (live: boolean) => void;

type Stream = {
  source: EventSource | null;
  listeners: Set<Listener>;
  status: Set<StatusListener>;
  live: boolean;
  lastHeard: number;
  watchdog: ReturnType<typeof setInterval> | null;
};

const streams = new Map<string, Stream>();

/** The depots as one key and one query, sorted so the same set shares one connection. */
export function streamKey(depots: string[]): string {
  return [...new Set(depots)].sort().join(",");
}

/** A store watches its outlet instead of a depot: it hears the topic of a change that names it. */
export function outletWatch(outletId: string): string {
  return `outlet:${outletId}`;
}

/** The stream's query: `depot=` for a depot, `outlet=` for an outlet watched with {@link outletWatch}. */
export function streamQuery(key: string): string {
  return key
    .split(",")
    .map((place) => (place.startsWith("outlet:") ? `outlet=${encodeURIComponent(place.slice(7))}` : `depot=${encodeURIComponent(place)}`))
    .join("&");
}

/** Whether a change concerns a read of these topics. */
export function concerns(topics: string[], change: Change): boolean {
  return topics.includes(change.topic);
}

function setLive(stream: Stream, live: boolean): void {
  if (stream.live === live) return;
  stream.live = live;
  for (const listener of stream.status) listener(live);
}

function open(key: string): Stream {
  const stream: Stream = { source: null, listeners: new Set(), status: new Set(), live: false, lastHeard: 0, watchdog: null };
  if (typeof EventSource === "undefined") return stream;
  const source = new EventSource(`/api/changes/stream?${streamQuery(key)}`);
  const heard = () => {
    stream.lastHeard = Date.now();
    setLive(stream, true);
  };
  source.onopen = heard;
  source.addEventListener("ping", heard);
  source.addEventListener("change", (event) => {
    heard();
    let change: Change;
    try {
      change = JSON.parse((event as MessageEvent<string>).data) as Change;
    } catch {
      return;
    }
    for (const listener of stream.listeners) listener(change);
  });
  // The browser reconnects by itself; until it does, reads poll as before.
  source.onerror = () => setLive(stream, false);
  stream.watchdog = setInterval(() => {
    if (stream.live && Date.now() - stream.lastHeard > LIVE_SILENT_MS) setLive(stream, false);
  }, 5_000);
  stream.source = source;
  return stream;
}

/**
 * Listens for changes at these depots, sharing one connection with every other
 * listener of the same depots. Returns what stops listening; the connection
 * closes with its last listener.
 */
export function listenForChanges(depots: string[], onChange: Listener, onStatus: StatusListener): () => void {
  const key = streamKey(depots);
  if (!key) return () => {};
  let stream = streams.get(key);
  if (!stream) {
    stream = open(key);
    streams.set(key, stream);
  }
  stream.listeners.add(onChange);
  stream.status.add(onStatus);
  onStatus(stream.live);
  const held = stream;
  return () => {
    held.listeners.delete(onChange);
    held.status.delete(onStatus);
    if (held.listeners.size === 0) {
      held.source?.close();
      if (held.watchdog) clearInterval(held.watchdog);
      streams.delete(key);
    }
  };
}
