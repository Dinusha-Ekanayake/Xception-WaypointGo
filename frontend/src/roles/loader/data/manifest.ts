import type { CheckStatus, IssueKind, ManifestLineView, OutletView, SessionStatus, Temperature } from "@shared/domain/types";

// Pure helpers over a manifest. Capacity reads order totals only (AGENTS.md,
// External Product Catalogue): weight and volume never come from product lines.

export type StopGroup<L extends ManifestLineView> = {
  stopSequence: number;
  outletId: string;
  lines: L[];
};

/** Stops in loading order: the last stop is loaded first (D-L). */
export function byStop<L extends ManifestLineView>(lines: L[]): StopGroup<L>[] {
  const groups: StopGroup<L>[] = [];
  for (const line of [...lines].sort((a, b) => a.loadSequence - b.loadSequence)) {
    const last = groups[groups.length - 1];
    if (last && last.stopSequence === line.stopSequence) last.lines.push(line);
    else groups.push({ stopSequence: line.stopSequence, outletId: line.outletId, lines: [line] });
  }
  return groups;
}

export const isChecked = (status: CheckStatus) => status !== "PENDING";
export const isFlagged = (status: CheckStatus) => status === "SHORT" || status === "MISSING" || status === "DAMAGED" || status === "DOES_NOT_FIT";

/** Exactly the three issues loaders may report. SHORT is displayed only for historical records. */
export const ISSUE_KIND_LABEL: Record<IssueKind, string> = {
  DAMAGED: "Damaged",
  DOES_NOT_FIT: "Doesn't fit",
  MISSING: "Missing",
};

export function progress(lines: ManifestLineView[]): { checked: number; total: number; flagged: number; percent: number } {
  const checked = lines.filter((l) => isChecked(l.status)).length;
  const flagged = lines.filter((l) => isFlagged(l.status)).length;
  return { checked, total: lines.length, flagged, percent: lines.length ? Math.round((checked / lines.length) * 100) : 0 };
}

/** Loaded share of the manifest's own totals, by order-level weight and volume. */
export function loadedTotals(lines: ManifestLineView[]) {
  let weight = 0;
  let volume = 0;
  let weightAll = 0;
  let volumeAll = 0;
  for (const l of lines) {
    const share = l.itemCount ? l.loadedUnits / l.itemCount : 0;
    weightAll += Number(l.weightKg);
    volumeAll += Number(l.volumeM3);
    weight += Number(l.weightKg) * share;
    volume += Number(l.volumeM3) * share;
  }
  return { weight, weightAll, volume, volumeAll };
}

export function tripTemperature(lines: ManifestLineView[]): Temperature {
  return lines.some((l) => l.temperature === "chilled") ? "chilled" : "ambient";
}

export function placeName(outletId: string, outlets: Map<string, OutletView>): string {
  return outlets.get(outletId)?.districtName ?? outletId;
}

/** Order ids are UUIDs; the dock reads the last block, as printed on the pick label. */
export function orderLabel(orderId: string): string {
  return orderId.split("-").pop()!.slice(-6).toUpperCase();
}

export function hhmm(time: string): string {
  return time.slice(0, 5);
}

export function depotToday(now: Date = new Date()): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Colombo" }).format(now);
}

export const STATUS_LABEL: Record<SessionStatus, string> = {
  NOT_STARTED: "Not started",
  IN_PROGRESS: "Loading",
  BLOCKED: "Flagged",
  READY: "Ready to release",
  COMPLETED: "Released",
};

export const CHECK_LABEL: Record<CheckStatus, string> = {
  PENDING: "To load",
  LOADED: "Loaded",
  SHORT: "Short",
  MISSING: "Missing",
  DAMAGED: "Damaged",
  DOES_NOT_FIT: "Doesn't fit",
};

export const kg = (n: number) => `${Math.round(n).toLocaleString("en-US")} kg`;
export const m3 = (n: number) => `${n.toFixed(1)} m³`;
