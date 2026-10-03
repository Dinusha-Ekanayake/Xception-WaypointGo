"use client";

import { useEffect, useState, type ReactNode } from "react";
import { cx, formatClock } from "@shared/ui";
import { BigButton, Sheet } from "../ui.tsx";
import { useT } from "../i18n.tsx";
import { CheckIcon, LockIcon } from "../icons.tsx";

// States around the load sheet, from Figma "08 Loader · Phone": 18 Order
// loaded with Undo, 19 Out of sequence, 20 Hand back, 21 Issue saved offline,
// 04 Trip already taken, and 23 Vehicle released.

export type ToastMessage = {
  /** "loaded" for a tick (green, the time); "saved" for an issue kept on this device (grey, Just now). */
  kind: "loaded" | "saved";
  title: string;
  detail?: string;
  /** A last line, such as who the issue goes to. */
  note?: string;
  at: Date;
  undo?: () => void;
};

/**
 * 18 and 21: the confirmation slides up from the bottom. It does not dim or
 * block the page, so the next item can be ticked straight away, and it goes by
 * itself; Undo is there while it shows.
 */
export function Toast({ message, onDone }: { message: ToastMessage; onDone: () => void }): React.JSX.Element {
  const tr = useT();
  useEffect(() => {
    const timer = window.setTimeout(onDone, message.undo ? 6000 : 5000);
    return () => window.clearTimeout(timer);
  }, [message, onDone]);
  const loaded = message.kind === "loaded";

  return (
    <div role="status" aria-live="polite" className="fixed inset-x-0 bottom-0 z-30 flex justify-center">
      <div className="flex w-full max-w-[560px] flex-col gap-5 rounded-t-[32px] bg-go-card px-5 pt-5 pb-7 text-go-ink shadow-[0_-10px_30px_rgba(0,0,0,0.15)]">
        <div className="flex flex-col gap-1 rounded-[24px] bg-go-canvas px-4 py-3.5">
          <div className="flex items-center justify-between text-[13px]">
            <span className={cx("flex items-center gap-1.5 font-medium", loaded ? "text-go-success" : "text-go-muted")}>
              <span aria-hidden className={cx("size-2 rounded-full", loaded ? "bg-go-success" : "bg-go-muted")} />
              {tr(loaded ? "Order loaded" : "Saved offline")}
            </span>
            <span className="text-go-muted">{loaded ? formatClock(message.at) : tr("Just now")}</span>
          </div>
          <span className="text-[22px] font-medium">{message.title}</span>
          {message.detail && <span className="text-[14px] text-go-muted">{message.detail}</span>}
          {message.note && <span className="text-[13px]">{message.note}</span>}
        </div>
        {message.undo ? (
          <div className="grid grid-cols-2 gap-3">
            <BigButton
              tone="grey"
              size="l"
              onClick={() => {
                message.undo?.();
                onDone();
              }}
            >
              {tr("Undo")}
            </BigButton>
            <BigButton tone="ink" size="l" onClick={onDone}>
              {tr("Done")}
            </BigButton>
          </div>
        ) : (
          <BigButton tone="ink" size="l" onClick={onDone}>
            {tr("Keep loading")}
          </BigButton>
        )}
      </div>
    </div>
  );
}

/** The card Figma uses for a short question over the page: an icon, a title, a line, and a grey note. */
function Confirm({
  label,
  title,
  body,
  note,
  tint = "neutral",
  onClose,
  children,
}: {
  label: string;
  title: string;
  body: string;
  note?: string;
  tint?: "neutral" | "mint";
  onClose: () => void;
  children: ReactNode;
}): React.JSX.Element {
  return (
    <Sheet label={label} onClose={onClose} placement="center">
      <div className="flex items-center gap-4">
        <span className={cx("flex size-14 shrink-0 items-center justify-center rounded-full", tint === "mint" ? "bg-go-success-tint text-go-success" : "bg-go-surface text-go-ink")}>
          <LockIcon />
        </span>
        <h2 className="text-[24px] leading-tight font-medium">{title}</h2>
      </div>
      <p className="text-[16px] text-go-muted">{body}</p>
      {note && <p className="rounded-[16px] bg-go-surface px-4 py-3 text-[14px] text-go-muted">{note}</p>}
      <div className="flex flex-col gap-3">{children}</div>
    </Sheet>
  );
}

const stopName = (n: number) => `Stop ${String(n).padStart(2, "0")}`;

/** 19: ticking a stop that unloads earlier blocks the stops that should go on first. */
export function OutOfSequence({
  firstStop,
  thisStop,
  loadsLast,
  onAnyway,
  onClose,
}: {
  firstStop: number;
  thisStop: number;
  /** This stop is the trip's first delivery, so it goes on the vehicle last. */
  loadsLast: boolean;
  onAnyway: () => void;
  onClose: () => void;
}): React.JSX.Element {
  const tr = useT();
  const first = stopName(firstStop);
  const self = stopName(thisStop);
  return (
    <Confirm
      label={tr("Out of sequence")}
      title={tr("Load {stop} first?", { stop: first })}
      body={
        loadsLast
          ? tr("{self} loads last. Loading it now blocks {stop}.", { self, stop: first })
          : tr("{self} loads after {stop}. Loading it now blocks {stop}.", { self, stop: first })
      }
      note={tr("Only if it's really on the vehicle. Dispatch will see.")}
      onClose={onClose}
    >
      <BigButton tone="grey" size="l" onClick={onAnyway}>
        {tr("Mark loaded anyway")}
      </BigButton>
      <BigButton tone="ink" size="l" onClick={onClose}>
        {tr("Load {stop} first", { stop: first })}
      </BigButton>
    </Confirm>
  );
}

/** 20: hand the trip back. The checks already made keep the loader's name and time (R-LOD-11). */
export function HandBack({
  vehicleId,
  checked,
  nextUp,
  busy,
  onConfirm,
  onClose,
}: {
  vehicleId: string;
  checked: number;
  /** The next item to load, so the next loader knows where to carry on. */
  nextUp: string | null;
  busy: boolean;
  onConfirm: () => void;
  onClose: () => void;
}): React.JSX.Element {
  const tr = useT();
  return (
    <Confirm
      label={tr("Hand back this trip")}
      title={tr("Hand back {vehicle}?", { vehicle: vehicleId })}
      body={tr(checked === 1 ? "Your {n} loaded order keeps your name and time." : "Your {n} loaded orders keep your name and time.", { n: checked })}
      note={nextUp ? tr("Next up: {item}. Tell the next loader.", { item: nextUp }) : undefined}
      tint="mint"
      onClose={onClose}
    >
      <BigButton tone="grey" size="l" onClick={onClose}>
        {tr("Keep loading")}
      </BigButton>
      <BigButton tone="ink" size="l" disabled={busy} onClick={onConfirm}>
        {tr("Hand back")}
      </BigButton>
    </Confirm>
  );
}

/** 04: another loader took the trip first (R-LOD-11). */
export function TripTaken({ vehicleId, onBack }: { vehicleId: string; onBack: () => void }): React.JSX.Element {
  const tr = useT();
  return (
    <Confirm
      label={tr("Trip already taken")}
      title={tr("{vehicle} was just taken", { vehicle: vehicleId })}
      body={tr("Someone else took it first. One loader per trip.")}
      note={tr("Need it? Ask them to hand it back, or call dispatch.")}
      onClose={onBack}
    >
      <BigButton tone="ink" size="l" onClick={onBack}>
        {tr("See available trips")}
      </BigButton>
    </Confirm>
  );
}

/** 23: the vehicle is released and the driver's run sheet is live. Returns to departures by itself. */
export function Released({
  vehicleId,
  summary,
  onBack,
}: {
  vehicleId: string;
  summary: string;
  onBack: () => void;
}): React.JSX.Element {
  const tr = useT();
  const [seconds, setSeconds] = useState(3);
  useEffect(() => {
    if (seconds <= 0) {
      onBack();
      return;
    }
    const timer = window.setTimeout(() => setSeconds((s) => s - 1), 1000);
    return () => window.clearTimeout(timer);
  }, [seconds, onBack]);

  return (
    <section aria-live="polite" className="mx-5 mt-[10vh] flex flex-col items-center gap-3 rounded-[32px] bg-go-card px-6 py-8 text-center text-go-ink shadow-go-card">
      <span className="flex size-16 items-center justify-center rounded-full bg-go-success-tint text-go-success">
        <CheckIcon />
      </span>
      <h2 className="text-[28px] font-semibold">{tr("{vehicle} released", { vehicle: vehicleId })}</h2>
      <p className="text-[15px] text-go-muted">{summary}</p>
      <p className="text-[15px] text-go-muted">{tr("Run sheet live · trip unlocked · dispatch notified")}</p>
      <BigButton tone="ink" size="l" onClick={onBack}>
        {tr("Back to departures")}
      </BigButton>
      <p className="text-[13px] text-go-muted">{tr("Going back automatically in {n} s", { n: seconds })}</p>
    </section>
  );
}
