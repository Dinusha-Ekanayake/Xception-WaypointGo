"use client";

import { useState } from "react";
import type { CheckStatus, FlagShortfall, ItemView, OutletView } from "@shared/domain/types";
import { Icon, Notice, cx } from "@shared/ui";
import { ISSUE_KIND_LABEL, isFlagged, orderLabel, placeName } from "../data/manifest.ts";
import type { Line } from "../data/useTrip.ts";
import { BigButton, Sheet } from "../ui.tsx";
import { useT } from "../i18n.tsx";

// Figma "03 Report an issue": flag a missing, damaged or ill-fitting item before
// the vehicle leaves (R-LOD-02). The flagged item is not loaded, the dispatcher
// and store are told, and loading carries on (R-LOD-07).

type Kind = Extract<CheckStatus, "SHORT" | "MISSING" | "DAMAGED" | "DOES_NOT_FIT">;
const ISSUE_HINT: Record<Kind, string> = {
  SHORT: "Fewer units than picked",
  DAMAGED: "Crushed, leaking, torn",
  DOES_NOT_FIT: "Does not fit safely in the vehicle",
  MISSING: "Not at the dock",
};

export default function IssueSheet({
  lines,
  initial,
  outlets,
  busy,
  onSend,
  onClose,
}: {
  lines: Line[];
  /** The order, and optionally the item, the loader reported from. */
  initial: { line: Line; item: ItemView | null } | null;
  outlets: Map<string, OutletView>;
  busy: boolean;
  onSend: (payload: Omit<FlagShortfall, "tripId">) => Promise<boolean>;
  onClose: () => void;
}): React.JSX.Element {
  const tr = useT();
  const [orderId, setOrderId] = useState(initial?.line.orderId ?? lines[0]?.orderId ?? "");
  // 0 is the whole order; otherwise the item's lineNo.
  const [lineNo, setLineNo] = useState(initial?.item?.lineNo ?? 0);
  const [kind, setKind] = useState<Kind>("DAMAGED");
  const [units, setUnits] = useState(1);
  const [reason, setReason] = useState("");
  const line = lines.find((l) => l.orderId === orderId);
  const item = line?.items.find((i) => i.lineNo === lineNo) ?? null;
  // The whole order flags every item not already flagged, in full (the server's rule).
  const open = (line?.items ?? []).filter((i) => !isFlagged(i.status));
  const max = item ? item.units : open.reduce((n, i) => n + i.units, 0);
  // Short leaves at least one unit loaded; none arrived is Missing (the server's rule).
  const shortMax = item ? item.units - 1 : 0;
  const missing = item === null || kind === "MISSING" ? max : Math.min(units, kind === "SHORT" ? shortMax : max);
  const shortAllowed = item !== null && shortMax >= 1;
  const ready = line !== undefined && reason.trim().length > 0 && missing > 0 && (kind !== "SHORT" || shortAllowed);

  const chooseOrder = (id: string) => {
    setOrderId(id);
    setLineNo(0);
    if (kind === "SHORT") setKind("DAMAGED");
    setUnits(1);
  };

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!ready) return;
    const ok = await onSend({
      orderId,
      lineNo: item ? item.lineNo : null,
      kind,
      missingUnits: missing,
      reason: reason.trim(),
      photoAttachmentId: null,
    });
    if (ok) onClose();
  };

  return (
    <Sheet label={tr("Report an issue")} onClose={onClose}>
      <form onSubmit={submit} className="flex flex-col gap-4">
        <div className="flex items-center justify-between">
          <h2 className="text-[28px] font-semibold">{tr("Report an issue")}</h2>
          <button type="button" onClick={onClose} aria-label="Close" className="flex size-12 items-center justify-center rounded-full bg-[#f1f3f5]">
            <Icon name="close" />
          </button>
        </div>

        <label className="flex flex-col gap-1.5">
          <span className="text-[13px] text-go-muted">{tr("Which order?")}</span>
          <select
            value={orderId}
            onChange={(e) => chooseOrder(e.target.value)}
            className="min-h-14 rounded-[16px] border border-[#dfe3e8] bg-[#f1f3f5] px-4 text-base font-medium"
          >
            {lines.map((l) => (
              <option key={l.orderId} value={l.orderId}>
                {orderLabel(l)} · Stop {String(l.stopSequence).padStart(2, "0")} · {placeName(l.outletId, outlets)} · {l.items.length} items
              </option>
            ))}
          </select>
        </label>

        <label className="flex flex-col gap-1.5">
          <span className="text-[13px] text-go-muted">{tr("Which item?")}</span>
          <select
            value={lineNo}
            onChange={(e) => {
              setLineNo(Number(e.target.value));
              if (Number(e.target.value) === 0 && kind === "SHORT") setKind("DAMAGED");
              setUnits(1);
            }}
            className="min-h-14 rounded-[16px] border border-[#dfe3e8] bg-[#f1f3f5] px-4 text-base font-medium"
          >
            <option value={0}>{tr("Whole order")}</option>
            {(line?.items ?? []).map((i) => (
              <option key={i.lineNo} value={i.lineNo}>
                {i.productId} (inferred) · {i.units} units
              </option>
            ))}
          </select>
        </label>

        <fieldset className="grid gap-2 md:grid-cols-2 md:gap-3">
          <legend className="mb-1.5 text-[13px] text-go-muted md:col-span-2">{tr("What is wrong?")}</legend>
          {Object.entries(ISSUE_KIND_LABEL).map(([value, label]) => {
            const kindValue = value as Kind;
            return (
            <label
              key={kindValue}
              className={cx(
                "flex min-h-14 cursor-pointer flex-col justify-center rounded-[16px] px-4 py-2.5 md:min-h-[94px]",
                kind === kindValue ? "border-2 border-[#dfe3e8] bg-white shadow-[0_2px_6px_rgba(0,0,0,0.08)]" : "border border-[#dfe3e8] bg-[#f1f3f5]",
              )}
            >
              <input
                type="radio"
                name="kind"
                value={kindValue}
                checked={kind === kindValue}
                disabled={kindValue === "SHORT" && !shortAllowed}
                onChange={() => setKind(kindValue)}
                className="sr-only"
              />
              <span className="text-[17px] font-medium">{tr(label)}</span>
              <span className="text-[13px] text-go-muted">
                {tr(kindValue === "SHORT" && !shortAllowed ? "Choose one item with more than one unit" : ISSUE_HINT[kindValue])}
              </span>
            </label>
            );
          })}
        </fieldset>

        {item !== null && kind !== "MISSING" && (
          <div className="flex items-center justify-between gap-3 rounded-[16px] bg-[#f1f3f5] py-3 pr-4 pl-4">
            <span className="flex flex-col">
              <span className="text-[17px] font-medium">
                Units {kind === "SHORT" ? "short" : kind === "DAMAGED" ? "damaged" : "that don't fit"}
              </span>
              <span className="text-[13px] text-go-muted">of {max} for this item</span>
            </span>
            <div className="flex items-center gap-2">
              <button type="button" aria-label="Fewer" onClick={() => setUnits((u) => Math.max(1, u - 1))} className="size-14 rounded-[16px] bg-[#e5e7eb] text-[28px]">
                −
              </button>
              <output aria-live="polite" className="w-10 text-center text-[28px] font-semibold">
                {missing}
              </output>
              <button type="button" aria-label="More" onClick={() => setUnits((u) => Math.min(kind === "SHORT" ? shortMax : max, u + 1))} className="size-14 rounded-[16px] bg-[#e5e7eb] text-[28px]">
                +
              </button>
            </div>
          </div>
        )}

        <label className="flex flex-col gap-1.5">
          <span className="text-[13px] text-go-muted">{tr("Reason (required)")}</span>
          <textarea
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            rows={2}
            placeholder="For example: two cases crushed on the pallet"
            className="rounded-[16px] border border-[#dfe3e8] px-4 py-3 text-base"
          />
        </label>

        {item !== null && isFlagged(item.status) && (
          <Notice tone="info" title={tr("This item is already reported")}>
            Sending again records a new report; the earlier one stays on record.
          </Notice>
        )}

        <div className="grid gap-3 md:grid-cols-2">
          <BigButton tone="muted" size="l" onClick={onClose}>
            {tr("Cancel")}
          </BigButton>
          <BigButton tone="danger" size="l" type="submit" disabled={!ready || busy}>
            {tr(busy ? "Sending…" : "Send to dispatcher")}
          </BigButton>
        </div>
      </form>
    </Sheet>
  );
}
