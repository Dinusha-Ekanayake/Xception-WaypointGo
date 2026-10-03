import { request } from "@shared/api/client";
import type { Page } from "@shared/domain/common";
import type { PlanStatus } from "@shared/domain/planning";
import type { DeliveryOutcome } from "@shared/domain/execution";

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
  weightKg: number;
  volumeM3: number;
  outcome?: DeliveryOutcome;
  arrivedAt?: string | null;
  completedAt?: string | null;
  delayMinutes?: number;
};

export type AdminTrip = {
  tripId: string;
  planId: string;
  vehicleId: string;
  vehicleType: "Van" | "Truck";
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
  weightCapKg: number;
  volumeM3: number;
  volumeCapM3: number;
  stops: AdminStop[];
  status: "PLANNED" | "LOADING" | "IN_TRANSIT" | "COMPLETED" | "DELAYED";
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
  fuelEstimatedLitres: number;
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

  return request<Page<AdminPlan>>(`/api/admin/plans${qs ? `?${qs}` : ""}`, {
    signal: options.signal,
  });
}

export async function fetchAdminPlanDetail(
  id: string,
  options?: { signal?: AbortSignal }
): Promise<AdminPlan> {
  const res = await request<AdminPlan | { plan: AdminPlan }>(`/api/admin/plans/${id}`, {
    signal: options?.signal,
  });
  if (res && "plan" in res && res.plan) {
    return res.plan;
  }
  return res as AdminPlan;
}
