import type { Page, Route } from "@playwright/test";
import type { PostMessagePayload } from "../../src/shared/domain/messaging.ts";
import { postToThread, resolveInThread, threadRead, type ThreadMock } from "../thread-mocks.ts";
import type { RunSheetStopView, RunSheetView } from "../../src/shared/domain/execution.ts";
import type { ReadyTripView } from "../../src/shared/domain/loading.ts";
import type { OrderStatus, OrderView } from "../../src/shared/domain/ordering.ts";
import type { IssueHistoryView, IssueView } from "../../src/shared/domain/issues.ts";
import type { AllocationView, ComparisonView, DeferralView, PlacementView, PlanView, SnapshotView, TripPreview, TripView } from "../../src/shared/domain/planning.ts";
import type { ForecastOverviewView, ForecastWeekView } from "../../src/shared/domain/intelligence.ts";

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

const ENGINE_DECISION = { source: "ENGINE", locked: false, decidedBy: null, decidedAt: null, lastServedOn: null } as const;
const served = (orderId: string, tripId: string): AllocationView => ({ orderId, decision: "SERVED", tripId, bindingRule: null, reason: "placed", checks: [], ...ENGINE_DECISION });
const deferred = (orderId: string): AllocationView => ({
  orderId, decision: "DEFERRED", tripId: null, bindingRule: "R-PLN-06", reason: "No refrigerated vehicle has 7.9 m³ free",
  checks: [
    { ruleId: "R-PLN-02", passed: true, reason: "chilled on a refrigerated vehicle", slack: null },
    { ruleId: "R-PLN-06", passed: false, reason: "volume 7.9 m³ exceeds 2.5 m³ free", slack: "-5.4" },
  ],
  ...ENGINE_DECISION,
});

/** Orders 1 and 2 on VEH043 trip 1; order 3 deferred for volume. */
export function draftPlan(version = 1, decided = false): PlanView {
  const t = trip("VEH043", 1, ["order-1", "order-2"]);
  const left = decided ? { ...deferred("order-3"), source: "KEPT" as const, decidedBy: "dispatcher-user", decidedAt: "2027-02-28T16:30:00Z" } : deferred("order-3");
  return {
    planId: `plan-v${version}`, depotCode: DEPOT, serviceDate: "2027-03-01", planVersion: version, status: "DRAFT", referenceVersionId: "ref",
    ruleSetVersionId: "rules", priorityPolicyVersionId: "policy", supersedes: null, publishedAt: null, savedAt: "2027-02-28T16:41:00Z", plannedWithoutPredictor: true,
    trips: [t], allocations: [served("order-1", t.tripId), served("order-2", t.tripId), left], rowVersion: 1,
    engine: "priority-insertion-v1+scarce-replan-v1",
    improvement: { firstPassServed: 1, firstPassDeferred: 2, served: 2, deferred: 1, improved: true, chilledVolumeGainedM3: "7.9", stoppedBy: "NONE", chilledCandidates: 2, chilledSearched: 2 },
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
  /** Queued generations (R-PLN-41), each done with the draft it wrote. */
  jobs: Array<{ jobId: string; result: ReturnType<typeof body> }>;
  /** Trip threads (issue #136). */
  threads?: ThreadMock[];
  orders: OrderView[];
  /** Orders off the day's list but readable by id (moved to another day). */
  elsewhere?: OrderView[];
  /** Extra differences the comparison names beside order 3's. */
  compareExtra?: ComparisonView["changes"];
  draft: PlanView | null;
  published: PlanView | null;
  sheets: RunSheetView[];
  /** Live map (issue #161): last fixes and the selected trip's trail. */
  positions?: unknown[];
  trail?: unknown[];
  /** The position stream answers but never speaks, as when the push is down. */
  streamSilent?: boolean;
  dock: ReadyTripView[];
  issues: IssueView[];
  history: Record<string, IssueHistoryView[]>;
  deferrals: DeferralView[];
  commands: Sent[];
  /** Answer the next command of this kind with this problem instead of applying it. */
  refuse: { kind: string; status: number; code: string; detail: string; rules?: string[] } | null;
  /** What /api/ml/models answers: the registry, for the forecast error chip (#119). */
  models?: unknown[];
  /** What /api/ml/plans/{id}/predictions answers for the published plan; none means not scored (#119). */
  predictions?: unknown;
  /** What /api/ml/forecast/overview answers for the depot. */
  forecast: ForecastOverviewView;
  /** Saved plans, newest first, and the plan each one holds. */
  snapshots: SnapshotView[];
  savedPlans: Record<string, PlanView>;
  /** The trip a swap or a stop order would leave; the preview routes answer with it. */
  preview: TripPreview;
};

export const FEASIBLE_PREVIEW: TripPreview = {
  vehicleId: "VEH043", tripNumber: 1, feasible: true,
  stops: [{ sequence: 1, orderId: "order-3", outletId: "OUT053", plannedArrival: "04:10:00", windowOpen: "03:00:00", windowClose: "08:00:00", serviceMinutes: "20" }],
  checks: [{ ruleId: "R-PLN-06", passed: true, reason: "fits", slack: "1.2" }],
};

export function snapshotOf(number: number, label: string, kind: SnapshotView["kind"] = "MANUAL"): SnapshotView {
  return {
    snapshotId: `snap-${number}`, depotCode: DEPOT, serviceDate: "2027-03-01", number, label, kind, sourcePlanId: "plan-v1", planVersion: 1,
    createdBy: "dispatcher-user", createdAt: "2027-02-28T11:10:00Z",
  };
}

const body = (plan: PlanView) => ({
  planId: plan.planId, planVersion: plan.planVersion, status: plan.status, rowVersion: plan.rowVersion,
  served: plan.allocations.filter((a) => a.decision === "SERVED").length,
  deferred: plan.allocations.filter((a) => a.decision === "DEFERRED").length,
  unservable: 0, partial: false,
});

export async function serve(page: Page, start: Partial<Desk> = {}): Promise<Desk> {
  const desk: Desk = {
    orders: [order(1, "CONFIRMED"), order(2, "CONFIRMED"), order(3, "CONFIRMED")],
    draft: null, published: null, sheets: [], dock: [], issues: [], history: {}, deferrals: [], commands: [], refuse: null,
    forecast: forecast(), snapshots: [], savedPlans: {}, preview: FEASIBLE_PREVIEW, jobs: [], ...start,
  };
  const json = (value: unknown) => ({ status: 200, contentType: "application/json", body: JSON.stringify(value) });
  const problem = (status: number, code: string, detail: string, rules: string[] = []) => ({
    status, contentType: "application/problem+json",
    body: JSON.stringify({ type: "about:blank", title: code, status, detail, code, correlationId: "test", violations: rules.map((rule) => ({ rule, message: "" })) }),
  });

  const apply = (command: Sent) => {
    const { payload } = command;
    if (command.kind.startsWith("issue:")) return applyIssue(desk, command);
    if (command.kind === "message:Resolve") {
      return resolveInThread(desk.threads ?? [], payload as unknown as { messageId: string; note?: string }, SESSION.displayName, new Date().toISOString());
    }
    if (command.kind === "message:Post") {
      return postToThread(desk.threads ?? [], payload as unknown as PostMessagePayload, { name: SESSION.displayName, role: "dispatcher" }, new Date().toISOString());
    }
    if (command.kind === "order:CloseForDay") return { alreadyClosed: false };
    if (command.kind === "plan:Generate") {
      // Planning v2 (R-PLN-41): Generate queues a job; the worker writes the draft and the screen follows the job.
      desk.draft = draftPlan((desk.draft?.planVersion ?? 0) + 1);
      const jobId = `job-${desk.jobs.length + 1}`;
      desk.jobs.push({ jobId, result: body(desk.draft) });
      return { jobId, status: "QUEUED", depotCode: DEPOT, serviceDate: "2027-03-01" };
    }
    const plan = desk.draft!;
    if (command.kind === "plan:Override") {
      const added = trip(String(payload.vehicleId), payload.tripNumber as 1 | 2, [String(payload.orderId)]);
      desk.draft = {
        ...plan, planId: `plan-v${plan.planVersion + 1}`, planVersion: plan.planVersion + 1, trips: [...plan.trips, added],
        allocations: plan.allocations.map((a) => (a.orderId === payload.orderId ? { ...served(a.orderId, added.tripId), source: "OVERRIDE" as const, decidedBy: "dispatcher-user", decidedAt: "2027-02-28T16:30:00Z" } : a)),
      };
      return body(desk.draft);
    }
    const next = (changes: (a: AllocationView) => AllocationView, only?: string[]) => {
      desk.draft = {
        ...plan, planId: `plan-v${plan.planVersion + 1}`, planVersion: plan.planVersion + 1,
        allocations: plan.allocations.map((a) => (!only || only.includes(a.orderId) ? changes(a) : a)),
      };
      return body(desk.draft);
    };
    const hand = { decidedBy: "dispatcher-user", decidedAt: "2027-02-28T16:30:00Z" };
    if (command.kind === "plan:KeepDeferred") return next((a) => ({ ...a, source: "KEPT", ...hand }), payload.orderIds as string[]);
    if (command.kind === "plan:Lock") return next((a) => ({ ...a, locked: true, ...hand }), [String(payload.orderId)]);
    if (command.kind === "plan:Unlock") return next((a) => ({ ...a, locked: false }), [String(payload.orderId)]);
    if (command.kind === "plan:Swap") {
      const onTrip = plan.allocations.find((a) => a.orderId === payload.outOrderId)!.tripId!;
      return next(
        (a) => (a.orderId === payload.outOrderId ? { ...deferred(a.orderId), source: "MANUAL_DEFER", ...hand } : { ...served(a.orderId, onTrip), source: "SWAP", ...hand }),
        [String(payload.outOrderId), String(payload.inOrderId)],
      );
    }
    if (command.kind === "plan:ReorderStops") return next((a) => a);
    if (command.kind === "plan:EditTrip") {
      // The trip holds exactly orderIds (none removes it): orders left out are deferred, orders named join it.
      const tripId = String(payload.tripId);
      const ids = payload.orderIds as string[];
      const before = plan.trips.find((t) => t.tripId === tripId);
      const kept = ids.length ? [{ ...before!, stops: ids.map((orderId, i) => ({ ...(before!.stops.find((st) => st.orderId === orderId) ?? before!.stops[0]!), sequence: i + 1, orderId })) }] : [];
      desk.draft = {
        ...plan, planId: `plan-v${plan.planVersion + 1}`, planVersion: plan.planVersion + 1,
        trips: [...plan.trips.filter((t) => t.tripId !== tripId), ...kept],
        allocations: plan.allocations.map((a) =>
          ids.includes(a.orderId) ? { ...served(a.orderId, tripId), source: a.tripId === tripId ? a.source : ("OVERRIDE" as const), ...hand }
          : a.tripId === tripId ? { ...deferred(a.orderId), source: "MANUAL_DEFER" as const, ...hand } : a,
        ),
      };
      return body(desk.draft);
    }
    if (command.kind === "plan:ContactStore") return { orderId: payload.orderId, outletId: "OUT053" };
    if (command.kind === "plan:SaveSnapshot") {
      const saved = snapshotOf(desk.snapshots.length + 1, String(payload.label ?? `Snapshot ${desk.snapshots.length + 1}`));
      desk.snapshots = [saved, ...desk.snapshots];
      desk.savedPlans[saved.snapshotId] = plan;
      return { snapshotId: saved.snapshotId, number: saved.number, label: saved.label };
    }
    if (command.kind === "plan:RestoreSnapshot") {
      const held = desk.savedPlans[String(payload.snapshotId)] ?? plan;
      desk.draft = { ...held, planId: `plan-v${plan.planVersion + 1}`, planVersion: plan.planVersion + 1, rowVersion: 1 };
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
    const byId = /^\/api\/orders\/(order-[^/]+)$/.exec(pathname);
    if (byId) {
      const found = [...desk.orders, ...(desk.elsewhere ?? [])].find((o) => o.orderId === byId[1]);
      return route.fulfill(found ? json(found) : problem(404, "NOT_FOUND", "No order"));
    }
    if (pathname === "/api/plans/draft") return route.fulfill(desk.draft ? json(desk.draft) : problem(404, "NOT_FOUND", "No open draft"));
    if (pathname.startsWith("/api/plans/jobs")) {
      const id = pathname.split("/")[4];
      const job = id ? desk.jobs.find((j) => j.jobId === id) : desk.jobs.at(-1);
      if (!job) return route.fulfill(problem(404, "NOT_FOUND", "No plan generation"));
      return route.fulfill(json({
        jobId: job.jobId, depotCode: DEPOT, serviceDate: "2027-03-01", status: "DONE", attempts: 1, planId: job.result.planId,
        error: null, createdAt: "2027-02-28T10:00:00Z", startedAt: "2027-02-28T10:00:00Z", finishedAt: "2027-02-28T10:00:01Z",
        result: job.result,
      }));
    }
    if (pathname === "/api/plans/published") return route.fulfill(desk.published ? json(desk.published) : problem(404, "NOT_FOUND", "No published plan"));
    if (pathname === "/api/plans/preview/placements") return route.fulfill(json(PLACES));
    if (pathname === "/api/plans/preview/swap" || pathname === "/api/plans/preview/sequence" || pathname === "/api/plans/preview/trip") return route.fulfill(json(desk.preview));
    if (pathname === "/api/plans/snapshots") return route.fulfill(json(desk.snapshots));
    const saved = /^\/api\/plans\/snapshots\/([^/]+)$/.exec(pathname);
    if (saved) {
      const header = desk.snapshots.find((x) => x.snapshotId === saved[1]);
      return route.fulfill(header ? json({ snapshot: header, plan: desk.savedPlans[header.snapshotId] }) : problem(404, "NOT_FOUND", "No saved plan"));
    }
    if (pathname === "/api/plans/compare") {
      const side = (id: string, label: string) => {
        const held = desk.savedPlans[id] ?? desk.draft ?? desk.published!;
        return { label, planId: held.planId, planVersion: held.planVersion, served: held.allocations.filter((a) => a.decision === "SERVED").length, deferred: held.allocations.filter((a) => a.decision === "DEFERRED").length, unservable: 0, trips: held.trips.length, vehicles: 1 };
      };
      const view: ComparisonView = {
        a: side(String(url.searchParams.get("a")), "Plan A"), b: side(String(url.searchParams.get("b")), "Plan B"),
        changes: [{ orderId: "order-3", outletId: "OUT053", kind: "ADDED", before: { decision: "DEFERRED", vehicleId: null, tripNumber: null }, after: { decision: "SERVED", vehicleId: "VEH044", tripNumber: 1 } }, ...(desk.compareExtra ?? [])],
        changedTrips: ["trip-VEH044-1"], removedTrips: [], affectedOutlets: ["OUT053"],
      };
      return route.fulfill(json(view));
    }
    if (pathname.startsWith("/api/reference/calendar/")) {
      return route.fulfill(json({ date: pathname.split("/").pop(), operating: true, nextOperatingDay: "2027-03-02", known: true, day: {} }));
    }
    if (/^\/api\/ml\/plans\/[^/]+\/predictions$/.test(pathname)) return route.fulfill(desk.predictions ? json(desk.predictions) : problem(404, "NOT_FOUND", "Not scored"));
    if (pathname === "/api/execution/run-sheets") return route.fulfill(json(desk.sheets));
    if (pathname === "/api/execution/positions") return route.fulfill(json(desk.positions ?? []));
    // The push (R-EXE-23): one event per connection; the browser reconnects and hears the next.
    if (pathname === "/api/execution/positions/stream") {
      if (desk.streamSilent) return route.fulfill({ status: 200, contentType: "text/event-stream", body: ": quiet\n\n" });
      return route.fulfill({ status: 200, contentType: "text/event-stream", body: `retry: 1000\nevent: positions\ndata: ${JSON.stringify(desk.positions ?? [])}\n\n` });
    }
    if (pathname.startsWith("/api/execution/deliveries/")) {
      return route.fulfill(json({ deliveryId: pathname.split("/").pop(), driver: { displayName: "Dilan R.", employeeCode: "DRV-00133" } }));
    }
    if (pathname.startsWith("/api/execution/trips/")) {
      // Two pages, as the keyset API answers a long trip: the map must read past the first.
      const trail = desk.trail ?? [];
      // A live map asks only for what came after the newest point it holds.
      const since = url.searchParams.get("since");
      if (since) return route.fulfill(json({ items: (trail as { recordedAt: string }[]).filter((p) => p.recordedAt > since), nextCursor: null }));
      if (url.searchParams.get("cursor")) return route.fulfill(json({ items: trail.slice(1), nextCursor: null }));
      return route.fulfill(json({ items: trail.slice(0, 1), nextCursor: trail.length > 1 ? "page-2" : null }));
    }
    if (pathname.startsWith("/api/reference/depots/")) {
      const code = decodeURIComponent(pathname.split("/").pop() ?? "");
      return route.fulfill(json({ depotCode: code, displayName: code, location: { latitude: "6.960000", longitude: "79.880000", precision: "approximate" } }));
    }
    if (pathname === "/api/loading/trips") return route.fulfill(json(desk.dock));
    if (pathname === "/api/issues") return route.fulfill(json({ items: desk.issues.filter((i) => i.status === "OPEN" || i.status === "ASSIGNED"), nextCursor: null }));
    const one = /^\/api\/issues\/([^/]+)(\/history)?$/.exec(pathname);
    if (one) {
      const found = desk.issues.find((i) => i.issueId === one[1]);
      if (!found) return route.fulfill(problem(404, "NOT_FOUND", "No issue"));
      return route.fulfill(json(one[2] ? (desk.history[found.issueId] ?? []) : found));
    }
    if (pathname === "/api/plans/deferrals") return route.fulfill(json(desk.deferrals));
    if (pathname === "/api/ml/models") {
      return route.fulfill(json(desk.models ?? [
        { name: "datathon-task2a", version: "2026.1", kind: "demand_forecast", status: "ACTIVE", metrics: { total_wape: "0.0415", chilled_wape: "0.0439" } },
      ]));
    }
    if (pathname === "/api/ml/forecast/overview") {
      const weeks = Number(url.searchParams.get("weeks") ?? "10");
      return route.fulfill(json({ ...desk.forecast, weeks: desk.forecast.weeks.slice(0, weeks) }));
    }
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
    const thread = request.method() === "GET" ? threadRead(desk.threads ?? [], url) : undefined;
    if (thread) return route.fulfill({ status: thread.status, contentType: thread.status === 200 ? "application/json" : "application/problem+json", body: JSON.stringify(thread.body) });
    return route.fulfill({ status: 404, body: "not mocked" });
  });
  return desk;
}

export function stop(sequence: number, extra: Partial<RunSheetStopView> = {}): RunSheetStopView {
  return {
    deliveryId: `delivery-${sequence}`, tripId: "trip-VEH043-1", sequence, orderId: `order-${sequence}`, outletId: `OUT0${50 + sequence}`, itemCount: 12,
    mallOutlet: false, plannedArrival: "09:30:00", windowOpen: "00:00:00", windowClose: "23:59:00", expectedArrival: null, startedAt: null, arrivedAt: null,
    completedAt: null, waitMinutes: null, lateMinutes: null, outcome: "PENDING", deliveredUnits: null, proofCaptured: false, rowVersion: 1, lines: [], ...extra,
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

/**
 * Ten forecast weeks for the depot: a festival peak in week 2 that fills the
 * refrigerated fleet, and a short holiday week 3. Capacity is two trucks of
 * 33.4 m3, one of them a reefer.
 */
export function forecast(over: Partial<ForecastOverviewView> = {}): ForecastOverviewView {
  const weeks: ForecastWeekView[] = Array.from({ length: 10 }, (_, i) => {
    const start = new Date(Date.UTC(2027, 2, 1 + 7 * i)).toISOString().slice(0, 10);
    const peak = i === 1;
    const short = i === 2;
    const freshTotal = peak ? 520 : short ? 260 : 400;
    const freshChilled = peak ? 395 : short ? 90 : 130;
    const days = short ? 3 : 6;
    return {
      isoYear: 2027,
      isoWeek: 9 + i,
      weekStart: start,
      operatingDays: days,
      holidayDays: short ? 3 : 0,
      paydays: i === 3 ? 1 : 0,
      festival: peak ? "Poson" : null,
      generatedDays: 0,
      brands: [
        { brandCode: "Fresh", totalM3: String(freshTotal), chilledM3: String(freshChilled) },
        { brandCode: "Style", totalM3: "80", chilledM3: "0" },
        { brandCode: "Tech", totalM3: "12", chilledM3: "0" },
      ],
      totalM3: String(freshTotal + 92),
      chilledM3: String(freshChilled),
      capacity: { vehicles: 2, refrigeratedVehicles: 1, fleetM3: String(2 * 33.4 * 2 * days), refrigeratedM3: String(33.4 * 2 * days) },
    };
  });
  return {
    depotCode: DEPOT,
    status: "READY",
    modelLabel: "datathon-task2a@2026.1",
    degraded: false,
    generatedAt: "2027-02-22T04:00:00Z",
    // Monday 1 March, 00:00 in Colombo.
    nextRunAt: "2027-02-28T18:30:00Z",
    weeks,
    ...over,
  };
}

/**
 * A Monday afternoon on the road, as the Figma "05 Live" frames show it: the
 * clock is 16:12 in Colombo (10:42 UTC). Two vehicles may miss a window, one has
 * gone quiet, two are on time, one is returning; one stop failed, one owes its
 * proof, and a store reported a missing unit.
 */
export const LIVE_NOW = "2027-03-01T10:42:00Z";

export function liveDay(): Partial<Desk> {
  const at = (utc: string) => `2027-03-01T${utc}:00Z`;
  const s = (vehicle: string, seq: number, outlet: string, extra: Partial<RunSheetStopView> = {}): RunSheetStopView =>
    stop(seq, { deliveryId: `d-${vehicle}-${seq}`, tripId: `t-${vehicle}`, outletId: outlet, windowOpen: "09:00:00", windowClose: "17:00:00", plannedArrival: "16:30:00", ...extra });
  const done = (utc: string) => ({ outcome: "DELIVERED" as const, proofCaptured: true, arrivedAt: at(utc), completedAt: at(utc), startedAt: at("08:50") });
  const sheets: RunSheetView[] = [
    { vehicleId: "VEH020", serviceDate: "2027-03-01", stops: [s("VEH020", 1, "OUT061", done("09:40")), s("VEH020", 2, "OUT063", { expectedArrival: at("11:36"), startedAt: at("08:50") })] },
    { vehicleId: "VEH019", serviceDate: "2027-03-01", stops: [s("VEH019", 1, "OUT041", done("09:20")), s("VEH019", 2, "OUT044", { expectedArrival: at("11:40"), startedAt: at("08:50") })] },
    { vehicleId: "VEH029", serviceDate: "2027-03-01", stops: [s("VEH029", 1, "OUT070", done("09:30")), s("VEH029", 2, "OUT072", { expectedArrival: at("11:00"), startedAt: at("08:50") })] },
    { vehicleId: "VEH030", serviceDate: "2027-03-01", stops: [s("VEH030", 1, "OUT010", { ...done("09:10"), outcome: "FAILED", proofCaptured: false }), s("VEH030", 2, "OUT012", { expectedArrival: at("10:55"), startedAt: at("08:50") })] },
    { vehicleId: "VEH023", serviceDate: "2027-03-01", stops: [s("VEH023", 1, "OUT030", { ...done("09:50"), proofCaptured: false }), s("VEH023", 2, "OUT031", { expectedArrival: at("11:01"), startedAt: at("08:50") })] },
    { vehicleId: "VEH011", serviceDate: "2027-03-01", stops: [s("VEH011", 1, "OUT080", done("09:15")), s("VEH011", 2, "OUT081", done("10:20"))] },
  ];
  const routes: Record<string, [string, string]> = { VEH020: ["Style", "Matara"], VEH019: ["Style", "Galle"], VEH029: ["Tech", "Kurunegala"], VEH030: ["Tech", "Colombo"], VEH023: ["Style", "Kalutara"], VEH011: ["Style", "Kurunegala"] };
  const dock: ReadyTripView[] = Object.entries(routes).map(([vehicleId, [brand, district]]) => ({
    ...dockTrip(vehicleId, "COMPLETED"), tripId: `t-${vehicleId}`, tripNumber: 2, tripsForVehicle: 2, brandCode: brand, districtName: district,
    volumeM3: "7.5", volumeCapM3: "34", releasedAt: at("08:50"),
  }));
  const fix = (vehicleId: string, lat: string, lon: string, offline = false, utc = "10:40") => ({
    vehicleId, tripId: `t-${vehicleId}`, latitude: lat, longitude: lon, headingDeg: "90.0", accuracyM: "12.0", recordedAt: at(utc), offline,
  });
  const positions = [fix("VEH020", "6.020000", "80.400000"), fix("VEH019", "6.100000", "80.150000"), fix("VEH029", "7.480000", "80.300000", true, "10:30"), fix("VEH030", "6.900000", "79.860000"), fix("VEH023", "6.580000", "79.960000")];
  const issues: IssueView[] = [issue(85, { type: "STOCK_DISCREPANCY", severity: "HIGH", outletId: "OUT085", description: "1 unit missing", raisedAt: at("10:32") })];
  return { sheets, dock, positions, issues };
}
