import { request } from "@shared/api/client";
import { send, type Command, type CommandAck } from "@shared/api/commands";
import { enqueue, keep, kept, saveUpload } from "@shared/offline";
import type { OutletView, RunSheetView, VehicleView } from "@shared/domain/types";

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
};

const q = encodeURIComponent;

export function attachmentPath(deliveryId: string, attachmentId: string, kind: "photo" | "signature"): string {
  return `/api/execution/deliveries/${q(deliveryId)}/attachments/${q(attachmentId)}?kind=${kind}`;
}

export function createGateway(accountId: string): DriverGateway {
  const key = (date: string) => `driver-run:${date}`;
  return {
    run: async (date, signal) => {
      const [vehicleIds, sheets] = await Promise.all([
        request<string[]>(`/api/execution/vehicles?date=${date}`, { signal }),
        request<RunSheetView[]>(`/api/execution/run-sheets?date=${date}`, { signal }),
      ]);
      const outletIds = [...new Set(sheets.flatMap((sheet) => sheet.stops.map((stop) => stop.outletId)))];
      const [vehicles, outlets] = await Promise.all([
        Promise.all(vehicleIds.map((id) => request<VehicleView>(`/api/reference/vehicles/${q(id)}`, { signal }))),
        Promise.all(outletIds.map((id) => request<OutletView>(`/api/reference/outlets/${q(id)}`, { signal }))),
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
