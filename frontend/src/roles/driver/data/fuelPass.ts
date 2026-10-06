import type { FuelPassView } from "../../../shared/domain/execution.ts";

// Roadmap FF-06b: the vehicle's official National Fuel Pass on the phone, so the
// driver needs no paper copy at the pump. The government sets and enforces the
// quota; the phone only carries the image the depot uploaded.
//
// The image is a bearer credential, so the phone keeps it only to the end of its
// last valid day, drops it the moment the server says the driver may no longer
// have it, and never caches it anywhere else (not in the service worker).

export type FuelPassState =
  /** The pass to show; `fromDevice` when read from the phone with no signal. */
  | { kind: "ready"; pass: FuelPassView; fromDevice: boolean }
  /** The depot has not added a pass for this vehicle, or withdrew it. */
  | { kind: "none" }
  /** The driver is not assigned to this vehicle today or tomorrow. */
  | { kind: "refused" }
  /** No signal and no valid copy on the phone. */
  | { kind: "unavailable" };

export const fuelPassKey = (vehicleId: string): string => `fuel-pass:${vehicleId}`;

/** The pass may be shown up to the end of its `validUntil` day; both are yyyy-mm-dd depot dates. */
export function stillValid(pass: Pick<FuelPassView, "validUntil">, today: string): boolean {
  return pass.validUntil >= today;
}

export type FuelPassDeps = {
  read: () => Promise<FuelPassView>;
  kept: () => Promise<FuelPassView | null>;
  /** Replaces the phone's copy; null deletes it. */
  keep: (pass: FuelPassView | null) => Promise<unknown>;
  /** Today in depot time, yyyy-mm-dd. */
  today: string;
};

const statusOf = (error: unknown): number | undefined =>
  typeof error === "object" && error !== null && "status" in error && typeof error.status === "number" ? error.status : undefined;

/**
 * The server's answer when it gives one, kept on the phone. A refusal or "no
 * pass" deletes the kept copy, so a withdrawn pass is not shown again. With no
 * answer, the kept copy stands in only while it is still valid.
 */
export async function loadFuelPass(deps: FuelPassDeps): Promise<FuelPassState> {
  try {
    const pass = await deps.read();
    await deps.keep(pass).catch(() => undefined);
    return { kind: "ready", pass, fromDevice: false };
  } catch (error) {
    const status = statusOf(error);
    if (status === 404 || status === 403) {
      await deps.keep(null).catch(() => undefined);
      return { kind: status === 404 ? "none" : "refused" };
    }
    const kept = await deps.kept().catch(() => null);
    if (kept && kept.vehicleId && stillValid(kept, deps.today)) return { kind: "ready", pass: kept, fromDevice: true };
    if (kept) await deps.keep(null).catch(() => undefined);
    return { kind: "unavailable" };
  }
}

/** The image as the browser draws it. */
export const fuelPassSrc = (pass: Pick<FuelPassView, "contentType" | "image">): string => `data:${pass.contentType};base64,${pass.image}`;
