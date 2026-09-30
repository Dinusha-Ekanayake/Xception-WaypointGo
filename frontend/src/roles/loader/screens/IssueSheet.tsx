"use client";

import { useState } from "react";
import type { CheckStatus, FlagShortfall, OutletView } from "@shared/domain/types";
import { Icon, Notice, cx } from "@shared/ui";
import { orderLabel, placeName } from "../data/manifest.ts";
import type { Line } from "../data/useTrip.ts";
import { BigButton, Sheet } from "../ui.tsx";

// Figma "03 Report an issue": flag a short, missing or damaged order before
// the vehicle leaves (R-LOD-02). The dispatcher is told; the order counts as
// checked for release, with what is missing on record.

type Kind = Extract<CheckStatus, "SHORT" | "MISSING" | "DAMAGED">;
const KINDS: Array<{ value: Kind; label: string; hint: string }> = [
  { value: "SHORT", label: "Short", hint: "Fewer items than picked" },
  { value: "DAMAGED", label: "Damaged", hint: "Crushed, leaking, torn" },
  { value: "MISSING", label: "Missing", hint: "The whole order is not at the dock" },
];

export default function IssueSheet({
  lines,
  initial,
  outlets,
  busy,
  onSend,
  onClose,
}: {
  lines: Line[];
  initial: Line | null;
  outlets: Map<string, OutletView>;
  busy: boolean;
  onSend: (payload: Omit<FlagShortfall, "tripId">) => Promise<boolean>;
  onClose: () => void;
}): React.JSX.Element {
  const [orderId, setOrderId] = useState(initial?.orderId ?? lines[0]?.orderId ?? "");
  const [kind, setKind] = useState<Kind>("SHORT");
  const [units, setUnits] = useState(1);
  const [reason, setReason] = useState("");
  const line = lines.find((l) => l.orderId === orderId);
  const max = line?.itemCount ?? 1;
  const missing = kind === "MISSING" ? max : Math.min(units, max);
  const ready = line !== undefined && reason.trim().length > 0 && missing > 0;

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!ready) return;
    const ok = await onSend({ orderId, kind, missingUnits: missing, reason: reason.trim(), photoAttachmentId: null });
    if (ok) onClose();
  };

  return (
    <Sheet label="Report an issue" onClose={onClose}>
      <form onSubmit={submit} className="flex flex-col gap-4">
        <div className="flex items-center justify-between">
          <h2 className="text-[28px] font-semibold">Report an issue</h2>
          <button type="button" onClick={onClose} aria-label="Close" className="flex size-12 items-center justify-center rounded-full bg-[#f1f3f5]">
            <Icon name="close" />
          </button>
        </div>

        <label className="flex flex-col gap-1.5">
          <span className="text-[13px] text-go-muted">Which order?</span>
          <select
            value={orderId}
            onChange={(e) => setOrderId(e.target.value)}
            className="min-h-14 rounded-[16px] border border-[#dfe3e8] bg-[#f1f3f5] px-4 text-base font-medium"
          >
            {lines.map((l) => (
              <option key={l.orderId} value={l.orderId}>
                Order {orderLabel(l.orderId)} · Stop {String(l.stopSequence).padStart(2, "0")} · {placeName(l.outletId, outlets)} · {l.itemCount} items
              </option>
            ))}
          </select>
        </label>

        <fieldset className="grid gap-2 md:grid-cols-2 md:gap-3">
          <legend className="mb-1.5 text-[13px] text-go-muted md:col-span-2">What is wrong?</legend>
          {KINDS.map((k) => (
            <label
              key={k.value}
              className={cx(
                "flex min-h-14 cursor-pointer flex-col justify-center rounded-[16px] px-4 py-2.5 md:min-h-[94px]",
                k.value === "MISSING" && "md:col-span-2",
                kind === k.value ? "border-2 border-[#dfe3e8] bg-white shadow-[0_2px_6px_rgba(0,0,0,0.08)]" : "border border-[#dfe3e8] bg-[#f1f3f5]",
              )}
            >
              <input type="radio" name="kind" value={k.value} checked={kind === k.value} onChange={() => setKind(k.value)} className="sr-only" />
              <span className="text-[17px] font-medium">{k.label}</span>
              <span className="text-[13px] text-go-muted">{k.hint}</span>
            </label>
          ))}
        </fieldset>

        {kind !== "MISSING" && (
          <div className="flex items-center justify-between gap-3 rounded-[16px] bg-[#f1f3f5] py-3 pr-4 pl-4">
            <span className="flex flex-col">
              <span className="text-[17px] font-medium">Items {kind === "SHORT" ? "short" : "damaged"}</span>
              <span className="text-[13px] text-go-muted">of {max} in this order</span>
            </span>
            <div className="flex items-center gap-2">
              <button type="button" aria-label="Fewer" onClick={() => setUnits((u) => Math.max(1, u - 1))} className="size-14 rounded-[16px] bg-[#e5e7eb] text-[28px]">
                −
              </button>
              <output aria-live="polite" className="w-10 text-center text-[28px] font-semibold">
                {missing}
              </output>
              <button type="button" aria-label="More" onClick={() => setUnits((u) => Math.min(max, u + 1))} className="size-14 rounded-[16px] bg-[#e5e7eb] text-[28px]">
                +
              </button>
            </div>
          </div>
        )}

        <label className="flex flex-col gap-1.5">
          <span className="text-[13px] text-go-muted">Reason (required)</span>
          <textarea
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            rows={2}
            placeholder="For example: two cases crushed on the pallet"
            className="rounded-[16px] border border-[#dfe3e8] px-4 py-3 text-base"
          />
        </label>

        {line && line.status !== "PENDING" && line.status !== "LOADED" && (
          <Notice tone="info" title="This order is already flagged">
            Sending again records a new report; the earlier one stays on record.
          </Notice>
        )}

        <div className="grid gap-3 md:grid-cols-2">
          <BigButton tone="muted" size="l" onClick={onClose}>
            Cancel
          </BigButton>
          <BigButton tone="danger" size="l" type="submit" disabled={!ready || busy}>
            {busy ? "Sending…" : "Send to dispatcher"}
          </BigButton>
        </div>
      </form>
    </Sheet>
  );
}
