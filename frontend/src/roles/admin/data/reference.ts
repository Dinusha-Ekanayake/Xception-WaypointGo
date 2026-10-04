import { request, requestAll } from "@shared/api/client";
import type { Page } from "@shared/domain/common";
import { newCommand, send } from "@shared/api/commands";
import type { OutletDetailsView, UpdateOutletDetails } from "@shared/domain/types";

export async function createAdminDepot(payload: { code: string; name: string; timezone: string;
  latitude: string; longitude: string; locationPrecision: "exact" | "approximate" }) {
  return send<{ id: string; referenceVersionId: string }>(newCommand("reference:CreateDepot", payload));
}

export async function createAdminOutlet(payload: {
  outletId: string; brand: string; district: string; depotCode: string;
  dockType: string; parking: string; windowOpen: string; windowClose: string;
  mallOpen?: string; mallClose?: string;
}) {
  return send<{ id: string; referenceVersionId: string }>(newCommand("reference:CreateOutlet", payload));
}

export async function createAdminVehicle(payload: {
  vehicleId: string; depotCode: string; type: string; temperature: string;
  weightCapKg: string; volumeCapM3: string; fuelType: string;
  kmPerL: string; weeklyFuelQuotaL: string;
}) {
  return send<{ id: string; referenceVersionId: string }>(newCommand("reference:CreateVehicle", payload));
}

export type AdminDepot = {
  code: string;
  name: string;
};

export type AdminDepotDetail = {
  code: string;
  name: string;
  timezone: string;
  referenceVersionId: string;
  outletCount: number;
  vehicleCount: number;
  districtCount: number;
  rowVersion: number;
};

export type AdminOutlet = {
  outletId: string;
  brand: string;
  district: string;
  depot: string;
  dockType: string;
  parking: string;
  windowOpen: string;
  windowClose: string;
  mallOpen?: string;
  mallClose?: string;
  latitude: number | null;
  longitude: number | null;
};

export type AdminVehicle = {
  vehicleId: string;
  depot: string;
  type: string;
  temperature: string;
  weightCapKg: number;
  volumeCapM3: number;
  fuelType: string;
  kmPerL: number;
  weeklyFuelQuotaL: number;
  statusDate?: string;
  dayStatus?: string;
  rowVersion: number;
};

export async function updateAdminDepot(payload: { code: string; name: string; timezone: string }, expectedVersion: number) {
  return send<{ id: string; rowVersion: number }>(newCommand("reference:UpdateDepot", payload, expectedVersion));
}

export async function updateAdminVehicle(payload: {
  vehicleId: string; depotCode: string; type: string; temperature: string;
  weightCapKg: string; volumeCapM3: string; fuelType: string; kmPerL: string; weeklyFuelQuotaL: string;
}, expectedVersion: number) {
  return send<{ id: string; rowVersion: number }>(newCommand("reference:UpdateVehicle", payload, expectedVersion));
}

export async function fetchAdminDepots(options?: {
  signal?: AbortSignal;
}): Promise<AdminDepot[]> {
  return request<AdminDepot[]>("/api/admin/reference/depots", {
    signal: options?.signal,
  });
}

export async function fetchAdminDepotDetail(
  code: string,
  options?: { signal?: AbortSignal }
): Promise<AdminDepotDetail> {
  return request<AdminDepotDetail>(`/api/admin/reference/depots/${encodeURIComponent(code)}`, {
    signal: options?.signal,
  });
}

export async function fetchAdminOutlets(options: {
  depot?: string;
  brand?: string;
  district?: string;
  dockType?: string;
  search?: string;
  after?: string;
  limit?: number;
  signal?: AbortSignal;
} = {}): Promise<Page<AdminOutlet>> {
  const params = new URLSearchParams();
  if (options.depot && options.depot !== "all") params.set("depot", options.depot);
  if (options.brand && options.brand !== "all") params.set("brand", options.brand);
  if (options.district && options.district !== "all") params.set("district", options.district);
  if (options.dockType && options.dockType !== "all") params.set("dockType", options.dockType);
  if (options.search) params.set("search", options.search);
  if (options.after) params.set("after", options.after);
  if (options.limit) params.set("limit", String(options.limit));
  const qs = params.toString();

  const path = `/api/admin/outlets${qs ? `?${qs}` : ""}`;
  return options.after ? request<Page<AdminOutlet>>(path, { signal: options.signal })
    : { items: await requestAll<AdminOutlet>(path, { signal: options.signal }), nextCursor: null };
}

export async function fetchAdminOutlet(
  id: string,
  options?: { signal?: AbortSignal }
): Promise<AdminOutlet> {
  return request<AdminOutlet>(`/api/admin/outlets/${encodeURIComponent(id)}`, {
    signal: options?.signal,
  });
}

export function fetchAdminOutletDetails(id: string): Promise<OutletDetailsView> {
  return request<OutletDetailsView>(`/api/reference/outlets/${encodeURIComponent(id)}/details`);
}

export async function updateAdminOutletDetails(payload: UpdateOutletDetails, expectedVersion: number): Promise<void> {
  await send(newCommand("reference:UpdateOutletDetails", payload, expectedVersion));
}

export async function fetchAdminVehicles(options: {
  depot?: string;
  date?: string;
  type?: string;
  temperature?: string;
  status?: string;
  search?: string;
  after?: string;
  limit?: number;
  signal?: AbortSignal;
} = {}): Promise<Page<AdminVehicle>> {
  const params = new URLSearchParams();
  if (options.depot && options.depot !== "all") params.set("depot", options.depot);
  if (options.date) params.set("date", options.date);
  if (options.type && options.type !== "all") params.set("type", options.type);
  if (options.temperature && options.temperature !== "all") params.set("temperature", options.temperature);
  if (options.status && options.status !== "all") params.set("status", options.status);
  if (options.search) params.set("search", options.search);
  if (options.after) params.set("after", options.after);
  if (options.limit) params.set("limit", String(options.limit));
  const qs = params.toString();

  const path = `/api/admin/vehicles${qs ? `?${qs}` : ""}`;
  return options.after ? request<Page<AdminVehicle>>(path, { signal: options.signal })
    : { items: await requestAll<AdminVehicle>(path, { signal: options.signal }), nextCursor: null };
}

export async function fetchAdminVehicle(
  id: string,
  options?: { signal?: AbortSignal }
): Promise<AdminVehicle> {
  return request<AdminVehicle>(`/api/admin/vehicles/${encodeURIComponent(id)}`, {
    signal: options?.signal,
  });
}
