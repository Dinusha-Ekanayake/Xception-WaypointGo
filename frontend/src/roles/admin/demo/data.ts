import { request } from "@shared/api/client";
import { newCommand, send } from "@shared/api/commands";
import { DemoCommandKind, type DemoRunView, type DemoView } from "@shared/domain/types";

// Reads and commands of the demo control room (issue #231). Every change is a
// demo:* command through the command bus, admin only, versioned on the settings
// row and audited with a reason.

export function loadDemo(signal: AbortSignal): Promise<DemoView> {
  return request<DemoView>("/api/demo", { signal });
}

export function loadRuns(signal: AbortSignal): Promise<DemoRunView[]> {
  return request<DemoRunView[]>("/api/demo/scenario-runs", { signal });
}

export function setEnabled(view: DemoView, on: boolean, reason: string) {
  return send(newCommand(on ? DemoCommandKind.Enable : DemoCommandKind.Disable, { reason }, view.rowVersion));
}

export function setClock(view: DemoView, target: Date, reason: string) {
  return send(newCommand(DemoCommandKind.SetClock, { target: target.toISOString(), reason }, view.rowVersion));
}

export function updateSettings(view: DemoView, changes: Partial<Pick<DemoView, "banner" | "simPointIntervalMs" | "positionFlushMs" | "speed">>, reason: string) {
  return send(newCommand(DemoCommandKind.UpdateSettings, { ...changes, reason }, view.rowVersion));
}

export function resetDay(view: DemoView, reason: string) {
  return send(newCommand(DemoCommandKind.ResetDay, { reason }, view.rowVersion));
}

/** The instant of a depot wall-clock time (Asia/Colombo, UTC+05:30) on the demo day. */
export function depotInstant(view: DemoView, hhmm: string): Date {
  const day = new Date(new Date(view.now).getTime() + 5.5 * 3_600_000).toISOString().slice(0, 10);
  return new Date(`${day}T${hhmm}:00+05:30`);
}
