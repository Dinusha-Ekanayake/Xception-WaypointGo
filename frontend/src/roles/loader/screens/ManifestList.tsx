"use client";

import { useState } from "react";
import type { OutletView } from "@shared/domain/types";
import { Icon, cx } from "@shared/ui";
import { byStop, CHECK_LABEL, isChecked, isFlagged, kg, orderLabel, placeName } from "../data/manifest.ts";
import type { Line } from "../data/useTrip.ts";

// Figma "02 Load sheet", load list: stops in loading order, last stop first
// (D-L). Checks are per order, because order totals are what the system knows;
// the product lines are a reconstruction and are never shown as real SKUs.

export default function ManifestList({
  lines,
  outlets,
  editable,
  onToggle,
  onReport,
}: {
  lines: Line[];
  outlets: Map<string, OutletView>;
  editable: boolean;
  onToggle: (line: Line) => void;
  onReport: (line: Line) => void;
}): React.JSX.Element {
  const stops = byStop(lines);
  const firstOpen = stops.find((s) => s.lines.some((l) => !isChecked(l.status)))?.stopSequence;
  const [open, setOpen] = useState<Record<number, boolean>>({});
  const total = stops.length;

  return (
    <section aria-label="Load list" className="flex flex-col gap-3">
      <div className="flex flex-col gap-0.5">
        <h2 className="text-[26px] font-semibold">Load list</h2>
        <p className="text-[14px] text-go-muted">Last stop loads first. Tick each order as it goes on.</p>
      </div>
      <div className="overflow-hidden rounded-[25px] bg-white px-4 pt-1 pb-4">
        {stops.map((stop, index) => {
          const done = stop.lines.filter((l) => isChecked(l.status)).length;
          const expanded = open[stop.stopSequence] ?? stop.stopSequence === firstOpen;
          const units = stop.lines.reduce((n, l) => n + l.itemCount, 0);
          return (
            <div key={stop.stopSequence} className="border-b border-[#d3e3e1] last:border-b-0">
              <button
                type="button"
                aria-expanded={expanded}
                onClick={() => setOpen((o) => ({ ...o, [stop.stopSequence]: !expanded }))}
                className="flex min-h-14 w-full items-center gap-3.5 py-3.5 text-left"
              >
                <span
                  className={cx(
                    "flex size-8 shrink-0 items-center justify-center rounded-[16px] text-[14px] font-medium",
                    done === stop.lines.length ? "bg-go-signal" : "bg-go-mint",
                  )}
                >
                  {done === stop.lines.length ? <Icon name="check-white" /> : index + 1}
                </span>
                <span className="flex min-w-0 flex-1 flex-col gap-0.5">
                  <span className="text-base font-medium">
                    Stop {String(stop.stopSequence).padStart(2, "0")} of {String(total).padStart(2, "0")} · {placeName(stop.outletId, outlets)}
                  </span>
                  <span className="text-[13px] text-go-muted">
                    {stop.lines.length} {stop.lines.length === 1 ? "order" : "orders"} · {units} items · {done} of {stop.lines.length} checked
                  </span>
                </span>
                <span className={cx("transition-transform", expanded && "rotate-180")}>
                  <Icon name="chevron-down" />
                </span>
              </button>
              {expanded && (
                <ul className="mb-3 flex flex-col gap-1 rounded-[16px] bg-[#f1f3f5] p-1">
                  {stop.lines.map((line) => (
                    <OrderRow key={line.orderId} line={line} editable={editable} onToggle={onToggle} onReport={onReport} />
                  ))}
                </ul>
              )}
            </div>
          );
        })}
      </div>
    </section>
  );
}

function OrderRow({
  line,
  editable,
  onToggle,
  onReport,
}: {
  line: Line;
  editable: boolean;
  onToggle: (line: Line) => void;
  onReport: (line: Line) => void;
}): React.JSX.Element {
  const loaded = line.status === "LOADED";
  const flagged = isFlagged(line.status);
  return (
    <li
      className={cx(
        "flex items-center gap-3 rounded-[12px] bg-white py-2 pr-2 pl-3",
        line.recheck && "ring-2 ring-go-warning",
        flagged && "bg-go-danger-tint",
      )}
    >
      <button
        type="button"
        onClick={() => onToggle(line)}
        disabled={!editable}
        aria-pressed={loaded}
        aria-label={loaded ? `Undo loaded, order ${orderLabel(line.orderId)}` : `Mark order ${orderLabel(line.orderId)} loaded`}
        className={cx(
          "flex size-12 shrink-0 items-center justify-center rounded-[10px] border-2 disabled:cursor-not-allowed",
          loaded ? "border-go-signal bg-go-signal" : "border-go-teal bg-white",
          flagged && "border-go-danger bg-white",
        )}
      >
        {loaded && <Icon name="check-white" />}
        {flagged && <Icon name="triangle" />}
      </button>
      <div className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className="text-base font-medium text-black">
          Order {orderLabel(line.orderId)} · {line.temperature === "chilled" ? "Chilled" : "Ambient"}
        </span>
        <span className="text-[13px] text-go-muted">
          {line.itemCount} items · {kg(Number(line.weightKg))} ·{" "}
          <span className={cx("font-medium", flagged ? "text-go-danger-strong" : line.recheck ? "text-go-warning-text" : "text-go-teal")}>
            {line.recheck
              ? "Plan changed: check again"
              : flagged
                ? `${CHECK_LABEL[line.status]} · ${line.itemCount - line.loadedUnits} of ${line.itemCount} not loaded`
                : CHECK_LABEL[line.status]}
          </span>
          {line.waiting && <span className="text-go-warning-text"> · saved on phone</span>}
        </span>
      </div>
      {editable && !loaded && (
        <button
          type="button"
          onClick={() => onReport(line)}
          className="min-h-12 shrink-0 rounded-full px-3 text-[13px] font-medium text-go-danger-strong"
        >
          {flagged ? "Edit" : "Report"}
        </button>
      )}
    </li>
  );
}
