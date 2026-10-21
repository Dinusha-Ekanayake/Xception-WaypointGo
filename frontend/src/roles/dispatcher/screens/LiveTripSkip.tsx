"use client";

import { useState } from "react";
import { ExecutionCommandKind, type RunSheetStopView } from "@shared/domain/types";
import { Notice, PrimaryButton, SecondaryButton, Segmented, Sheet } from "@shared/ui";
import { stops as stopsWord } from "@shared/wording";
import { useCommand } from "../data/useCommand.ts";
import Refusal from "./Refusal.tsx";

// R-EXE-31: the dispatcher's two decisions on a trip on the road. Skip store
// takes one stop off, reached or not: its order goes to the next delivery day,
// or is cancelled and the goods come back to the depot. End trip sends every
// stop not yet done to the next day. Each asks why (rule 8), says who is told,
// and sends the stop's version, so a stop the driver just recorded is refused
// rather than overwritten.
//
// One form per decision, shown two ways: in a dialog on the Live trip page, and
// opened in place under a report in the trip's chat, where a dialog cannot sit
// over the chat drawer.

type Then = "NEXT_DAY" | "CANCEL";

const field = "min-h-11 w-full rounded-go-input border border-go-rule bg-go-surface px-3 text-[14px] font-normal outline-none focus:border-go-teal";
const link = "min-h-9 self-start rounded-full px-3 text-[13px] font-medium text-go-teal hover:bg-go-surface disabled:opacity-40";
const REASON_MIN = 3;

/** A stop the dispatcher may still take off: not delivered, failed or already skipped. */
export function skippable(stop: Pick<RunSheetStopView, "outcome">): boolean {
  return stop.outcome === "PENDING" || stop.outcome === "ARRIVED";
}

/** Skip store: a dialog from the trip page, or in place under a report (`inline`). */
export function SkipStopButton({ stop, online, inline = false }: { stop: RunSheetStopView; online: boolean; inline?: boolean }): React.JSX.Element | null {
  const [open, setOpen] = useState(false);
  if (!skippable(stop)) return null;
  if (inline && open) return <SkipStopForm stop={stop} onDone={() => setOpen(false)} />;
  return (
    <>
      <button type="button" disabled={!online} onClick={() => setOpen(true)} className={link}>
        {inline ? `Skip ${stop.outletId}` : "Skip store"}
      </button>
      {!inline && open && (
        <Sheet label={`Skip ${stop.outletId}`} onClose={() => setOpen(false)}>
          {/* Opened from a right-aligned table cell, whose alignment the panel would inherit. */}
          <div className="flex flex-col gap-4 text-left">
            <h2 className="text-[20px] font-medium text-go-ink">{`Skip ${stop.outletId}`}</h2>
            <SkipStopForm stop={stop} onDone={() => setOpen(false)} />
          </div>
        </Sheet>
      )}
    </>
  );
}

function SkipStopForm({ stop, onDone }: { stop: RunSheetStopView; onDone: () => void }): React.JSX.Element {
  const { busy, run } = useCommand();
  const [then, setThen] = useState<Then>("NEXT_DAY");
  const [reason, setReason] = useState("");
  const [error, setError] = useState<Error | null>(null);
  const ready = reason.trim().length >= REASON_MIN;

  const send = async () => {
    const sent = await run(ExecutionCommandKind.skipStop, { deliveryId: stop.deliveryId, then, reason: reason.trim() }, stop.rowVersion);
    if (sent.ok) onDone();
    else setError(sent.error);
  };

  return (
    <div role="group" aria-label={`Skip ${stop.outletId}`} className="flex flex-col gap-3 text-left">
      <p className="text-[13px] text-go-secondary">
        {stop.outcome === "ARRIVED" ? "The driver is at this stop. " : ""}
        The stop comes off the trip. The store reads why in the trip&apos;s messages and by push, and the driver&apos;s route drops it.
      </p>
      <Segmented
        label="What happens to the order"
        value={then}
        onChange={setThen}
        options={[
          { value: "NEXT_DAY", label: "Next day" },
          { value: "CANCEL", label: "Cancel" },
        ]}
      />
      <p className="text-[13px] text-go-ink">
        {then === "NEXT_DAY"
          ? "The goods go back to the depot and the same order goes first on the next delivery day."
          : "The goods go back to the depot and the order is cancelled. Depot staff put the stock right by hand; the warehouse is not called."}
      </p>
      <label className="flex flex-col gap-1 text-[13px] font-medium text-go-ink">
        Why
        <input value={reason} maxLength={500} onChange={(e) => setReason(e.target.value)} placeholder="Store closed, nobody answers" className={field} />
      </label>
      {error && <Refusal error={error} what="the skip" />}
      <div className="flex flex-wrap gap-2">
        <PrimaryButton disabled={!ready} busy={busy} onClick={() => void send()}>
          {busy ? "Sending…" : then === "NEXT_DAY" ? "Skip to next day" : "Skip and cancel"}
        </PrimaryButton>
        <SecondaryButton onClick={onDone}>Keep the stop</SecondaryButton>
      </div>
    </div>
  );
}

/** End trip: a dialog from the trip page, or in place in the chat's header (`inline`). */
export function EndTripButton({
  tripId,
  open: openStops,
  online,
  inline = false,
}: {
  tripId: string;
  open: RunSheetStopView[];
  online: boolean;
  inline?: boolean;
}): React.JSX.Element | null {
  const [open, setOpen] = useState(false);
  if (openStops.length === 0) return null;
  if (inline && open) return <EndTripForm tripId={tripId} openStops={openStops} onDone={() => setOpen(false)} />;
  return (
    <>
      {inline ? (
        <button type="button" disabled={!online} onClick={() => setOpen(true)} className={link}>
          End trip
        </button>
      ) : (
        <SecondaryButton disabled={!online} onClick={() => setOpen(true)}>
          End trip
        </SecondaryButton>
      )}
      {!inline && open && (
        <Sheet label="End trip" onClose={() => setOpen(false)}>
          <h2 className="text-[20px] font-medium text-go-ink">End trip</h2>
          <EndTripForm tripId={tripId} openStops={openStops} onDone={() => setOpen(false)} />
        </Sheet>
      )}
    </>
  );
}

function EndTripForm({ tripId, openStops, onDone }: { tripId: string; openStops: RunSheetStopView[]; onDone: () => void }): React.JSX.Element {
  const { busy, run } = useCommand();
  const [reason, setReason] = useState("");
  const [error, setError] = useState<Error | null>(null);
  const ready = reason.trim().length >= REASON_MIN;

  const send = async () => {
    const sent = await run(ExecutionCommandKind.endTrip, { tripId, reason: reason.trim() }, null);
    if (sent.ok) onDone();
    else setError(sent.error);
  };

  return (
    <div role="group" aria-label="End trip" className="flex flex-col gap-3 text-left">
      <Notice tone="warning" title={`${stopsWord(openStops.length)} not yet done ${openStops.length === 1 ? "goes" : "go"} to the next delivery day`}>
        {openStops.map((s) => s.outletId).join(", ")}. Each store is told why; delivered stops stand.
      </Notice>
      <label className="flex flex-col gap-1 text-[13px] font-medium text-go-ink">
        Why
        <input value={reason} maxLength={500} onChange={(e) => setReason(e.target.value)} placeholder="Vehicle broke down" className={field} />
      </label>
      {error && <Refusal error={error} what="ending the trip" />}
      <div className="flex flex-wrap gap-2">
        <PrimaryButton disabled={!ready} busy={busy} onClick={() => void send()}>
          {busy ? "Sending…" : `End trip · ${stopsWord(openStops.length)}`}
        </PrimaryButton>
        <SecondaryButton onClick={onDone}>Keep the trip</SecondaryButton>
      </div>
    </div>
  );
}
