// Shapes shared by every module's contract. These mirror the backend records in
// com.waypoint.dispatch.shared and each module's contract package; the backend
// is the source of truth and a change there is a change here.
//
// Conventions, so they are decided once:
// - Weights, volumes, minutes and fuel are decimal strings, never numbers. JSON
//   numbers are binary floating point, and a capacity decision must never rest
//   on floating point (AGENTS.md, Data and Migration Rules).
// - Instants are ISO 8601 strings with an offset; dates are "YYYY-MM-DD"; wall
//   clock times are "HH:mm:ss" in the depot timezone, Asia/Colombo.
// - An optional backend field (java.util.Optional) is `T | null` here.
// - `rowVersion` is what a caller sends back as `expectedVersion`.

export type Uuid = string;
export type Decimal = string;
export type IsoInstant = string;
export type IsoDate = string;
export type IsoTime = string;

/** chilled or ambient, from the order; never inferred from products. */
export type Temperature = "chilled" | "ambient";

/** One page of a keyset-paginated read. `nextCursor` is null on the last page. */
export type Page<T> = {
  items: T[];
  nextCursor: string | null;
};

/** An event as a consumer receives it. Delivery is at least once. */
export type EventEnvelope<TPayload> = {
  eventId: Uuid;
  type: string;
  version: number;
  occurredAt: IsoInstant;
  producer: string;
  correlationId: string | null;
  actorId: Uuid | null;
  payload: TPayload;
};
