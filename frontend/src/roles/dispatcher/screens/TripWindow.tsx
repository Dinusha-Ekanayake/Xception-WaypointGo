"use client";

import { useState, type ReactNode } from "react";
import { Icon, PrimaryButton, SecondaryButton, Sheet, cx } from "@shared/ui";

// Figma "Plan · 1b Swap window" and "Plan · Edit trip" share one frame: the
// trip's header, an optional suggestion banner, three columns (what can come
// in, the trip's stop order with its load, what will be deferred), the checks
// as chips and Cancel / Accept changes. A stop is dragged by its handle to
// reorder it or to the right to defer it; every drag has a button beside it,
// so the window works from the keyboard too.

export type Tile = { label: string; value: string; note: string; percent: number | null; warn?: boolean };

export type StopRow = {
  orderId: string;
  title: string;
  sub: string;
  /** A warning line under the stop ("Skipped yesterday"). */
  note?: string;
  /** "NEW · added by you" on a stop that just came in. */
  tag?: string;
  window: string;
  eta: string;
  share: number | null;
};

export type Chip = { ok: boolean | "warn"; text: string; title?: string };

/** What a drag carries: a stop of the trip, or an order from the left column. */
const STOP = "stop:";
const ORDER = "order:";

/** Makes a card in the left column draggable onto the trip. */
export function draggableOrder(orderId: string): { draggable: true; onDragStart: (event: React.DragEvent) => void } {
  return {
    draggable: true,
    onDragStart: (event) => {
      event.dataTransfer.setData("text/plain", ORDER + orderId);
      event.dataTransfer.effectAllowed = "move";
    },
  };
}

function dragged(event: React.DragEvent): { kind: "stop" | "order"; id: string } | null {
  const data = event.dataTransfer.getData("text/plain");
  if (data.startsWith(STOP)) return { kind: "stop", id: data.slice(STOP.length) };
  if (data.startsWith(ORDER)) return { kind: "order", id: data.slice(ORDER.length) };
  return null;
}

export default function TripWindow({
  label,
  kicker,
  title,
  onClose,
  banner,
  left,
  tiles,
  depart,
  back,
  stops,
  onReorder,
  onDefer,
  onInsert,
  right,
  chips,
  footer,
  accept,
}: {
  label: string;
  kicker: string;
  title: string;
  onClose: () => void;
  banner?: ReactNode;
  left: { title: string; hint: string; body: ReactNode };
  tiles: Tile[];
  depart: { place: string; time: string };
  back: { time: string };
  stops: StopRow[];
  /** Present when the stop order can change. */
  onReorder?: (from: number, to: number) => void;
  /** Present when a stop can be dragged out to be deferred. */
  onDefer?: (orderId: string) => void;
  /** Present when an order from the left column can be dropped onto the trip, at that place. */
  onInsert?: (orderId: string, index: number) => void;
  right: { title: string; hint: string; body?: ReactNode };
  chips: Chip[];
  footer: ReactNode;
  accept: { label: string; disabled: boolean; onClick: () => void; busy?: boolean };
}): React.JSX.Element {
  const [dragging, setDragging] = useState<number | null>(null);
  const [over, setOver] = useState(false);
  /** The place a drop would land, for the line drawn above it. */
  const [target, setTarget] = useState<number | null>(null);
  const droppable = Boolean(onReorder || onInsert);
  const drop = (event: React.DragEvent, index: number) => {
    event.preventDefault();
    const what = dragged(event);
    if (what?.kind === "order" && onInsert) onInsert(what.id, index);
    if (what?.kind === "stop" && onReorder && dragging !== null && dragging !== index) onReorder(dragging, Math.min(index, stops.length - 1));
    setDragging(null);
    setTarget(null);
  };

  return (
    <Sheet label={label} onClose={onClose} size="wide">
      <header className="flex items-start gap-4 border-b border-go-rule px-6 pt-5 pb-4">
        <div className="min-w-0 flex-1">
          <p className="text-[13px] text-go-secondary">{kicker}</p>
          <h2 className="text-[22px] font-medium text-go-ink">{title}</h2>
        </div>
        <button type="button" onClick={onClose} aria-label="Close" className="mt-1 rounded-full p-2 hover:bg-go-subtle">
          <Icon name="close" />
        </button>
      </header>

      <div className="flex min-h-0 flex-1 flex-col overflow-y-auto">
        {banner && <div className="px-6 pt-4">{banner}</div>}
        <div className="grid min-h-[420px] flex-1 grid-cols-1 md:grid-cols-[265px_minmax(0,1fr)_280px]">
          <section aria-label={left.title} className="flex flex-col gap-1 border-go-rule px-6 py-4 md:border-r">
            <h3 className="text-[15px] font-medium text-go-ink">{left.title}</h3>
            <p className="pb-2 text-xs text-go-placeholder">{left.hint}</p>
            {left.body}
          </section>

          <section aria-label="Trip stop order" className="flex min-w-0 flex-col gap-2.5 px-5 py-4">
            <h3 className="text-[15px] font-medium text-go-ink">Trip stop order</h3>
            <div className="grid grid-cols-2 gap-2 lg:grid-cols-4">
              {tiles.map((tile) => (
                <div key={tile.label} className="flex flex-col gap-0.5 rounded-go-input border border-go-rule px-2.5 py-2">
                  <span className="text-[11px] text-go-secondary">{tile.label}</span>
                  <span className={cx("text-[15px] font-medium", tile.warn ? "text-go-warning-text" : "text-go-teal")}>{tile.value}</span>
                  <span className="h-1 rounded-full bg-go-surface">
                    <span className={cx("block h-1 rounded-full", tile.warn ? "bg-go-warning" : "bg-go-teal")} style={{ width: `${Math.min(100, tile.percent ?? 0)}%` }} />
                  </span>
                  <span className="truncate text-[10px] text-go-secondary">{tile.note}</span>
                </div>
              ))}
            </div>
            <p className="flex items-center gap-3 px-3 py-1 text-[13px] text-go-secondary">
              <span aria-hidden className="size-2.5 rounded-full bg-go-secondary" />
              <span className="flex-1">{depart.place}</span>
              <span className="font-medium text-go-ink tabular-nums">{depart.time}</span>
            </p>
            <ol
              aria-label="Stops in order"
              onDragOver={(event) => {
                if (!droppable) return;
                event.preventDefault();
                if (event.target === event.currentTarget) setTarget(stops.length);
              }}
              onDragLeave={(event) => event.target === event.currentTarget && setTarget(null)}
              onDrop={(event) => drop(event, stops.length)}
              className={cx("flex min-h-[64px] flex-col gap-2 rounded-go-input", target === stops.length && "pb-3 shadow-[inset_0_-3px_0_0_var(--color-go-teal)]")}
            >
              {stops.length === 0 && (
                <li className="rounded-go-input border border-dashed border-go-rule px-3 py-5 text-center text-[13px] text-go-secondary">
                  {onInsert ? "No stops: the trip is removed. Drag an order here to keep it." : "No stops."}
                </li>
              )}
              {stops.map((stop, index) => (
                <li
                  key={stop.orderId}
                  draggable={Boolean(onReorder || onDefer)}
                  onDragStart={(event) => {
                    setDragging(index);
                    event.dataTransfer.setData("text/plain", STOP + stop.orderId);
                    event.dataTransfer.effectAllowed = "move";
                  }}
                  onDragEnd={() => (setDragging(null), setTarget(null))}
                  onDragOver={(event) => {
                    if (!droppable) return;
                    event.preventDefault();
                    setTarget(index);
                  }}
                  onDrop={(event) => (event.stopPropagation(), drop(event, index))}
                  className={cx(
                    "flex cursor-grab items-center gap-3 rounded-go-input border bg-go-card px-3 py-2 active:cursor-grabbing",
                    stop.tag ? "border-[1.5px] border-go-teal" : "border-go-rule",
                    dragging === index && "opacity-50",
                    target === index && dragging !== index && "shadow-[0_-3px_0_0_var(--color-go-teal)]",
                  )}
                >
                  <span className="w-3 text-[13px] text-go-ink">{index + 1}</span>
                  <span className="min-w-0 flex-1">
                    <span className="flex items-center gap-1.5 text-[14px] font-medium text-go-ink">
                      {(onReorder || onDefer) && <span aria-hidden className="cursor-grab text-go-secondary">⠿</span>}
                      <span className="truncate">{stop.title}</span>
                    </span>
                    <span className="block truncate text-xs text-go-secondary">{stop.sub}</span>
                    {stop.note && <span className="block text-xs font-medium text-go-warning-text">{stop.note}</span>}
                    {stop.tag && <span className="block text-xs font-medium text-go-teal">{stop.tag}</span>}
                  </span>
                  <span className="shrink-0 text-xs text-go-secondary tabular-nums">{stop.window}</span>
                  <span className="w-11 shrink-0 text-right text-[14px] font-medium text-go-ink tabular-nums">{stop.eta}</span>
                  {stop.share !== null && <span className="shrink-0 rounded-full bg-go-surface px-2 py-0.5 text-[11px] text-go-secondary">{`${stop.share}%`}</span>}
                  {onReorder && (
                    <span className="flex shrink-0 flex-col">
                      <button type="button" aria-label={`Move ${stop.title} earlier`} disabled={index === 0} onClick={() => onReorder(index, index - 1)} className="px-1 text-[10px] leading-none text-go-secondary disabled:opacity-30">
                        ▲
                      </button>
                      <button
                        type="button"
                        aria-label={`Move ${stop.title} later`}
                        disabled={index === stops.length - 1}
                        onClick={() => onReorder(index, index + 1)}
                        className="px-1 text-[10px] leading-none text-go-secondary disabled:opacity-30"
                      >
                        ▼
                      </button>
                    </span>
                  )}
                  {onDefer && !stop.tag && (
                    <button type="button" onClick={() => onDefer(stop.orderId)} className="shrink-0 rounded-full bg-go-warning-tint px-2 py-0.5 text-[11px] font-medium text-go-warning-text">
                      Defer
                    </button>
                  )}
                </li>
              ))}
            </ol>
            <p className="flex items-center gap-3 px-3 py-1 text-[13px] text-go-secondary">
              <span aria-hidden className="size-2.5 rounded-full bg-go-secondary" />
              <span className="flex-1">Back to depot</span>
              <span className="font-medium text-go-ink tabular-nums">{back.time}</span>
            </p>
            {(onReorder || onDefer) && (
              <p className="text-xs text-go-secondary">
                {onReorder ? "Drag ⠿ to change the stop order." : ""} {onDefer ? "Drag a stop to the right to defer it." : ""}
              </p>
            )}
          </section>

          <section
            aria-label={right.title}
            onDragOver={(event) => {
              if (!onDefer || dragging === null) return;
              event.preventDefault();
              setOver(true);
            }}
            onDragLeave={() => setOver(false)}
            onDrop={(event) => {
              event.preventDefault();
              setOver(false);
              const what = dragged(event);
              if (onDefer && what?.kind === "stop") onDefer(what.id);
              setDragging(null);
              setTarget(null);
            }}
            className="flex flex-col gap-1 border-go-rule bg-go-subtle px-5 py-4 md:border-l"
          >
            <h3 className="text-[15px] font-medium text-go-ink">{right.title}</h3>
            <p className="pb-2 text-xs text-go-secondary">{right.hint}</p>
            {right.body ??
              (onDefer && (
                <p className={cx("rounded-go-input border border-dashed px-3 py-4 text-center text-[13px] text-go-warning-text", over ? "border-go-warning bg-go-warning-tint" : "border-go-warning/60")}>
                  Drag a stop here to defer it
                </p>
              ))}
          </section>
        </div>
      </div>

      {chips.length > 0 && (
        <div className="flex flex-wrap items-center gap-1.5 border-t border-go-rule bg-go-subtle px-6 py-2.5">
          <span className="pr-1 text-xs text-go-secondary">Checks</span>
          {chips.map((chip, index) => (
            <span
              key={`${chip.text}-${index}`}
              title={chip.title}
              className={cx(
                "rounded-full px-2.5 py-1 text-xs font-medium",
                chip.ok === true ? "bg-go-success-tint text-go-teal" : chip.ok === "warn" ? "bg-go-warning-tint text-go-warning-text" : "bg-go-danger-tint text-go-danger-strong",
              )}
            >
              {`${chip.ok === true ? "✓" : chip.ok === "warn" ? "!" : "✕"} ${chip.text}`}
            </span>
          ))}
        </div>
      )}

      <footer className="flex flex-wrap items-center gap-3 border-t border-go-rule px-6 py-3.5">
        <div className="min-w-0 flex-1 text-[13px] text-go-secondary">{footer}</div>
        <SecondaryButton onClick={onClose}>Cancel</SecondaryButton>
        <PrimaryButton disabled={accept.disabled} busy={accept.busy} onClick={accept.onClick}>
          {accept.busy ? "Sending…" : accept.label}
        </PrimaryButton>
      </footer>
    </Sheet>
  );
}

/** Two lists the same orders in the same order. */
export function sameOrder(a: string[], b: string[]): boolean {
  return a.length === b.length && a.every((id, index) => id === b[index]);
}

export function moveItem<T>(list: T[], from: number, to: number): T[] {
  const next = [...list];
  const [item] = next.splice(from, 1);
  next.splice(to, 0, item!);
  return next;
}
