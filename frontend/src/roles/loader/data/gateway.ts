import { request, requestAll } from "@shared/api/client";
import { send, type Command, type CommandAck } from "@shared/api/commands";
import { enqueue, readThrough } from "@shared/offline";
import type { ManifestView, OutletView, ReadyTripView } from "@shared/domain/types";
import { sampleGateway } from "./fixtures.ts";

// Everything the loader screens read and write, behind one seam. The real
// gateway talks to the Loading module (#10); the sample gateway runs the same
// rules in memory so the screens can be built and judged before it exists.
//
// Sample data is opt in with NEXT_PUBLIC_LOADER_FIXTURES=1 and never ships in a
// production build, and the screen says it is showing sample data.

export type LoadingGateway = {
  sample: boolean;
  readyTrips: (depot: string, date: string, signal: AbortSignal) => Promise<ReadyTripView[]>;
  manifest: (tripId: string, signal: AbortSignal) => Promise<ManifestView>;
  outlets: (depot: string, signal: AbortSignal) => Promise<OutletView[]>;
  send: (command: Command) => Promise<CommandAck>;
  /** Keep a write on this device until the connection returns (resilient tier). */
  queue: (command: Command) => Promise<{ durable: boolean; reason?: string }>;
  /** Sample only: publish a new plan version under the loader, to show R-LOD-03. */
  revisePlan?: (tripId: string) => void;
};

function liveGateway(accountId: string): LoadingGateway {
  return {
    sample: false,
    // Paths follow MODULES.md; they are confirmed when the Loading module lands.
    // Each read is kept on the dock tablet, so a reload with the network down
    // still shows the day's trips and manifests (issue #201). The day is in the
    // key: yesterday's departures never stand in for today's.
    readyTrips: (depot, date, signal) =>
      readThrough(accountId, `loader:trips:${depot}:${date}`, () =>
        request(`/api/loading/trips?depot=${encodeURIComponent(depot)}&date=${date}`, { signal })),
    manifest: (tripId, signal) =>
      readThrough(accountId, `loader:manifest:${tripId}`, () => request(`/api/loading/trips/${tripId}/manifest`, { signal })),
    outlets: (depot, signal) =>
      readThrough(accountId, `loader:outlets:${depot}`, () => requestAll(`/api/reference/outlets?depot=${encodeURIComponent(depot)}`, { signal })),
    send: (command) => send(command),
    queue: (command) => enqueue(accountId, "loader", command),
  };
}

export function sampleDataEnabled(): boolean {
  return process.env.NODE_ENV !== "production" && process.env.NEXT_PUBLIC_LOADER_FIXTURES === "1";
}

export function createGateway(accountId: string): LoadingGateway {
  return sampleDataEnabled() ? sampleGateway() : liveGateway(accountId);
}
