import { request } from "@shared/api/client";
import { ApiError } from "@shared/api/problem";
import { send, type Command, type CommandAck } from "@shared/api/commands";
import { enqueue, keep, kept, saveUpload } from "@shared/offline";
import type { DeliveryRecordView, FuelPassView, OutletView, PlanView, RunSheetView, VehicleView } from "@shared/domain/types";
import { depotToday } from "@shared/wording";
import { fuelPassKey, loadFuelPass, type FuelPassState } from "./fuelPass.ts";
import { plannedOutletIds } from "./prefetch.ts";
import { ASSIGNED_TTL_MS, DETAILS_TTL_MS, readCache } from "./readCache.ts";
import type { DayProbe } from "./run.ts";

// Everything the driver screens read and write, behind one seam. Reads come
// from Execution (#12) and Reference; writes are commands, and proof artifacts
// are uploads. No sample data: these screens show the real run or say there is
// none (D-D).

/** The day's working set, as it is kept on the phone for a day with no signal. */
export type RunData = {
  /** The vehicles this driver is assigned to today, with or without a released trip. */
  vehicles: VehicleView[];
  sheets: RunSheetView[];
  /** Every outlet on today's stops, for its dock, parking and district. */
  outlets: Record<string, OutletView>;
};

export type DriverGateway = {
  /** Reads the day from the server and keeps a copy on this phone. */
  run: (date: string, signal: AbortSignal) => Promise<RunData>;
  /** The copy kept on this phone, with when it was read. */
  keptRun: (date: string) => Promise<{ data: RunData; savedAt: Date } | null>;
  /** Replaces the kept copy, such as with a version the server just acknowledged. */
  keepRun: (date: string, data: RunData) => Promise<void>;
  send: (command: Command) => Promise<CommandAck>;
  /** Keep a write on this phone until it can be sent. */
  queue: (command: Command) => Promise<{ durable: boolean; reason?: string }>;
  /** Keep a proof artifact on this phone until it can be sent. */
  saveArtifact: (deliveryId: string, attachmentId: string, kind: "photo" | "signature", blob: Blob) => Promise<{ durable: boolean; reason?: string }>;
  /**
   * One day ahead, read lightly and not kept: whether this driver has a released
   * run, else the earliest published trip on one of their vehicles that the
   * loader has not released yet (plan:Read on the depot).
   */
  probeDay: (date: string, depot: string | null, signal: AbortSignal) => Promise<DayProbe>;
  /** The vehicle's Fuel Pass (FF-06b): from the server and kept on this phone, or the kept copy with no signal. */
  fuelPass: (vehicleId: string) => Promise<FuelPassState>;
  /** The outlets of this driver's trips on a day's published plan, for its map ahead of time (F-06). */
  plannedOutlets: (date: string, depot: string, signal: AbortSignal) => Promise<OutletView[]>;
  /** The stop's version on the server now, to redo a held write against it (F-06). */
  deliveryVersion: (deliveryId: string) => Promise<number>;
};

const q = encodeURIComponent;

export function attachmentPath(deliveryId: string, attachmentId: string, kind: "photo" | "signature"): string {
  return `/api/execution/deliveries/${q(deliveryId)}/attachments/${q(attachmentId)}?kind=${kind}`;
}

export function createGateway(accountId: string): DriverGateway {
  const key = (date: string) => `driver-run:${date}`;
  const cache = readCache();
  return {
    probeDay: async (date, depot, signal) => {
      const sheets = await request<RunSheetView[]>(`/api/execution/run-sheets?date=${date}`, { signal });
      if (sheets.some((sheet) => sheet.stops.length > 0)) return { released: true, waiting: null };
      if (!depot) return { released: false, waiting: null };
      const [vehicleIds, plan] = await Promise.all([
        request<string[]>(`/api/execution/vehicles?date=${date}`, { signal }),
        request<PlanView>(`/api/plans/published?depot=${q(depot)}&date=${date}`, { signal }).catch((failure: unknown) => {
          if (failure instanceof ApiError && failure.status === 404) return null;
          throw failure;
        }),
      ]);
      const mine = new Set(vehicleIds);
      const next = (plan?.trips ?? [])
        .filter((trip) => mine.has(trip.vehicleId))
        .sort((a, b) => a.plannedDeparture.localeCompare(b.plannedDeparture))[0];
      return { released: false, waiting: next ? { vehicleId: next.vehicleId, departure: next.plannedDeparture } : null };
    },
    run: async (date, signal) => {
      // Only the run sheet is read every pass; the rest is kept for its time to live (F-07).
      // The cached reads do not take the caller's signal, so one pass leaving early
      // does not cancel a read the next pass shares.
      const [vehicleIds, sheets] = await Promise.all([
        cache.get(`vehicles:${date}`, ASSIGNED_TTL_MS, () => request<string[]>(`/api/execution/vehicles?date=${date}`)),
        request<RunSheetView[]>(`/api/execution/run-sheets?date=${date}`, { signal }),
      ]);
      const outletIds = [...new Set(sheets.flatMap((sheet) => sheet.stops.map((stop) => stop.outletId)))];
      const [vehicles, outlets] = await Promise.all([
        Promise.all(vehicleIds.map((id) => cache.get(`vehicle:${id}`, DETAILS_TTL_MS, () => request<VehicleView>(`/api/reference/vehicles/${q(id)}`)))),
        Promise.all(outletIds.map((id) => cache.get(`outlet:${id}`, DETAILS_TTL_MS, () => request<OutletView>(`/api/reference/outlets/${q(id)}`)))),
      ]);
      const data: RunData = { vehicles, sheets, outlets: Object.fromEntries(outlets.map((o) => [o.outletId, o])) };
      await keep(accountId, key(date), data);
      return data;
    },
    keptRun: async (date) => {
      const snapshot = await kept<RunData>(accountId, key(date));
      return snapshot ? { data: snapshot.value, savedAt: new Date(snapshot.savedAt) } : null;
    },
    keepRun: async (date, data) => {
      await keep(accountId, key(date), data);
    },
    plannedOutlets: async (date, depot, signal) => {
      const [vehicleIds, plan] = await Promise.all([
        request<string[]>(`/api/execution/vehicles?date=${date}`, { signal }),
        request<PlanView>(`/api/plans/published?depot=${q(depot)}&date=${date}`, { signal }).catch((failure: unknown) => {
          if (failure instanceof ApiError && failure.status === 404) return null;
          throw failure;
        }),
      ]);
      return Promise.all(plannedOutletIds(vehicleIds, plan).map((id) => request<OutletView>(`/api/reference/outlets/${q(id)}`, { signal })));
    },
    deliveryVersion: async (deliveryId) =>
      (await request<DeliveryRecordView>(`/api/execution/deliveries/${q(deliveryId)}`)).rowVersion,
    fuelPass: (vehicleId) =>
      loadFuelPass({
        read: () => request<FuelPassView>(`/api/execution/vehicles/${q(vehicleId)}/fuel-pass`),
        kept: async () => (await kept<FuelPassView | null>(accountId, fuelPassKey(vehicleId)))?.value ?? null,
        keep: (pass) => keep(accountId, fuelPassKey(vehicleId), pass),
        today: depotToday(),
      }),
    send: (command) => send(command),
    queue: (command) => enqueue(accountId, "driver", command),
    saveArtifact: (deliveryId, attachmentId, kind, blob) =>
      saveUpload(accountId, {
        id: attachmentId,
        path: attachmentPath(deliveryId, attachmentId, kind),
        subject: deliveryId,
        contentType: blob.type || "application/octet-stream",
        blob,
      }),
  };
}
