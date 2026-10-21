"use client";

import { useState } from "react";
import { send } from "@shared/api/commands";
import { announceChange } from "@shared/api/changes";
import { friendlyError } from "@shared/api/problem";
import type { RunSheetStopView } from "@shared/domain/types";
import { eachStoreMessages } from "@shared/messaging/templates";
import { newId, postCommand } from "@shared/messaging/useThread";

// Decision suggestions, part 4: the dispatcher tells every store still waiting
// its own expected time, one message per store on the trip's thread, rather than
// one line for all in the stores' room. A preview first; nothing goes until Send.

export default function TellEachStore({
  threadId,
  stops,
  serviceDate,
  online,
}: {
  threadId: string;
  stops: RunSheetStopView[];
  serviceDate: string;
  online: boolean;
}): React.JSX.Element | null {
  const messages = eachStoreMessages({ stops, serviceDate });
  const [open, setOpen] = useState(false);
  const [sent, setSent] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  if (messages.length < 2) return null;

  const sendAll = async () => {
    setBusy(true);
    setError(null);
    try {
      for (const m of messages) {
        if (sent.includes(m.outletId)) continue;
        await send(postCommand({ threadId, to: "outlet", outletId: m.outletId, body: m.body, clientMessageId: newId() }));
        setSent((before) => [...before, m.outletId]);
      }
      announceChange({ topic: "message" });
      setOpen(false);
    } catch (failure) {
      // What went out stays sent; Send again sends only the rest.
      setError(friendlyError(failure));
    } finally {
      setBusy(false);
    }
  };

  if (!open) {
    return (
      <button
        type="button"
        disabled={!online}
        onClick={() => setOpen(true)}
        className="self-start rounded-full bg-go-surface px-3.5 py-1.5 text-[13px] font-medium text-go-teal disabled:opacity-40"
      >
        {`Tell each store waiting its time (${messages.length})`}
      </button>
    );
  }
  return (
    <div role="group" aria-label="Tell each store its time" className="flex flex-col gap-2 rounded-go-card-s bg-go-surface p-3 text-[13px]">
      <p className="font-medium text-go-ink">One message to each store, with its own time</p>
      <ul className="flex flex-col gap-1">
        {messages.map((m) => (
          <li key={m.outletId} className="text-go-ink">
            <span className="font-medium">{m.outletId}</span>
            {`: ${m.body}`}
            {sent.includes(m.outletId) && <span className="text-go-secondary"> · sent</span>}
          </li>
        ))}
      </ul>
      {error && <p role="alert" className="text-go-danger-strong">{error}</p>}
      <div className="flex gap-2">
        <button type="button" disabled={busy || !online} onClick={() => void sendAll()} className="min-h-9 rounded-full bg-go-ink px-4 font-medium text-white disabled:opacity-40">
          {busy ? "Sending…" : `Send ${messages.length - sent.length}`}
        </button>
        <button type="button" disabled={busy} onClick={() => setOpen(false)} className="min-h-9 rounded-full px-3 font-medium text-go-teal">
          Cancel
        </button>
      </div>
    </div>
  );
}
