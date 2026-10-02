"use client";

import { useState } from "react";
import type { ItemView, OutletView } from "@shared/domain/types";
import { Icon, cx } from "@shared/ui";
import { byStop, CHECK_LABEL, clockTime, isChecked, isFlagged, kg, orderLabel, placeName } from "../data/manifest.ts";
import type { Line } from "../data/useTrip.ts";
import { useT } from "../i18n.tsx";

// Figma "02 Load sheet", load list: stops in loading order, last stop first
// (D-L), each order opened into its items. The loader ticks each item as it goes
// on. A product id is an inferred candidate, never shown as a real SKU.

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
  onToggle: (line: Line, item: ItemView | null) => void;
  onReport: (line: Line, item: ItemView | null) => void;
}): React.JSX.Element {
  const tr = useT();
  const stops = byStop(lines);
  const firstOpen = stops.find((s) => s.lines.some((l) => !isChecked(l.status)))?.stopSequence;
  const [open, setOpen] = useState<Record<number, boolean>>({});
  const total = stops.length;
  const items = lines.reduce((n, l) => n + l.items.length, 0);

  return (
    <section aria-label="Load list" className="flex flex-col gap-3">
      <div className="flex flex-col gap-0.5">
        <h2 className="text-[26px] font-semibold">{tr("Load list")}</h2>
        <p className="text-[14px] text-go-muted">{tr("Last stop loads first. Tick each item.")}</p>
        <p className="text-[13px] text-go-muted">
          {[tr(total === 1 ? "{n} stop" : "{n} stops", { n: total }), tr(lines.length === 1 ? "{n} order" : "{n} orders", { n: lines.length }), tr(items === 1 ? "{n} item" : "{n} items", { n: items })].join(" · ")}
        </p>
      </div>
      <div className="overflow-hidden rounded-[25px] bg-go-card px-4 pt-1 pb-4">
        {stops.map((stop, index) => {
          const stopItems = stop.lines.flatMap((l) => l.items);
          const done = stopItems.filter((i) => i.status === "LOADED").length;
          const expanded = open[stop.stopSequence] ?? stop.stopSequence === firstOpen;
          const flagged = stopItems.filter((i) => isFlagged(i.status)).length;
          return (
            <div key={stop.stopSequence} className="border-b border-go-rule last:border-b-0">
              <button
                type="button"
                aria-expanded={expanded}
                onClick={() => setOpen((o) => ({ ...o, [stop.stopSequence]: !expanded }))}
                className="flex min-h-14 w-full items-center gap-3.5 py-3.5 text-left"
              >
                <span className="flex size-8 shrink-0 items-center justify-center rounded-[16px] border-[1.5px] border-go-ink bg-go-card text-[14px] font-medium">
                  {stop.lines.every((l) => isChecked(l.status)) ? <Icon name="check" label={tr("All checked")} /> : index + 1}
                </span>
                <span className="flex min-w-0 flex-1 flex-col gap-0.5">
                  <span className="text-base font-medium">
                    Stop {String(stop.stopSequence).padStart(2, "0")} · {placeName(stop.outletId, outlets)}
                  </span>
                  <span className="text-[13px] text-go-muted">
                    {tr(stop.lines.length === 1 ? "{n} order" : "{n} orders", { n: stop.lines.length })} · {tr("{a} of {b} items loaded", { a: done, b: stopItems.length })}
                    {flagged > 0 && <span className="text-go-danger-strong"> · {tr("{n} reported", { n: flagged })}</span>}
                  </span>
                </span>
                <span className={cx("transition-transform", expanded && "rotate-180")}>
                  <Icon name="chevron-down" />
                </span>
              </button>
              {expanded && (
                <div className="mb-3 flex flex-col gap-2">
                  {stop.lines.map((line) => (
                    <OrderBlock key={line.orderId} line={line} editable={editable} onToggle={onToggle} onReport={onReport} />
                  ))}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </section>
  );
}

function OrderBlock({
  line,
  editable,
  onToggle,
  onReport,
}: {
  line: Line;
  editable: boolean;
  onToggle: (line: Line, item: ItemView | null) => void;
  onReport: (line: Line, item: ItemView | null) => void;
}): React.JSX.Element {
  const tr = useT();
  const loaded = line.items.filter((i) => i.status === "LOADED").length;
  const units = line.items.reduce((n, i) => n + i.units, 0);
  return (
    <div className={cx("rounded-[16px] bg-go-surface p-1", line.recheck && "ring-2 ring-go-warning")}>
      <div className="flex items-center gap-2 px-2 py-2">
        <div className="flex min-w-0 flex-1 flex-col gap-0.5">
          <span className="text-base font-medium text-go-ink">
            {orderLabel(line)} · {tr(line.temperature === "chilled" ? "Chilled" : "Ambient")}
          </span>
          <span className="text-[13px] text-go-muted">
            {tr(line.items.length === 1 ? "{n} item" : "{n} items", { n: line.items.length })} · {tr(units === 1 ? "{n} unit" : "{n} units", { n: units })} · {kg(Number(line.weightKg))} ·{" "}
            <span className={cx("font-medium", line.recheck ? "text-go-warning-text" : "text-go-teal")}>
              {line.recheck ? tr("Plan changed: check again") : tr("{a} of {b} loaded", { a: loaded, b: line.items.length })}
            </span>
            {line.waiting && <span className="text-go-warning-text"> · {tr("saved on this device")}</span>}
          </span>
        </div>
        {editable && (
          <button
            type="button"
            onClick={() => onReport(line, null)}
            className="min-h-12 shrink-0 rounded-full px-3 text-[13px] font-medium text-go-danger-strong"
          >
            {tr("Report")}
          </button>
        )}
      </div>
      <ul className="flex flex-col gap-1">
        {line.items.map((item) => (
          <ItemRow key={item.lineNo} line={line} item={item} editable={editable} onToggle={onToggle} onReport={onReport} />
        ))}
      </ul>
    </div>
  );
}

function ItemRow({
  line,
  item,
  editable,
  onToggle,
  onReport,
}: {
  line: Line;
  item: ItemView;
  editable: boolean;
  onToggle: (line: Line, item: ItemView | null) => void;
  onReport: (line: Line, item: ItemView | null) => void;
}): React.JSX.Element {
  const tr = useT();
  const loaded = item.status === "LOADED";
  const flagged = isFlagged(item.status);
  const label = tr("item {n} of {order}", { n: item.lineNo, order: orderLabel(line) });
  return (
    <li className={cx("flex items-center gap-3 rounded-[12px] bg-go-card py-2 pr-2 pl-3", flagged && "bg-go-danger-tint")}>
      <button
        type="button"
        // A flagged item is reported, not ticked: re-ticking it goes through Report.
        onClick={() => (flagged ? onReport(line, item) : onToggle(line, item))}
        disabled={!editable}
        aria-pressed={loaded}
        aria-label={tr(flagged ? "Edit report for {label}" : loaded ? "Undo loaded, {label}" : "Mark {label} loaded", { label })}
        className={cx(
          "flex size-12 shrink-0 items-center justify-center rounded-[10px] border-2 disabled:cursor-not-allowed",
          loaded ? "border-go-signal bg-go-signal" : "border-go-teal bg-go-card",
          flagged && "border-go-danger bg-go-card",
        )}
      >
        {loaded && <Icon name="check-white" />}
        {flagged && <Icon name="triangle" />}
      </button>
      <div className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className="truncate text-[15px] font-medium text-go-ink">
          {item.productId} <span className="text-[12px] font-normal text-go-muted">{tr("(inferred)")}</span>
        </span>
        <span className="text-[13px] text-go-muted">
          {tr(item.units === 1 ? "{n} unit" : "{n} units", { n: item.units })} ·{" "}
          <span className={cx("font-medium", flagged ? "text-go-danger-strong" : loaded ? "text-go-success" : "text-go-teal")}>
            {flagged
              ? `${tr(CHECK_LABEL[item.status])} · ${tr("{a} of {b} not loaded", { a: item.units - item.loadedUnits, b: item.units })}`
              : loaded
                ? item.checkedAt ? tr("Loaded {time}", { time: clockTime(item.checkedAt) }) : tr("Loaded")
                : tr("Tap when loaded")}
          </span>
        </span>
      </div>
    </li>
  );
}
