import { request, requestAll } from "@shared/api/client";
import type { Page } from "@shared/domain/common";
import type { PlanStatus } from "@shared/domain/planning";
import type { DeliveryOutcome } from "@shared/domain/execution";
import type { RunSheetView, RunSheetStopView } from "@shared/domain/execution";
import type { PlanView } from "@shared/domain/planning";
import { fetchAdminVehicle, type AdminVehicle } from "./reference";

export type AdminStop = {
  sequence: number;
  orderId: string;
  orderRef: string;
  outletId: string;
  outletName: string;
  district: string;
  plannedArrival: string;
  windowOpen: string;
  windowClose: string;
  serviceMinutes: number;
  weightKg: number | null;
  volumeM3: number | null;
  outcome?: DeliveryOutcome;
  arrivedAt?: string | null;
  completedAt?: string | null;
  delayMinutes?: number;
};

export type AdminTrip = {
  tripId: string;
  planId: string;
  vehicleId: string;
  vehicleType: "Van" | "Truck" | "Unknown";
  driverId: string;
  driverName: string;
  driverPhone: string;
  tripNumber: 1 | 2;
  brand: "Fresh" | "Style" | "Tech";
  district: string;
  depot: string;
  temperature: "Ambient" | "Chilled (Refrigerated)";
  serviceDate: string;
  plannedDeparture: string;
  estimatedReturn: string;
  actualDeparture?: string | null;
  weightKg: number;
  weightCapKg: number | null;
  volumeM3: number;
  volumeCapM3: number | null;
  stops: AdminStop[];
  status: "PLANNED" | "LOADING" | "IN_TRANSIT" | "COMPLETED" | "DELAYED" | "UNKNOWN";
  currentStopSequence?: number;
  completedStopsCount?: number;
  totalStopsCount?: number;
};

export type AdminPlan = {
  planId: string;
  depot: string;
  serviceDate: string;
  planVersion: number;
  status: PlanStatus;
  publishedAt: string | null;
  referenceVersion: string;
  ruleSetVersion: string;
  priorityPolicyVersion: string;
  solverEngine: string;
  totalOrders: number;
  allocatedOrders: number;
  deferredOrders: number;
  totalWeightKg: number;
  totalVolumeM3: number;
  fuelEstimatedLitres: number | null;
  trips: AdminTrip[];
};

export async function fetchAdminPlans(options: {
  depot?: string;
  date?: string;
  status?: string;
  after?: string;
  limit?: number;
  signal?: AbortSignal;
} = {}): Promise<Page<AdminPlan>> {
  const params = new URLSearchParams();
  if (options.depot && options.depot !== "all") params.set("depot", options.depot);
  if (options.date && options.date !== "all") params.set("date", options.date);
  if (options.status && options.status !== "all") params.set("status", options.status);
  if (options.after) params.set("after", options.after);
  if (options.limit) params.set("limit", String(options.limit));
  const qs = params.toString();

  const path = `/api/admin/plans${qs ? `?${qs}` : ""}`;
  const page = options.after ? await request<Page<PlanView>>(path, {
    signal: options.signal,
  }) : { items: await requestAll<PlanView>(path, { signal: options.signal }), nextCursor: null };
  return { ...page, items: await enrichPlans(page.items, options.signal) };
}

export async function fetchAdminPlanDetail(
  id: string,
  options?: { signal?: AbortSignal }
): Promise<AdminPlan> {
  const plan = await request<PlanView>(`/api/plans/${encodeURIComponent(id)}`, {
    signal: options?.signal,
  });
  return (await enrichPlans([plan], options?.signal))[0];
}

function addMinutes(time: string, minutes: number): string {
  const [hour, minute] = time.split(":").map(Number);
  const total = (hour * 60 + minute + Math.round(minutes)) % (24 * 60);
  return `${String(Math.floor(total / 60)).padStart(2, "0")}:${String(total % 60).padStart(2, "0")}`;
}

function tripStatus(stops: RunSheetStopView[] | null): AdminTrip["status"] {
  if (stops === null) return "UNKNOWN";
  if (stops.length === 0) return "PLANNED";
  const finished = stops.filter((s) => ["DELIVERED", "PARTIAL", "FAILED", "SKIPPED"].includes(s.outcome));
  if (finished.length === stops.length) return "COMPLETED";
  if (stops.some((s) => (s.lateMinutes ?? 0) > 0)) return "DELAYED";
  if (stops.some((s) => s.startedAt || s.arrivedAt || s.completedAt)) return "IN_TRANSIT";
  return "LOADING";
}

async function enrichPlans(plans: PlanView[], signal?: AbortSignal): Promise<AdminPlan[]> {
  const vehicleIds = [...new Set(plans.flatMap((p) => p.trips.map((t) => t.vehicleId)))];
  const vehicles = new Map<string, AdminVehicle>();
  await Promise.all(vehicleIds.map(async (id) => {
    try { vehicles.set(id, await fetchAdminVehicle(id, { signal })); } catch { /* Show unavailable capacity. */ }
  }));
  const runs = new Map<string, RunSheetView[] | null>();
  const depotDays = [...new Set(plans.map((p) => `${p.depotCode}|${p.serviceDate}`))];
  await Promise.all(depotDays.map(async (key) => {
    const [depot, date] = key.split("|");
    try {
      runs.set(key, await request<RunSheetView[]>(`/api/execution/run-sheets?depot=${encodeURIComponent(depot)}&date=${date}`, { signal }));
    } catch { runs.set(key, null); }
  }));
  return plans.map((plan) => {
    const sheets = runs.get(`${plan.depotCode}|${plan.serviceDate}`) ?? null;
    const trips: AdminTrip[] = plan.trips.map((trip) => {
      const vehicle = vehicles.get(trip.vehicleId);
      const actual = sheets?.flatMap((s) => s.stops).filter((s) => s.tripId === trip.tripId) ?? null;
      const byOrder = new Map(actual?.map((s) => [s.orderId, s]));
      return {
        tripId: trip.tripId, planId: plan.planId, vehicleId: trip.vehicleId,
        vehicleType: vehicle?.type === "truck" ? "Truck" : vehicle?.type === "van" ? "Van" : "Unknown",
        driverId: "", driverName: "Driver unavailable", driverPhone: "", tripNumber: trip.tripNumber,
        brand: trip.brandCode as AdminTrip["brand"], district: trip.districtName,
        depot: plan.depotCode, temperature: trip.temperature === "chilled" ? "Chilled (Refrigerated)" : "Ambient",
        serviceDate: plan.serviceDate, plannedDeparture: trip.plannedDeparture,
        estimatedReturn: addMinutes(trip.plannedDeparture, Number(trip.plannedMinutes)),
        weightKg: Number(trip.weightKg), weightCapKg: vehicle ? Number(vehicle.weightCapKg) : null,
        volumeM3: Number(trip.volumeM3), volumeCapM3: vehicle ? Number(vehicle.volumeCapM3) : null,
        status: tripStatus(actual), completedStopsCount: actual?.filter((s) => s.completedAt).length,
        totalStopsCount: trip.stops.length,
        stops: trip.stops.map((stop) => {
          const observed = byOrder.get(stop.orderId);
          return {
            sequence: stop.sequence, orderId: stop.orderId, orderRef: stop.orderId,
            outletId: stop.outletId, outletName: stop.outletId, district: trip.districtName,
            plannedArrival: stop.plannedArrival, windowOpen: stop.windowOpen, windowClose: stop.windowClose,
            serviceMinutes: Number(stop.serviceMinutes), weightKg: null, volumeM3: null,
            outcome: observed?.outcome, arrivedAt: observed?.arrivedAt, completedAt: observed?.completedAt,
            delayMinutes: observed?.lateMinutes ?? undefined,
          };
        }),
      };
    });
    return {
      planId: plan.planId, depot: plan.depotCode, serviceDate: plan.serviceDate,
      planVersion: plan.planVersion, status: plan.status, publishedAt: plan.publishedAt,
      referenceVersion: plan.referenceVersionId, ruleSetVersion: plan.ruleSetVersionId,
      priorityPolicyVersion: plan.priorityPolicyVersionId, solverEngine: plan.engine,
      totalOrders: plan.allocations.length,
      allocatedOrders: plan.allocations.filter((a) => a.decision === "SERVED").length,
      deferredOrders: plan.allocations.filter((a) => a.decision === "DEFERRED").length,
      totalWeightKg: trips.reduce((sum, t) => sum + t.weightKg, 0),
      totalVolumeM3: trips.reduce((sum, t) => sum + t.volumeM3, 0),
      fuelEstimatedLitres: null, trips,
    };
  });
}
