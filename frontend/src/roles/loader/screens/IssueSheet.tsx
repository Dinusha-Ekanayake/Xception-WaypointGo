"use client";

import { useState } from "react";
import type { CheckStatus, FlagShortfall, ItemView, OutletView } from "@shared/domain/types";
import { Notice, cx } from "@shared/ui";
import { ISSUE_KIND_LABEL, isFlagged, orderLabel, placeName } from "../data/manifest.ts";
import type { Line } from "../data/useTrip.ts";
import { BigButton, Sheet } from "../ui.tsx";
import { useT } from "../i18n.tsx";
import { ChevronDownIcon, CloseIcon, IssueIcon } from "../icons.tsx";

// Figma "03 Report an issue": flag a missing, damaged or ill-fitting item before
// the vehicle leaves (R-LOD-02). The flagged item is not loaded, the dispatcher
// and store are told, and loading carries on (R-LOD-07).

type Kind = Extract<CheckStatus, "SHORT" | "MISSING" | "DAMAGED" | "DOES_NOT_FIT">;
// Figma's hints, in the item vocabulary decided on 2026-10-01 (units, not packages).
const ISSUE_HINT: Record<Kind, string> = {
  SHORT: "Fewer units than picked",
  DAMAGED: "Crushed, leaking, torn",
  DOES_NOT_FIT: "No space left in the vehicle",
  MISSING: "Not at the dock",
};

/**
 * A native select drawn as Figma's two-line field: the phone's own picker opens,
 * and a screen reader reads one labelled control.
 */
function Picker({
  label,
  title,
  detail,
  value,
  onChange,
  children,
}: {
  label: string;
  title: string;
  detail: string;
  value: string | number;
  onChange: (value: string) => void;
  children: React.ReactNode;
}): React.JSX.Element {
  return (
    <label className="flex flex-col gap-1.5">
      <span className="text-[14px] text-go-muted">{label}</span>
      <span className="relative flex min-h-16 items-center gap-3 rounded-[18px] border border-go-rule bg-go-surface px-4 py-2.5">
        <span className="flex min-w-0 flex-1 flex-col">
          <span className="truncate text-[18px] font-medium">{title}</span>
          <span className="truncate text-[14px] text-go-muted">{detail}</span>
        </span>
        <ChevronDownIcon />
        <select
          value={value}
          onChange={(e) => onChange(e.target.value)}
          className="absolute inset-0 h-full w-full cursor-pointer opacity-0"
        >
          {children}
        </select>
      </span>
    </label>
  );
}

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
    <Sheet label={tr("Report an issue")} onClose={onClose} placement="center">
      <form onSubmit={submit} className="flex flex-col gap-4">
        <div className="flex items-center justify-between">
          <h2 className="text-[28px] font-semibold">{tr("Report an issue")}</h2>
          <button type="button" onClick={onClose} aria-label={tr("Close")} className="flex size-12 items-center justify-center rounded-full bg-go-surface text-go-ink">
            <CloseIcon />
          </button>
        </div>

        <Picker
          label={tr("Which order?")}
          title={line ? `${tr("Order {ref}", { ref: orderLabel(line) })} · ${tr(line.temperature === "chilled" ? "Chilled" : "Ambient")}` : ""}
          detail={line ? [`Stop ${String(line.stopSequence).padStart(2, "0")}`, placeName(line.outletId, outlets), tr(line.items.length === 1 ? "{n} item" : "{n} items", { n: line.items.length })].join(" · ") : ""}
          value={orderId}
          onChange={chooseOrder}
        >
          {lines.map((l) => (
            <option key={l.orderId} value={l.orderId}>
              {orderLabel(l)} · Stop {String(l.stopSequence).padStart(2, "0")} · {placeName(l.outletId, outlets)}
            </option>
          ))}
        </Picker>

        <Picker
          label={tr("Which item?")}
          title={item ? `${tr("Item {n}", { n: item.lineNo })} · ${item.productId} ${tr("(inferred)")}` : tr("Whole order")}
          detail={
            item
              ? `${tr(item.units === 1 ? "{n} unit" : "{n} units", { n: item.units })} · ${tr("or pick \u201cWhole order\u201d")}`
              : tr("Every item of the order not already reported")
          }
          value={lineNo}
          onChange={(value) => {
            setLineNo(Number(value));
            if (Number(value) === 0 && kind === "SHORT") setKind("DAMAGED");
            setUnits(1);
          }}
        >
          <option value={0}>{tr("Whole order")}</option>
          {(line?.items ?? []).map((i) => (
            <option key={i.lineNo} value={i.lineNo}>
              {tr("Item {n}", { n: i.lineNo })} · {i.productId} {tr("(inferred)")} · {tr(i.units === 1 ? "{n} unit" : "{n} units", { n: i.units })}
            </option>
          ))}
        </Picker>

        <fieldset className="grid gap-2.5 md:grid-cols-2 md:gap-3">
          <legend className="sr-only">{tr("What is wrong?")}</legend>
          {Object.entries(ISSUE_KIND_LABEL).map(([value, label]) => {
            const kindValue = value as Kind;
            return (
            <label
              key={kindValue}
              className={cx(
                "flex min-h-16 cursor-pointer items-center gap-4 rounded-[20px] px-5 py-2.5 has-[:disabled]:cursor-not-allowed has-[:disabled]:opacity-60 md:min-h-[94px]",
                kind === kindValue ? "border-2 border-go-rule bg-go-card shadow-[0_2px_6px_rgba(0,0,0,0.08)]" : "border border-go-rule bg-go-surface",
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
              <span className="flex w-7 shrink-0 justify-center"><IssueIcon kind={kindValue} /></span>
              <span className="flex flex-col">
                <span className="text-[18px] font-medium">{tr(label)}</span>
                <span className="text-[14px] text-go-muted">
                  {tr(kindValue === "SHORT" && !shortAllowed ? "Choose one item with more than one unit" : ISSUE_HINT[kindValue])}
                </span>
              </span>
            </label>
            );
          })}
        </fieldset>

        {item !== null && kind !== "MISSING" && (
          <div className="flex items-center justify-between gap-3 rounded-[16px] bg-go-surface py-3 pr-4 pl-4">
            <span className="flex flex-col">
              <span className="text-[17px] font-medium">
                {tr(kind === "SHORT" ? "Units short" : kind === "DAMAGED" ? "Units damaged" : "Units that don't fit")}
              </span>
              <span className="text-[13px] text-go-muted">{tr("of {n} for this item", { n: max })}</span>
            </span>
            <div className="flex items-center gap-2">
              <button type="button" aria-label={tr("Fewer")} onClick={() => setUnits((u) => Math.max(1, u - 1))} className="size-14 rounded-[16px] bg-go-divider text-[28px]">
                −
              </button>
              <output aria-live="polite" className="w-10 text-center text-[28px] font-semibold">
                {missing}
              </output>
              <button type="button" aria-label={tr("More")} onClick={() => setUnits((u) => Math.min(kind === "SHORT" ? shortMax : max, u + 1))} className="size-14 rounded-[16px] bg-go-divider text-[28px]">
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
            placeholder={tr("For example: two units crushed on the pallet")}
            className="rounded-[16px] border border-go-rule px-4 py-3 text-base"
          />
        </label>

        {item !== null && isFlagged(item.status) && (
          <Notice tone="info" title={tr("This item is already reported")}>
            {tr("Sending again records a new report; the earlier one stays on record.")}
          </Notice>
        )}

        <div className="flex flex-col gap-3">
          <BigButton tone="grey" size="l" onClick={onClose}>
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
