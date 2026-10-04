import type { IssueView, OrderView, ReadyTripView, RunSheetView, VehicleView } from "@shared/domain/types";
import type { ViewId } from "../navigation.ts";
import { TYPE as ISSUE_TYPE } from "./issues.ts";

// The dispatcher's global search (header, Ctrl+K): one substring match over
// everything already loaded for the depots in scope, grouped by kind. No
// fuzzy library and no new network call; this only ranks and groups what the
// screens already hold. A result carries enough to land on the right screen,
// but only where a screen already supports opening one item (Issues'
// focusIssueId); everywhere else it navigates and leaves the screen's own
// filter for the dispatcher to use, rather than inventing new focus props.

export type SearchKind = "order" | "vehicle" | "trip" | "issue" | "depot";

export type SearchResult = {
  kind: SearchKind;
  /** Stable within its kind, for the list key and keyboard selection. */
  id: string;
  label: string;
  sublabel: string;
  /** Where a choice navigates. */
  view: ViewId;
  /** An issue to focus once Issues is open; only issue results carry one. */
  focusIssueId?: string;
  /** A depot code to scope the shell to; only depot results carry one. */
  depotFilter?: string;
  /** Text to seed the target screen's own kept search box; only order results carry one. */
  prefillOrderText?: string;
};

export type SearchSource = {
  orders: OrderView[];
  vehicles: VehicleView[];
  sheets: RunSheetView[];
  dock: ReadyTripView[];
  issues: IssueView[];
  /** Depot codes in the session's scope; there is no depot name reader at the shell level, so the code is the label. */
  depots: string[];
};

const KIND_LABEL: Record<SearchKind, string> = {
  order: "Orders",
  vehicle: "Vehicles",
  trip: "Trips",
  issue: "Issues",
  depot: "Depots",
};

export function kindLabel(kind: SearchKind): string {
  return KIND_LABEL[kind];
}

/** How well `query` matches `value`: higher is better, 0 is no match. startsWith beats contains; a shorter value beats a longer one at the same tier. */
function score(value: string, query: string): number {
  const v = value.toLowerCase();
  const q = query.toLowerCase();
  if (q.length === 0 || v.length === 0) return 0;
  if (v === q) return 1000 - v.length;
  if (v.startsWith(q)) return 500 - v.length;
  if (v.includes(q)) return 100 - v.length;
  return 0;
}

/** The best score any field reaches for this query; 0 when none match. */
function bestOf(fields: Array<string | null | undefined>, query: string): number {
  return fields.reduce((best, field) => Math.max(best, field ? score(field, query) : 0), 0);
}

type Scored<T> = { item: T; rank: number };

function topScored<T>(items: T[], query: string, fields: (item: T) => Array<string | null | undefined>): Scored<T>[] {
  return items
    .map((item) => ({ item, rank: bestOf(fields(item), query) }))
    .filter((scored) => scored.rank > 0)
    .sort((a, b) => b.rank - a.rank);
}

/**
 * Grouped matches across every loaded resource, ranked within each kind. An
 * empty or whitespace-only query returns no results (there is nothing useful
 * to rank), rather than a default listing of everything on screen.
 */
export function search(source: SearchSource, query: string): SearchResult[] {
  const q = query.trim();
  if (q.length === 0) return [];

  const orders = topScored(source.orders, q, (o) => [o.orderRef, o.outletId, o.districtName, o.brandCode]).map(
    ({ item }): SearchResult => ({
      kind: "order",
      id: item.orderId,
      label: item.orderRef,
      sublabel: `${item.outletId} · ${item.districtName} · ${item.brandCode}`,
      view: "orders",
      prefillOrderText: item.orderRef,
    }),
  );

  const vehicles = topScored(source.vehicles, q, (v) => [v.vehicleId]).map(
    ({ item }): SearchResult => ({
      kind: "vehicle",
      id: item.vehicleId,
      label: item.vehicleId,
      sublabel: `${item.depotCode} · ${item.van ? "Van" : "Truck"}${item.refrigerated ? " · Refrigerated" : ""}`,
      view: "vehicles",
    }),
  );

  const sheetTrips = topScored(source.sheets, q, (s) => [s.vehicleId]).map(
    ({ item }): SearchResult => ({
      kind: "trip",
      id: `sheet-${item.vehicleId}`,
      label: item.vehicleId,
      sublabel: `On the road · ${item.stops.length} ${item.stops.length === 1 ? "stop" : "stops"}`,
      view: "live",
    }),
  );
  const dockTrips = topScored(source.dock, q, (t) => [t.vehicleId, t.tripId]).map(
    ({ item }): SearchResult => ({
      kind: "trip",
      id: item.tripId,
      label: `${item.vehicleId} · T${item.tripNumber}`,
      sublabel: `${item.dockCode} · ${item.brandCode} · ${item.districtName}`,
      view: "live",
    }),
  );
  const trips = [...sheetTrips, ...dockTrips];

  const issues = topScored(source.issues, q, (i) => [i.description, ISSUE_TYPE[i.type], i.outletId, i.depotCode]).map(
    ({ item }): SearchResult => ({
      kind: "issue",
      id: item.issueId,
      label: ISSUE_TYPE[item.type],
      sublabel: `${item.depotCode}${item.outletId ? ` · ${item.outletId}` : ""} · ${item.description}`,
      view: "issues",
      focusIssueId: item.issueId,
    }),
  );

  const depots = topScored(source.depots, q, (code) => [code]).map(
    ({ item }): SearchResult => ({
      kind: "depot",
      id: item,
      label: item,
      sublabel: "Depot",
      view: "overview",
      depotFilter: item,
    }),
  );

  return [...orders, ...vehicles, ...trips, ...issues, ...depots];
}

/** Results grouped by kind, in a fixed kind order, each group's own rank order kept. */
export function groupResults(results: SearchResult[]): Array<{ kind: SearchKind; items: SearchResult[] }> {
  const order: SearchKind[] = ["order", "vehicle", "trip", "issue", "depot"];
  return order
    .map((kind) => ({ kind, items: results.filter((r) => r.kind === kind) }))
    .filter((group) => group.items.length > 0);
}
