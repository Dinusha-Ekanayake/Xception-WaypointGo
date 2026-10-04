import { depotToday } from "../wording/time.ts";
import type { MemberRole, MemberView, MessageAudience, MessageView, ReportType } from "../domain/messaging.ts";

// The pure half of a trip's thread (issue #136): what each message, role and
// report is called, who a message is for, the @mentions a writer may type, and
// the order a thread reads in. Each role draws the thread with these.

export const REPORT_LABEL: Record<ReportType, string> = {
  loading_shortfall: "Loading shortfall",
  vehicle_fault: "Vehicle fault",
  road_disruption: "Road disruption",
  failed_delivery: "Failed delivery",
  damaged_goods: "Damaged goods",
  receipt_dispute: "Receipt dispute",
  stock_discrepancy: "Stock problem",
  late: "Running late",
  other: "Other problem",
};

/** The reports each role may make by hand, in the order a phone offers them. */
export const REPORTS_BY_ROLE: Record<MemberRole, ReportType[]> = {
  dispatcher: [],
  loader: ["loading_shortfall", "damaged_goods", "other"],
  driver: ["vehicle_fault", "road_disruption", "late", "failed_delivery", "other"],
  store_manager: ["stock_discrepancy", "damaged_goods", "receipt_dispute", "other"],
};

export const ROLE_LABEL: Record<MemberRole, string> = {
  dispatcher: "Dispatcher",
  loader: "Loader",
  driver: "Driver",
  store_manager: "Store",
};

/**
 * A screen's words in the reader's language: the English text is the key and
 * `{name}` marks a value (the loader's dictionary, roles/loader/data/strings.ts).
 * Roles in English pass nothing and get `plain`.
 */
export type Translate = (english: string, vars?: Record<string, string | number>) => string;

export const plain: Translate = (english, vars) =>
  vars ? english.replace(/\{(\w+)\}/g, (whole, key: string) => (key in vars ? String(vars[key]) : whole)) : english;

/** "To the driver", "To OUT063", "To everyone": who a message reached besides the dispatcher. */
export function audienceLabel(audience: MessageAudience, outlet: string | null, tr: Translate = plain): string {
  switch (audience) {
    case "dispatch":
      return tr("To the dispatcher");
    case "driver":
      return tr("To the driver");
    case "loader":
      return tr("To the loaders");
    case "outlet":
      return outlet ? tr("To {outlet}", { outlet }) : tr("To a store");
    case "all":
      return tr("To everyone on the trip");
  }
}

export type Address = { to: MessageAudience; outletId: string | null };

/** Two addresses are the same recipient. */
export function sameAddress(a: Address, b: Address): boolean {
  return a.to === b.to && (a.to !== "outlet" || a.outletId === b.outletId);
}

/**
 * A leading mention picks the recipient: "@driver", "@loader", "@all",
 * "@dispatcher" or an outlet such as "@OUT063". Only someone the writer may
 * address counts (R-MSG-02); anything else is left in the text.
 */
export function parseMention(text: string, members: MemberView[]): { address: Address | null; body: string } {
  const match = /^\s*@([A-Za-z0-9_]+)\b[\s,:]*/.exec(text);
  if (!match) return { address: null, body: text };
  const word = match[1]!.toLowerCase();
  const wanted: Address | null =
    word === "driver" ? { to: "driver", outletId: null }
    : word === "loader" || word === "loaders" ? { to: "loader", outletId: null }
    : word === "all" || word === "everyone" ? { to: "all", outletId: null }
    : word === "dispatcher" || word === "dispatch" ? { to: "dispatch", outletId: null }
    : { to: "outlet", outletId: match[1]!.toUpperCase() };
  const allowed = members.some((m) => sameAddress({ to: m.to, outletId: m.outletId }, wanted));
  return allowed ? { address: wanted, body: text.slice(match[0].length) } : { address: null, body: text };
}

/** Pages arrive newest first; the thread reads oldest first, each message once. */
export function chronological(pages: MessageView[][]): MessageView[] {
  const seen = new Set<string>();
  const out: MessageView[] = [];
  for (const page of pages) {
    for (const m of page) {
      if (!seen.has(m.messageId)) {
        seen.add(m.messageId);
        out.push(m);
      }
    }
  }
  return out.sort((a, b) => a.createdAt.localeCompare(b.createdAt) || a.messageId.localeCompare(b.messageId));
}

/** The day a message falls on in depot time, "2026-10-05", for the separators in a thread. */
export function depotDay(iso: string): string {
  return depotToday(new Date(iso));
}

/** Messages split at each new depot day, oldest first. */
export function byDay(messages: MessageView[]): Array<{ day: string; messages: MessageView[] }> {
  const out: Array<{ day: string; messages: MessageView[] }> = [];
  for (const m of messages) {
    const day = depotDay(m.createdAt);
    const last = out[out.length - 1];
    if (last && last.day === day) last.messages.push(m);
    else out.push({ day, messages: [m] });
  }
  return out;
}

/** "0:42" for a voice note's length. */
export function voiceLength(ms: number | null): string {
  if (ms === null || ms <= 0) return "";
  const s = Math.round(ms / 1000);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}

/** The default recipient: the dispatcher for everyone but the dispatcher, who starts with the driver. */
export function defaultAddress(role: MemberRole, members: MemberView[]): Address {
  const preferred: MessageAudience = role === "dispatcher" ? "driver" : "dispatch";
  const found = members.find((m) => m.to === preferred) ?? members[0];
  return found ? { to: found.to, outletId: found.outletId } : { to: "dispatch", outletId: null };
}
