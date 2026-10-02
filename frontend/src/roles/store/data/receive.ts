import type { ItemView, ReceiptLineView } from "@shared/domain/types";

// The store's count, as Figma "06 Receive delivery" builds it. Pure, so the rules
// are tested without a browser.
//
// One delivery problem is one report. Missing, damaged and wrong goods were not
// accepted, so they lower the count and the receipt is confirmed as partial; the
// problems become the answer's note, and Issues opens one investigation from it
// with the photos (R-RCP-07). What the loader already flagged at the dock never
// left it: it lowers the count too, but it is not reported again, so a shortage
// the loader explained raises nothing new.

export type Kind = "Missing" | "Damaged" | "Wrong item" | "Other";
export const KINDS: Kind[] = ["Missing", "Damaged", "Wrong item", "Other"];
/** Other is a remark on the item; it does not change what was counted. */
export const LOWERS_COUNT: Record<Kind, boolean> = { Missing: true, Damaged: true, "Wrong item": true, Other: false };

/** One problem the store found, with the photos taken of it (ids minted on this device). */
export type Report = { productId: string; kind: Kind; units: number; photoIds: string[] };

/** What the store sends: one of the receipt answers, or a confirmation plus one issue for a remark. */
export type Answer =
  | { kind: "confirm" }
  | { kind: "partial"; note: string | null }
  | { kind: "dispute"; reason: string }
  | { kind: "confirm-and-raise"; description: string };

/** The checks that mean "not loaded": the goods never left the dock. */
const FLAGGED = new Set(["SHORT", "MISSING", "DAMAGED", "DOES_NOT_FIT"]);

/** Units the loader flagged per product, from the order's loading check; empty when it cannot be read. */
export function knownShortages(items: readonly ItemView[] | null | undefined): Map<string, number> {
  const out = new Map<string, number>();
  for (const item of items ?? []) {
    if (!FLAGGED.has(item.status)) continue;
    const missing = Math.max(0, item.units - item.loadedUnits);
    if (missing > 0) out.set(item.productId, (out.get(item.productId) ?? 0) + missing);
  }
  return out;
}

/** Units of one product the store reported as not accepted. */
function reported(productId: string, reports: readonly Report[]): number {
  return reports.filter((r) => r.productId === productId && LOWERS_COUNT[r.kind]).reduce((s, r) => s + r.units, 0);
}

/** What the store counts per product: expected, less what the loader kept back, less what it reported. */
export function counted(
  lines: readonly ReceiptLineView[],
  reports: readonly Report[],
  known: ReadonlyMap<string, number>,
): { productId: string; receivedQuantity: number }[] {
  return lines.map((l) => ({
    productId: l.productId,
    receivedQuantity: Math.max(0, l.expectedQuantity - (known.get(l.productId) ?? 0) - reported(l.productId, reports)),
  }));
}

/** Units of a product still counted as arrived, which is the most a new report can take. */
export function room(line: ReceiptLineView, reports: readonly Report[], known: ReadonlyMap<string, number>): number {
  return Math.max(0, line.expectedQuantity - (known.get(line.productId) ?? 0) - reported(line.productId, reports));
}

/** "Damaged: Red lentils 1 kg x1. Missing: Soya meat x1. Other: Butter 200 g. 2 photos." plus the store's own words. */
export function noteOf(reports: readonly Report[], extra = ""): string | null {
  const parts: string[] = [];
  for (const kind of KINDS) {
    const items = reports.filter((r) => r.kind === kind);
    if (items.length === 0) continue;
    parts.push(`${kind}: ${items.map((r) => (LOWERS_COUNT[kind] ? `${r.productId} x${r.units}` : r.productId)).join(", ")}`);
  }
  const photos = reports.reduce((s, r) => s + r.photoIds.length, 0);
  if (photos > 0) parts.push(`${photos} ${photos === 1 ? "photo" : "photos"}`);
  const words = extra.trim();
  if (words) parts.push(`Note: ${words}`);
  return parts.length ? `${parts.join(". ")}.` : null;
}

/**
 * The answer for the count as it stands.
 *
 * - "Something else is wrong" is a dispute; its reason is the store's words, with the problems.
 * - Anything short (reported, or kept back by the loader) is a partial receipt. Its note is the
 *   problems; with no problems and no words it is null, so a shortage only the loader explains is
 *   not investigated twice.
 * - Nothing short but a remark ("Other") is a confirmation, and the remark is one issue.
 */
export function answerFor(
  lines: readonly ReceiptLineView[],
  reports: readonly Report[],
  known: ReadonlyMap<string, number>,
  dispute: boolean,
  extra: string,
): Answer {
  if (dispute) {
    const problems = noteOf(reports);
    return { kind: "dispute", reason: [extra.trim(), problems].filter(Boolean).join(" · ") };
  }
  const short = counted(lines, reports, known).some((c, i) => c.receivedQuantity < lines[i]!.expectedQuantity);
  if (short) return { kind: "partial", note: noteOf(reports, extra) };
  if (reports.length > 0) return { kind: "confirm-and-raise", description: noteOf(reports, extra)! };
  return { kind: "confirm" };
}
