"use client";

import { useState } from "react";
import { ApiError } from "@shared/api/problem";
import type { MessageAudience } from "@shared/domain/types";
import { sendNow } from "@shared/messaging/senders";
import { newId, postCommand, useTripThread } from "@shared/messaging/useThread";
import { cx } from "@shared/ui";
import { clock } from "@shared/wording";
import { lateBy, tripOf, updateText } from "../data/threads.ts";
import type { Run } from "../data/liveDesk.ts";
import { useDispatcherInbox } from "../inbox.tsx";
import { Action } from "./LiveParts.tsx";

// Figma "05 Live · trip", "Send an update" (issue #136): a message on the
// trip's thread to the driver, the store expected next, or everyone on the
// trip, written from the expected arrival. "Voice" and "All messages" open the
// thread in the side sheet.

type Choice = "driver" | "next" | "all";

export default function LiveTripUpdate({ run, date, online }: { run: Run; date: string; online: boolean }): React.JSX.Element {
  const next = run.day.current;
  const tripId = tripOf(run);
  const thread = useTripThread(tripId);
  const inbox = useDispatcherInbox();
  const [choice, setChoice] = useState<Choice>(next ? "next" : "driver");
  const [body, setBody] = useState(() => updateText(next, lateBy(next, date)));
  const [state, setState] = useState<{ sending: boolean; sent: string | null; error: string | null }>({ sending: false, sent: null, error: null });
  const threadId = thread.data?.threadId ?? null;
  const closed = thread.data ? !thread.data.open : false;

  const target: { to: MessageAudience; outletId?: string; label: string } =
    choice === "driver"
      ? { to: "driver", label: "the driver" }
      : choice === "next" && next
        ? { to: "outlet", outletId: next.outletId, label: next.outletId }
        : { to: "all", label: "everyone on the trip" };

  const send = async () => {
    if (!threadId || !body.trim()) return;
    setState({ sending: true, sent: null, error: null });
    try {
      await sendNow(postCommand({ threadId, body: body.trim(), to: target.to, ...(target.outletId ? { outletId: target.outletId } : {}), clientMessageId: newId() }));
      setState({ sending: false, sent: `Sent to ${target.label} at ${clock(new Date())}`, error: null });
    } catch (failure) {
      const message = failure instanceof ApiError ? failure.problem.violations[0]?.message ?? failure.message : "Could not send. Try again.";
      setState({ sending: false, sent: null, error: message });
    }
  };

  const options: Array<[Choice, string]> = [["driver", "Driver"], ...(next ? [["next", next.outletId] as [Choice, string]] : []), ["all", "Everyone"]];
  const unavailable = !online || !threadId || closed;

  return (
    <>
      <div className="flex items-baseline justify-between gap-2">
        <h2 className="text-[15px] font-medium text-go-ink">Send an update</h2>
        {threadId && (
          <button type="button" onClick={() => inbox?.openThread({ threadId })} className="text-[12.5px] font-medium text-go-teal">
            All messages ›
          </button>
        )}
      </div>
      <div role="radiogroup" aria-label="Send to" className="flex flex-wrap gap-1.5">
        {options.map(([value, label]) => (
          <button
            key={value}
            type="button"
            role="radio"
            aria-checked={choice === value}
            onClick={() => setChoice(value)}
            className={cx("rounded-full px-2.5 py-1 text-[12px] font-medium", choice === value ? "bg-go-ink text-white" : "bg-go-surface text-go-ink")}
          >
            {label}
          </button>
        ))}
      </div>
      <textarea
        rows={2}
        maxLength={1000}
        aria-label="Message"
        value={body}
        disabled={unavailable}
        onChange={(e) => setBody(e.target.value)}
        className="w-full resize-none rounded-xl bg-go-subtle px-3 py-2.5 text-[13px] text-go-ink disabled:text-go-secondary"
      />
      <div className="flex gap-2">
        <Action disabled={unavailable} onClick={() => threadId && inbox?.openThread({ threadId, draft: { address: { to: target.to, outletId: target.outletId ?? null } } })}>
          Voice
        </Action>
        <Action primary disabled={unavailable || state.sending || !body.trim()} className="flex-1" onClick={() => void send()}>
          {state.sending ? "Sending" : `Send to ${target.label === "everyone on the trip" ? "everyone" : target.label}`}
        </Action>
      </div>
      {state.sent && <p role="status" className="text-[11px] text-go-teal">{state.sent}</p>}
      {state.error && <p role="alert" className="text-[11px] text-go-danger-strong">{state.error}</p>}
      {!thread.loading && !threadId && <p className="text-[11px] text-go-secondary">This trip has no messages yet. Its thread opens when the plan is published.</p>}
      {closed && <p className="text-[11px] text-go-secondary">This trip is over; its messages can be read but not added to.</p>}
      {!online && <p className="text-[11px] text-go-warning-text">Offline: sending is paused.</p>}
    </>
  );
}
