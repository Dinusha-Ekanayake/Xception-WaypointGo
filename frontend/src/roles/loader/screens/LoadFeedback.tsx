"use client";

import { useEffect, useState } from "react";
import { Icon } from "@shared/ui";
import { BigButton, Sheet } from "../ui.tsx";

// Small states around the load sheet, from Figma "08 Loader · Phone":
// E5 order loaded with Undo, E6 out-of-sequence warning, E7 hand back,
// E8 issue saved offline, and E11 vehicle released.

export type ToastMessage = { title: string; detail?: string; undo?: () => void };

/** E5 and E8: a short confirmation at the bottom of the screen, with Undo when it applies. */
export function Toast({ message, onDone }: { message: ToastMessage; onDone: () => void }): React.JSX.Element {
  useEffect(() => {
    const timer = window.setTimeout(onDone, message.undo ? 6000 : 4000);
    return () => window.clearTimeout(timer);
  }, [message, onDone]);

  return (
    <div role="status" aria-live="polite" className="fixed inset-x-0 bottom-4 z-30 flex justify-center px-4">
      <div className="flex w-full max-w-[480px] items-center gap-3 rounded-[20px] bg-[#0b2a1a] px-4 py-3 text-white shadow-[0_10px_30px_rgba(0,0,0,0.25)]">
        <div className="flex min-w-0 flex-1 flex-col">
          <span className="text-[15px] font-medium">{message.title}</span>
          {message.detail && <span className="text-[13px] text-white/75">{message.detail}</span>}
        </div>
        {message.undo && (
          <button
            type="button"
            onClick={() => {
              message.undo?.();
              onDone();
            }}
            className="min-h-12 shrink-0 rounded-full bg-white/15 px-4 text-[14px] font-medium"
          >
            Undo
          </button>
        )}
        <button type="button" onClick={onDone} className="min-h-12 shrink-0 px-2 text-[14px] font-medium">
          Done
        </button>
      </div>
    </div>
  );
}

/** E6: ticking a stop that unloads earlier blocks the stops that should go on first. */
export function OutOfSequence({
  firstStop,
  thisStop,
  onAnyway,
  onClose,
}: {
  firstStop: number;
  thisStop: number;
  onAnyway: () => void;
  onClose: () => void;
}): React.JSX.Element {
  const stop = (n: number) => `Stop ${String(n).padStart(2, "0")}`;
  return (
    <Sheet label="Out of sequence" onClose={onClose}>
      <h2 className="text-[26px] font-semibold">Load {stop(firstStop)} first?</h2>
      <p className="text-[15px] text-go-muted">
        {stop(thisStop)} loads after {stop(firstStop)}. Loading it now blocks {stop(firstStop)} at the doors.
      </p>
      <p className="text-[14px] text-go-muted">Only if it&apos;s really on the vehicle. Dispatch will see.</p>
      <div className="grid gap-3 md:grid-cols-2">
        <BigButton tone="plain" size="l" onClick={onAnyway}>
          Mark loaded anyway
        </BigButton>
        <BigButton size="l" onClick={onClose}>
          Load {stop(firstStop)} first
        </BigButton>
      </div>
    </Sheet>
  );
}

/** E11: the vehicle is released and the driver's run sheet is live. Returns to departures by itself. */
export function Released({
  vehicleId,
  summary,
  onBack,
}: {
  vehicleId: string;
  summary: string;
  onBack: () => void;
}): React.JSX.Element {
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
    <section aria-live="polite" className="mx-5 flex flex-col items-center gap-3 rounded-[31px] bg-white px-6 py-8 text-center shadow-[0_5px_20px_rgba(0,0,0,0.09)]">
      <span className="flex size-16 items-center justify-center rounded-full bg-go-signal">
        <Icon name="check-white" />
      </span>
      <h2 className="text-[28px] font-semibold">{vehicleId} released</h2>
      <p className="text-[15px] text-go-muted">{summary}</p>
      <p className="text-[15px] font-medium text-go-success">Run sheet live · trip unlocked · dispatch notified</p>
      <BigButton size="l" onClick={onBack}>
        Back to departures
      </BigButton>
      <p className="text-[13px] text-go-muted">Going back automatically in {seconds} s</p>
    </section>
  );
}

/** E7: hand the trip back. The checks already made keep the loader's name and time (R-LOD-11). */
export function HandBack({
  vehicleId,
  checked,
  busy,
  onConfirm,
  onClose,
}: {
  vehicleId: string;
  checked: number;
  busy: boolean;
  onConfirm: () => void;
  onClose: () => void;
}): React.JSX.Element {
  return (
    <Sheet label="Hand back this trip" onClose={onClose}>
      <h2 className="text-[26px] font-semibold">Hand back {vehicleId}?</h2>
      <p className="text-[15px] text-go-muted">
        Your {checked} checked {checked === 1 ? "order keeps" : "orders keep"} your name and time. Another loader can take the
        trip and carry on.
      </p>
      <div className="grid gap-3 md:grid-cols-2">
        <BigButton tone="muted" size="l" onClick={onClose}>
          Keep loading
        </BigButton>
        <BigButton size="l" disabled={busy} onClick={onConfirm}>
          Hand back
        </BigButton>
      </div>
    </Sheet>
  );
}
