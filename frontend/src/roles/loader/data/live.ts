// The topics of the depot's change stream (F-07b) a loader's screens re-read on:
// the plan, trips, checks, shortfalls and their issues, and a driver reporting at
// the depot (vehicle.at_depot). Anything else committing at the depot is not theirs.

export const LOADER_TOPICS = ["plan", "trip", "loading", "shortfall", "issue", "vehicle"];
