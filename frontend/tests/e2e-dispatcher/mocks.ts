import type { Page, Route } from "@playwright/test";
import type { RunSheetStopView, RunSheetView } from "../../src/shared/domain/execution.ts";
import type { ReadyTripView } from "../../src/shared/domain/loading.ts";
import type { OrderStatus, OrderView } from "../../src/shared/domain/ordering.ts";
import type { IssueHistoryView, IssueView } from "../../src/shared/domain/issues.ts";
import type { AllocationView, DeferralView, PlacementView, PlanView, TripView } from "../../src/shared/domain/planning.ts";

// A small stand-in for Ordering, Planning, Loading and Execution, in the shapes
// their contracts serve. Like Planning, every edit of a draft replaces it with
// the next version under a new id. The tests assert what the screen sends.

export const SESSION = { userId: "dispatcher-user", displayName: "Dinusha Bawantha", roles: ["dispatcher"], scope: ["depot:Kandy"] };
export const DEPOT = "Kandy";

export type Sent = { commandId: string; kind: string; expectedVersion: number | null; payload: Record<string, unknown> };

export function order(n: number, status: OrderStatus, extra: Partial<OrderView> = {}): OrderView {
  return {
    orderId: `order-${n}`, orderRef: `ORD009${2300 + n}`, outletId: `OUT0${50 + n}`, depotCode: DEPOT, brandCode: n % 2 ? "Fresh" : "Style",
    districtName: n % 2 ? "Kandy" : "Matale", requestedDate: "2027-03-01", deliveryDate: "2027-03-01", dateRolled: false,
    temperature: n % 2 ? "chilled" : "ambient", itemCount: 12, weightKg: "520", volumeM3: "7.9", status, warehouseOrderRef: "W-1",
    redeliveryOf: null, deferralCount: 0, placedAt: "2027-02-28T04:00:00Z", lines: [], rowVersion: 1, ...extra,
  };
}

function trip(vehicleId: string, tripNumber: 1 | 2, orderIds: string[]): TripView {
  return {
    tripId: `trip-${vehicleId}-${tripNumber}`, vehicleId, tripNumber, brandCode: "Fresh", districtName: "Kandy", temperature: "chilled",
    weightKg: "5200", volumeM3: "31.5", plannedMinutes: "157", plannedDeparture: "03:30:00",
    stops: orderIds.map((orderId, index) => ({
      sequence: index + 1, orderId, outletId: `OUT0${index + 51}`, plannedArrival: `0${4 + index}:10:00`, windowOpen: "03:00:00", windowClose: "08:00:00", serviceMinutes: "20",
    })),
  };
}

const served = (orderId: string, tripId: string): AllocationView => ({ orderId, decision: "SERVED", tripId, bindingRule: null, reason: "placed", checks: [] });
const deferred = (orderId: string): AllocationView => ({
  orderId, decision: "DEFERRED", tripId: null, bindingRule: "R-PLN-06", reason: "No refrigerated vehicle has 7.9 m³ free",
  checks: [
    { ruleId: "R-PLN-02", passed: true, reason: "chilled on a refrigerated vehicle", slack: null },
    { ruleId: "R-PLN-06", passed: false, reason: "volume 7.9 m³ exceeds 2.5 m³ free", slack: "-5.4" },
  ],
});

/** Orders 1 and 2 on VEH043 trip 1; order 3 deferred for volume. */
export function draftPlan(version = 1): PlanView {
  const t = trip("VEH043", 1, ["order-1", "order-2"]);
  return {
    planId: `plan-v${version}`, depotCode: DEPOT, serviceDate: "2027-03-01", planVersion: version, status: "DRAFT", referenceVersionId: "ref",
    ruleSetVersionId: "rules", priorityPolicyVersionId: "policy", supersedes: null, publishedAt: null, plannedWithoutPredictor: true,
    trips: [t], allocations: [served("order-1", t.tripId), served("order-2", t.tripId), deferred("order-3")], rowVersion: 1,
  };
}

const PLACES: PlacementView[] = [
  { vehicleId: "VEH044", tripNumber: 1, joins: false, tripId: null, feasible: true, bindingRule: null, reason: "Fits as a new trip", checks: [] },
  { vehicleId: "VEH043", tripNumber: 1, joins: true, tripId: "trip-VEH043-1", feasible: false, bindingRule: "R-PLN-06", reason: "volume 7.9 m³ exceeds 2.5 m³ free", checks: [] },
];

const vehicle = (vehicleId: string) => ({
  vehicleId, vehicleType: "truck", temperatureCapability: "reefer", weightCapKg: 5510, volumeCapM3: 33.4, kmPerL: 5, weeklyFuelQuotaL: 200,
  depotCode: DEPOT, refrigerated: true, van: false,
});

export type Desk = {
  orders: OrderView[];
  draft: PlanView | null;
  published: PlanView | null;
  sheets: RunSheetView[];
  dock: ReadyTripView[];
  issues: IssueView[];
  history: Record<string, IssueHistoryView[]>;
  deferrals: DeferralView[];
  commands: Sent[];
  /** Answer the next command of this kind with this problem instead of applying it. */
  refuse: { kind: string; status: number; code: string; detail: string; rules?: string[] } | null;
};

const body = (plan: PlanView) => ({
  planId: plan.planId, planVersion: plan.planVersion, status: plan.status, rowVersion: plan.rowVersion,
  served: plan.allocations.filter((a) => a.decision === "SERVED").length,
  deferred: plan.allocations.filter((a) => a.decision === "DEFERRED").length,
  unservable: 0, partial: false,
});

export async function serve(page: Page, start: Partial<Desk> = {}): Promise<Desk> {
  const desk: Desk = {
    orders: [order(1, "CONFIRMED"), order(2, "CONFIRMED"), order(3, "CONFIRMED")],
    draft: null, published: null, sheets: [], dock: [], issues: [], history: {}, deferrals: [], commands: [], refuse: null, ...start,
  };
  const json = (value: unknown) => ({ status: 200, contentType: "application/json", body: JSON.stringify(value) });
  const problem = (status: number, code: string, detail: string, rules: string[] = []) => ({
    status, contentType: "application/problem+json",
    body: JSON.stringify({ type: "about:blank", title: code, status, detail, code, correlationId: "test", violations: rules.map((rule) => ({ rule, message: "" })) }),
  });

  const apply = (command: Sent) => {
    const { payload } = command;
    if (command.kind.startsWith("issue:")) return applyIssue(desk, command);
    if (command.kind === "order:CloseForDay") return { alreadyClosed: false };
    if (command.kind === "plan:Generate") {
      desk.draft = draftPlan((desk.draft?.planVersion ?? 0) + 1);
      return body(desk.draft);
    }
    const plan = desk.draft!;
    if (command.kind === "plan:Override") {
      const added = trip(String(payload.vehicleId), payload.tripNumber as 1 | 2, [String(payload.orderId)]);
      desk.draft = {
        ...plan, planId: `plan-v${plan.planVersion + 1}`, planVersion: plan.planVersion + 1, trips: [...plan.trips, added],
        allocations: plan.allocations.map((a) => (a.orderId === payload.orderId ? served(a.orderId, added.tripId) : a)),
      };
      return body(desk.draft);
    }
    if (command.kind === "plan:Publish") {
      desk.published = { ...plan, status: "PUBLISHED", publishedAt: new Date().toISOString(), rowVersion: plan.rowVersion + 1 };
      desk.draft = null;
      desk.orders = desk.orders.map((o) => (plan.allocations.find((a) => a.orderId === o.orderId)?.decision === "SERVED" ? { ...o, status: "ALLOCATED" } : { ...o, status: "DEFERRED", deferralCount: 1 }));
      return body(desk.published);
    }
    return {};
  };

  await page.route("**/api/**", async (route: Route) => {
    const request = route.request();
    const url = new URL(request.url());
    const { pathname } = url;
    if (pathname === "/api/session") return route.fulfill(json(SESSION));
    if (pathname === "/api/reference/vehicles") return route.fulfill(json({ items: [vehicle("VEH043"), vehicle("VEH044")], nextCursor: null }));
    if (pathname === "/api/orders/day") return route.fulfill(json(desk.orders));
    if (pathname === "/api/plans/draft") return route.fulfill(desk.draft ? json(desk.draft) : problem(404, "NOT_FOUND", "No open draft"));
    if (pathname === "/api/plans/published") return route.fulfill(desk.published ? json(desk.published) : problem(404, "NOT_FOUND", "No published plan"));
    if (pathname === "/api/plans/preview/placements") return route.fulfill(json(PLACES));
    if (pathname === "/api/execution/run-sheets") return route.fulfill(json(desk.sheets));
    if (pathname === "/api/loading/trips") return route.fulfill(json(desk.dock));
    if (pathname === "/api/issues") return route.fulfill(json({ items: desk.issues.filter((i) => i.status === "OPEN" || i.status === "ASSIGNED"), nextCursor: null }));
    const one = /^\/api\/issues\/([^/]+)(\/history)?$/.exec(pathname);
    if (one) {
      const found = desk.issues.find((i) => i.issueId === one[1]);
      if (!found) return route.fulfill(problem(404, "NOT_FOUND", "No issue"));
      return route.fulfill(json(one[2] ? (desk.history[found.issueId] ?? []) : found));
    }
    if (pathname === "/api/plans/deferrals") return route.fulfill(json(desk.deferrals));
    if (pathname === "/api/plans/fuel") {
      return route.fulfill(json({ vehicleId: url.searchParams.get("vehicle"), weekStarting: "2027-03-01", quotaLitres: "200", usedLitres: "150", remainingLitres: "50" }));
    }
    if (pathname === "/api/sync") return route.fulfill(json({ results: [] }));
    if (pathname === "/api/commands" && request.method() === "POST") {
      const command = request.postDataJSON() as Sent;
      desk.commands.push(command);
      if (desk.refuse?.kind === command.kind) {
        const { status, code, detail, rules } = desk.refuse;
        desk.refuse = null;
        return route.fulfill(problem(status, code, detail, rules));
      }
      return route.fulfill(json({ commandId: command.commandId, kind: command.kind, replayed: false, result: apply(command) }));
    }
    return route.fulfill({ status: 404, body: "not mocked" });
  });
  return desk;
}

export function stop(sequence: number, extra: Partial<RunSheetStopView> = {}): RunSheetStopView {
  return {
    deliveryId: `delivery-${sequence}`, tripId: "trip-VEH043-1", sequence, orderId: `order-${sequence}`, outletId: `OUT0${50 + sequence}`, itemCount: 12,
    mallOutlet: false, plannedArrival: "09:30:00", windowOpen: "00:00:00", windowClose: "23:59:00", expectedArrival: null, startedAt: null, arrivedAt: null,
    completedAt: null, waitMinutes: null, lateMinutes: null, outcome: "PENDING", proofCaptured: false, rowVersion: 1, lines: [], ...extra,
  };
}

export function dockTrip(vehicleId: string, status: ReadyTripView["status"]): ReadyTripView {
  return {
    tripId: `dock-${vehicleId}`, vehicleId, tripNumber: 1, tripsForVehicle: 1, plannedDeparture: "03:30:00", status, brandCode: "Fresh", districtName: "Kandy",
    temperature: "chilled", dockCode: "D1", stopCount: 3, orderCount: 3, weightKg: "900", volumeM3: "9", holder: null, releasedAt: null, rowVersion: 1,
  };
}

export function issue(n: number, extra: Partial<IssueView> = {}): IssueView {
  return {
    issueId: `issue-${n}`, type: "OTHER", severity: "MEDIUM", status: "OPEN", depotCode: DEPOT, outletId: `OUT0${50 + n}`,
    subjects: [{ type: "order", id: `order-${n}` }], description: `Reported problem ${n}`, assignee: null, resolutionAction: null,
    resolutionNote: null, raisedBy: "loader-user", raisedAt: new Date(Date.now() - 25 * 60_000).toISOString(), resolvedAt: null, rowVersion: 1, ...extra,
  };
}

/** Issues' lifecycle as the server keeps it: each command bumps rowVersion and writes a history row. */
function applyIssue(desk: Desk, command: Sent): Record<string, unknown> {
  const { payload } = command;
  const index = desk.issues.findIndex((i) => i.issueId === payload.issueId);
  const before = desk.issues[index]!;
  const now = new Date().toISOString();
  const next: IssueView =
    command.kind === "issue:Assign"
      ? { ...before, status: "ASSIGNED", assignee: String(payload.assigneeUserId) }
      : command.kind === "issue:Resolve"
        ? { ...before, status: "RESOLVED", resolutionAction: String(payload.action).toUpperCase(), resolutionNote: String(payload.note), resolvedAt: now }
        : command.kind === "issue:ScheduleRedelivery"
          ? { ...before, status: "RESOLVED", resolutionAction: "REDELIVERY", resolutionNote: String(payload.note), resolvedAt: now }
          : command.kind === "issue:RecordReplacement"
            ? { ...before, status: "RESOLVED", resolutionAction: "REPLACEMENT", resolutionNote: String(payload.note), resolvedAt: now }
            : command.kind === "issue:Close"
              ? { ...before, status: "CLOSED" }
              : { ...before, status: "CANCELLED", resolutionNote: String(payload.reason) };
  next.rowVersion = before.rowVersion + 1;
  desk.issues[index] = next;
  const action = command.kind.replace("issue:", "").toLowerCase();
  (desk.history[next.issueId] ??= []).push({ from: before.status, to: next.status, action, reason: String(payload.note ?? payload.reason ?? ""), actorId: SESSION.userId, at: now });
  return { issueId: next.issueId, status: next.status, rowVersion: next.rowVersion };
}
