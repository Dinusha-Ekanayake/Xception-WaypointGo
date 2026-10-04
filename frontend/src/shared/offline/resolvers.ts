import type { StoredEntry } from "./store.ts";

// Only the role that made a write knows what version its record is at now and
// who is acting, so redoing a held conflict asks that role. A role registers a
// resolver for the command kinds it owns; the review panel offers "redo" only
// where one exists, and "discard" everywhere.

export type RedoBasis = {
  /** The version the redo is checked against. */
  expectedVersion: number | null;
  /** Who records the redo, for a shared device such as the loader's. */
  actingUserId?: string;
};

/**
 * Answers the basis for redoing `entry`, given the writes still waiting on the
 * device, or null when it cannot right now (no loader signed in, for example).
 * Throws when the current version cannot be read, such as with no connection.
 */
export type Resolver = (entry: StoredEntry, waiting: StoredEntry[]) => Promise<RedoBasis | null>;

const resolvers = new Map<string, Resolver>();

/** `prefix` is the module part of a command kind, such as "loading:". Returns the unregister function. */
export function registerResolver(prefix: string, resolver: Resolver): () => void {
  resolvers.set(prefix, resolver);
  return () => {
    if (resolvers.get(prefix) === resolver) resolvers.delete(prefix);
  };
}

export function resolverFor(kind: string): Resolver | null {
  for (const [prefix, resolver] of resolvers) {
    if (kind.startsWith(prefix)) return resolver;
  }
  return null;
}
