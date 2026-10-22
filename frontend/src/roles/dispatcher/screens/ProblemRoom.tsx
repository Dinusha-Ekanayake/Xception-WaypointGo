"use client";

import { useState } from "react";
import { send } from "@shared/api/commands";
import { announceChange } from "@shared/api/changes";
import { friendlyError } from "@shared/api/problem";
import { IssueCommandKind, type DecisionView, type RoomState, type RoomView, type RunSheetStopView } from "@shared/domain/types";
import { newId, postCommand, resolveReport } from "@shared/messaging/useThread";
import { clockOn } from "@shared/wording";
import { cx } from "@shared/ui";
import { useCommand } from "../data/useCommand.ts";
import { useStoreContacts } from "../data/storeContacts.ts";
import { EndTripForm, SkipStopForm } from "./LiveTripSkip.tsx";

// Decision suggestions part 3 (R-ML-36, docs/issues/decision-suggestions/PLAN.md):
// while a problem is open on the trip, one room at the top of its chat. Each store
// still on the trip has a row with its state, and there is one next step for the
// dispatcher, chosen by rules from the stores' answers, never a guess: ask the
// stores, end the trip, skip one, call one, ask a dock, tell the driver. One tap
// does it, or opens its form already filled in; the dispatcher still confirms.

const PROBLEM: Record<string, string> = {
  BREAKDOWN: "broke down",
  ROAD_BLOCKED: "is held up by a blocked road",
  RUNNING_LATE: "is running late",
  STORE_CLOSED: "found the store closed",
  CANNOT_UNLOAD: "cannot unload",
};

function stateWords(state: RoomState, at: string | null): string {
  const t = at ? clockOn(at) : null;
  switch (state) {
    case "NOT_ASKED": return "Not asked yet";
    case "MALL_CANNOT_WAIT": return "Mall · can't receive late";
    case "IN_WINDOW": return t ? `In its window · expected ${t}` : "In its window";
    case "ASKED": return t ? `Asked · answer due ${t}` : "Asked";
    case "YES": return t ? `Yes · receives at ${t}` : "Yes";
    case "NO": return "No · can't receive";
    case "NO_ANSWER": return "No answer";
    case "DOCK_ASKED": return t ? `Asked about its dock · due ${t}` : "Asked about its dock";
    case "DOCK_YES": return "Dock: yes";
    case "DOCK_NO": return "Dock: refused";
    case "SKIPPED": return "Skipped · next day";
    case "DELIVERED": return "Done";
  }
}

const TONE: Partial<Record<RoomState, string>> = {
  YES: "text-go-success",
  DOCK_YES: "text-go-success",
  NO: "text-go-danger",
  DOCK_NO: "text-go-danger",
  NO_ANSWER: "text-go-warning-text",
  MALL_CANNOT_WAIT: "text-go-warning-text",
};

type Open = "end" | "skip" | null;

export default function ProblemRoom({
  room,
  threadId,
  tripId,
  vehicleId,
  stops,
  decisions,
  reportIds,
  online,
  onExtra,
}: {
  room: RoomView;
  threadId: string;
  tripId: string;
  vehicleId: string | null;
  /** The trip's stops from the run sheet, for the forms. */
  stops: RunSheetStopView[];
  /** The day's questions, for recording a store's answer given by phone. */
  decisions: DecisionView[];
  /** The open reports the room is about, resolved together once it is done. */
  reportIds: string[];
  online: boolean;
  /** Re-reads the room with another extra time. */
  onExtra: (minutes: number) => void;
}): React.JSX.Element {
  const { run, busy } = useCommand();
  const [open, setOpen] = useState<Open>(null);
  const [error, setError] = useState<string | null>(null);
  const [sending, setSending] = useState(false);
  const contacts = useStoreContacts(room.rows.map((r) => r.outletId));
  const next = room.next;
  const vehicle = vehicleId ?? "The vehicle";
  const target = next.stops[0] ?? null;
  const stop = target ? stops.find((s) => s.deliveryId === target.deliveryId) ?? null : null;
  const waiting = stops.filter((s) => s.outcome === "PENDING" || s.outcome === "ARRIVED");
  const changed = () => announceChange({ topic: "issue" });

  const sendAll = async (payloads: unknown[]) => {
    setSending(true);
    setError(null);
    try {
      for (const p of payloads) {
        const sent = await run(IssueCommandKind.askDecision, p, null);
        if (!sent.ok) throw sent.error;
      }
      changed();
    } catch (failure) {
      setError(friendlyError(failure));
    } finally {
      setSending(false);
    }
  };
  const byPhone = async (answer: "yes" | "no") => {
    const asked = [...decisions]
      .filter((d) => d.deliveryId === target?.deliveryId && d.state !== "withdrawn")
      .sort((a, b) => b.askedAt.localeCompare(a.askedAt))[0];
    if (!asked) return;
    const sent = await run(IssueCommandKind.answerDecision, { requestId: asked.requestId, answer, byPhone: true }, asked.rowVersion);
    if (sent.ok) changed();
    else setError(friendlyError(sent.error));
  };
  const tellDriver = async (body: string) => {
    setSending(true);
    try {
      await send(postCommand({ threadId, to: "driver", body, clientMessageId: newId() }));
      announceChange({ topic: "message" });
    } catch (failure) {
      setError(friendlyError(failure));
    } finally {
      setSending(false);
    }
  };
  const handled = async () => {
    setSending(true);
    try {
      for (const id of reportIds) await resolveReport(id, `Handled: ${next.why}`);
      announceChange({ topic: "message" });
    } finally {
      setSending(false);
    }
  };

  if (open === "end") return <EndTripForm tripId={tripId} openStops={waiting} onDone={() => setOpen(null)} />;
  if (open === "skip" && stop) {
    return <SkipStopForm stop={stop} initialThen="NEXT_DAY" initialReason={next.why} onDone={() => setOpen(null)} />;
  }

  const title = PROBLEM[room.situation] && ["BREAKDOWN", "ROAD_BLOCKED", "RUNNING_LATE"].includes(room.situation)
    ? `${vehicle} ${PROBLEM[room.situation]}`
    : `${target?.outletId ?? "A store"}: the driver ${PROBLEM[room.situation] ?? "reported a problem"}`;
  const manager = target ? (contacts.data?.[target.outletId] ?? [])[0] ?? null : null;
  const primary = "min-h-9 rounded-full bg-go-ink px-4 text-[13px] font-medium text-go-card disabled:opacity-40";
  const quiet = "min-h-9 rounded-full bg-go-card px-3 text-[13px] font-medium text-go-teal shadow-[inset_0_0_0_1px_rgb(0_0_0/0.08)] disabled:opacity-40";
  const off = !online || busy || sending;

  return (
    <section aria-label="Problem room" className="flex max-h-[45dvh] flex-col gap-3 overflow-y-auto rounded-go-card-s bg-go-surface px-3.5 py-3">
      <span className="text-[13px] font-medium text-go-ink">{`Problem · ${title}`}</span>
      <ul aria-label="Stores on the trip" className="flex flex-col gap-1">
        {room.rows.map((r) => (
          <li key={r.deliveryId} className="flex items-baseline justify-between gap-3 text-[13px]">
            <span className="font-medium text-go-ink">{r.outletId}</span>
            <span className={cx("text-right", TONE[r.state] ?? "text-go-secondary")}>{stateWords(r.state, r.at)}</span>
          </li>
        ))}
      </ul>

      <div role="group" aria-label="Next step" className="flex flex-col gap-2 rounded-xl bg-go-card px-3 py-2.5">
        <p className="text-[12px] text-go-secondary">{next.why}</p>
        {next.kind === "ASK" && (
          <>
            {next.extras.length > 0 && (
              <div role="radiogroup" aria-label="Extra time" className="flex flex-wrap items-center gap-1.5 text-[12px] text-go-secondary">
                <span>The van needs about</span>
                {next.extras.map((m) => (
                  <button
                    key={m}
                    type="button"
                    role="radio"
                    aria-checked={next.extraMinutes === m}
                    onClick={() => onExtra(m)}
                    className={cx("min-h-7 rounded-full px-2.5 font-medium", next.extraMinutes === m ? "bg-go-ink text-go-card" : "bg-go-subtle text-go-ink")}
                  >
                    {m < 60 ? `+${m} min` : `+${m / 60} h`}
                  </button>
                ))}
                <span>more</span>
              </div>
            )}
            <button
              type="button"
              disabled={off}
              className={primary}
              onClick={() => void sendAll(next.stops.map((t) => ({ kind: "receive_late", deliveryId: t.deliveryId, receiveAt: t.receiveAt })))}
            >
              {sending ? "Asking…" : next.stops.length === 1
                ? `Ask ${next.stops[0]!.outletId}: receive at about ${clockOn(next.stops[0]!.receiveAt!)}?`
                : `Ask the ${next.stops.length} stores: ${next.stops.map((t) => `${t.outletId} ${clockOn(t.receiveAt!)}`).join(", ")}`}
            </button>
          </>
        )}
        {next.kind === "END_TRIP" && (
          <button type="button" disabled={off} className={primary} onClick={() => setOpen("end")}>End the trip</button>
        )}
        {next.kind === "SKIP" && target && (
          <button type="button" disabled={off || !stop} className={primary} onClick={() => setOpen("skip")}>{`Skip ${target.outletId} to next day`}</button>
        )}
        {next.kind === "CALL" && target && (
          <div className="flex flex-col gap-1.5">
            <a className={cx(primary, "inline-flex items-center justify-center")} href={manager?.phone ? `tel:${manager.phone}` : undefined}>
              {manager?.phone ? `Call ${target.outletId} · ${manager.name} · ${manager.phone}` : `Call ${target.outletId} · no phone on their profile`}
            </a>
            <span role="group" aria-label={`What ${target.outletId} said by phone`} className="flex flex-wrap gap-1.5">
              <button type="button" disabled={off} className={quiet} onClick={() => void byPhone("yes")}>They said Yes</button>
              <button type="button" disabled={off} className={quiet} onClick={() => void byPhone("no")}>They said No</button>
            </span>
          </div>
        )}
        {next.kind === "ASK_DOCK" && target && next.about && (
          <button
            type="button"
            disabled={off}
            className={primary}
            onClick={() => void sendAll([{ kind: "dock_help", deliveryId: target.deliveryId, aboutDeliveryId: next.about!.deliveryId }])}
          >
            {`Ask ${target.outletId}: may the crew unload ${next.about.outletId}'s goods at its dock?`}
          </button>
        )}
        {next.kind === "TELL_DRIVER_DOCK" && target && next.about && (
          <button
            type="button"
            disabled={off}
            className={primary}
            onClick={() => void tellDriver(`${target.outletId} can't host the unloading. Move ${next.about!.outletId}'s goods inside the van at the kerb before ${target.outletId}, and tell me if you can't.`)}
          >
            Tell the driver to move the goods inside the van
          </button>
        )}
        {next.kind === "DONE" && reportIds.length > 0 && (
          <button type="button" disabled={off} className={quiet} onClick={() => void handled()}>Mark the problem handled</button>
        )}
        {next.endTripToo && next.kind !== "END_TRIP" && (
          <button type="button" disabled={off} onClick={() => setOpen("end")} className="self-start text-[12px] font-medium text-go-teal disabled:opacity-40">
            The driver can&apos;t go on: end the trip
          </button>
        )}
        {error && <p role="alert" className="text-[12px] text-go-danger">{error}</p>}
      </div>
    </section>
  );
}
