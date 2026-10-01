import type { CheckStatus, HolderView, IssueKind, ItemView, ManifestLineView, OutletView, SessionStatus, Temperature } from "@shared/domain/types";

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

/** The issues a loader may report, in the order of Figma 03. */
export const ISSUE_KIND_LABEL: Record<IssueKind, string> = {
  SHORT: "Short",
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

/** The order's business reference, as printed on the pick label (for example ORD0092336). */
export function orderLabel(line: Pick<ManifestLineView, "orderRef" | "orderId">): string {
  return line.orderRef || line.orderId.slice(-6).toUpperCase();
}

/** Stable key for one item line of one order. */
export const itemKey = (orderId: string, lineNo: number) => `${orderId}:${lineNo}`;

/**
 * An order's status from its items, the same rule the server applies: pending
 * while any item is unchecked, loaded when every item is, otherwise the first flag.
 */
export function orderStatusOf(items: ItemView[]): CheckStatus {
  if (items.some((i) => i.status === "PENDING")) return "PENDING";
  return items.find((i) => isFlagged(i.status))?.status ?? "LOADED";
}

/** "HH:mm" of an instant in the depot's timezone. */
export function clockTime(instant: string): string {
  return new Intl.DateTimeFormat("en-GB", { timeZone: "Asia/Colombo", hour: "2-digit", minute: "2-digit", hour12: false }).format(
    new Date(instant),
  );
}

/** Minutes until a depot wall-clock time today, read in Asia/Colombo whatever the device's timezone. */
export function minutesUntil(time: string, now: Date = new Date()): number {
  const [h, m] = time.split(":").map(Number);
  const [nh, nm] = clockTime(now.toISOString()).split(":").map(Number);
  return (h ?? 0) * 60 + (m ?? 0) - ((nh ?? 0) * 60 + (nm ?? 0));
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

/** Minutes until the planned departure, read in Asia/Colombo; nothing once it has passed. */
/** LoadingSession.IDLE_RELEASE: a hold with no activity for this long lapses. */
export const IDLE_RELEASE_MINUTES = 30;

/** Whether a holder has been idle long enough that anyone may take the trip over. */
export function holdLapsed(holder: HolderView, now: Date = new Date()): boolean {
  return now.getTime() - Date.parse(holder.lastActiveAt) >= IDLE_RELEASE_MINUTES * 60_000;
}

export function untilDeparture(time: string, now: Date = new Date()): string {
  const minutes = minutesUntil(time, now);
  if (minutes <= 0 || minutes >= 12 * 60) return "";
  return minutes < 60 ? `${minutes} min to departure` : `${Math.floor(minutes / 60)} h ${minutes % 60} min to departure`;
}
