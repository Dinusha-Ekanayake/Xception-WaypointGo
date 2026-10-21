// The warehouse's product names (docs/issues/product-names/PLAN.md), kept on the
// device so a label reads the same offline. Pure: no request here. productLabel
// asks for a name it does not know, and useProductNames (shared/catalogue) reads
// the ones asked for and keeps them.

const KEY = "waypoint.product-names";
const KEEP = 3000;
const listeners = new Set<() => void>();
const names = new Map<string, string>(kept());
/** Asked and answered with no name, this session: not asked again. */
const unnamed = new Set<string>();
const wanted = new Set<string>();
/** A read that failed: not asked again before this time (epoch ms), so a screen that re-renders often does not ask each time. */
const failedUntil = new Map<string, number>();
const RETRY_MS = 60_000;
let version = 0;

function kept(): Array<[string, string]> {
  try {
    const raw = globalThis.localStorage?.getItem(KEY);
    const parsed: unknown = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed) ? parsed.filter((e): e is [string, string] => Array.isArray(e) && typeof e[0] === "string" && typeof e[1] === "string") : [];
  } catch {
    return [];
  }
}

function changed(): void {
  version += 1;
  for (const listener of listeners) listener();
}

/** The warehouse's name, or null while unknown or when it has none; an unknown one is asked for. */
export function productNameOf(productId: string): string | null {
  const name = names.get(productId);
  if (name !== undefined) return name;
  if (!unnamed.has(productId) && !wanted.has(productId) && (failedUntil.get(productId) ?? 0) <= Date.now()) {
    wanted.add(productId);
    // Told after the render that asked, never during it.
    queueMicrotask(() => { for (const listener of listeners) listener(); });
  }
  return null;
}

/** The ids asked for and not yet read; clears them. */
export function takeWantedNames(): string[] {
  const ids = [...wanted];
  wanted.clear();
  return ids;
}

/** Keeps what the server answered; an id it left out has no name. */
export function rememberNames(asked: string[], answer: Record<string, string>): void {
  for (const id of asked) {
    if (answer[id]) names.set(id, answer[id]!);
    else unnamed.add(id);
  }
  try {
    globalThis.localStorage?.setItem(KEY, JSON.stringify([...names].slice(-KEEP)));
  } catch {
    // Private mode or full storage: the names still hold for this page.
  }
  changed();
}

/** A read that failed (offline, or a server without names): asked again after a minute. */
export function forgetWanted(ids: string[]): void {
  for (const id of ids) {
    wanted.delete(id);
    failedUntil.set(id, Date.now() + RETRY_MS);
  }
}

export function onProductNames(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function productNamesVersion(): number {
  return version;
}
