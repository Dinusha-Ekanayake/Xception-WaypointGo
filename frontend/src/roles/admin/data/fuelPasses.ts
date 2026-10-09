import { putFile, request } from "../../../shared/api/client.ts";
import { newCommand, send } from "../../../shared/api/commands.ts";
import type { FuelPassStatusView } from "../../../shared/domain/execution.ts";

// Roadmap FF-06b: the vehicle's official National Fuel Pass QR. The government
// issues one per vehicle and enforces the quota at the pump; the administrator
// uploads the image here so the driver carries it on the phone. The image is
// never read back on this screen: it is the driver's to show.

export function fetchFuelPasses(signal?: AbortSignal) {
  return request<FuelPassStatusView[]>("/api/execution/fuel-passes", { signal });
}

/** A vehicle that never had a pass is not in the list: version 0, nothing current. */
export function statusFor(statuses: FuelPassStatusView[], vehicleId: string): FuelPassStatusView {
  return (
    statuses.find((s) => s.vehicleId === vehicleId) ?? {
      vehicleId,
      version: 0,
      passId: null,
      uploadedAt: null,
      sizeBytes: null,
      passRowVersion: null,
    }
  );
}

/** "Added · 14 KB" or "No Fuel Pass added"; the day is drawn by the caller in depot time. */
export function sizeText(bytes: number | null): string {
  if (bytes === null) return "";
  return bytes < 1024 ? `${bytes} bytes` : `${Math.round(bytes / 1024)} KB`;
}

export const FUEL_PASS_TYPES = ["image/png", "image/jpeg", "image/webp"] as const;
export const FUEL_PASS_MAX_BYTES = 512 * 1024;

/** Why a chosen file cannot be a pass, in words; null when it can be sent. */
export function fileProblem(file: Pick<File, "type" | "size">): string | null {
  if (!(FUEL_PASS_TYPES as readonly string[]).includes(file.type)) return "Choose a PNG, JPEG or WebP image of the QR.";
  if (file.size === 0) return "This file is empty.";
  if (file.size > FUEL_PASS_MAX_BYTES) return "This image is larger than 512 KB. Crop it to the QR and try again.";
  return null;
}

function newId(): string {
  return globalThis.crypto.randomUUID();
}

/** Uploads the image, replacing the current pass. A screen that was out of date is refused (version conflict). */
export async function uploadFuelPass(vehicleId: string, file: Blob, expectedVersion: number): Promise<void> {
  await putFile(`/api/execution/vehicles/${encodeURIComponent(vehicleId)}/fuel-pass/${newId()}?expectedVersion=${expectedVersion}`, file);
}

export function revokeFuelPass(status: FuelPassStatusView, reason: string) {
  return send(newCommand("vehicle:RevokeFuelPass", { vehicleId: status.vehicleId, passId: status.passId, reason }, status.passRowVersion));
}
