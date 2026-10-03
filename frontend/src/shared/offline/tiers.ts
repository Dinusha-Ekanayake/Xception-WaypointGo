// How much offline capability each role gets, and why.
//
// The booklet's rule is "work away from the depot must remain usable offline".
// That is not the same as "everyone works offline", so the tiers differ:
//
//   driver        full      on the road, hill country, personal phone. The
//                           booklet explicitly requires record-offline-and-sync.
//   loader        resilient at the depot dock. Their stated problem is stale
//                           printed lists, which live data fixes, not offline.
//   store_manager resilient at the outlet counter. Away from the depot, but the
//                           actions are low frequency and tolerate a retry.
//   dispatcher    online    stated stable connectivity, and offline planning is
//                           actively harmful: two dispatchers planning offline
//                           produce irreconcilable plans.
//
// Since issue #201 the resilient tier also keeps its reads (readThrough in
// keptReads.ts) and may carry on with the remembered session when the server
// cannot be asked, so a dock tablet or a store phone reloaded with no network
// still opens on the day's work. What still separates the tiers: only the full
// tier downloads its working set ahead of time (prefetchesWorkingSet).

export type OfflineTier = "full" | "resilient" | "online";

export type Role = "dispatcher" | "loader" | "driver" | "store_manager";

const TIERS: Record<Role, OfflineTier> = {
  driver: "full",
  loader: "resilient",
  store_manager: "resilient",
  dispatcher: "online",
};

export function offlineTier(role: Role): OfflineTier {
  return TIERS[role];
}

/** Whether a failed write should be queued for this role, or reported immediately. */
export function queuesWrites(role: Role): boolean {
  return offlineTier(role) !== "online";
}

/** Whether the role's working set is pre-downloaded so it survives a full outage. */
export function prefetchesWorkingSet(role: Role): boolean {
  return offlineTier(role) === "full";
}
