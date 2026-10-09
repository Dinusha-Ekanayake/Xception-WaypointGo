"use client";

import { useState } from "react";
import type { MessageView } from "../domain/messaging.ts";
import type { Translate } from "../messaging/thread.ts";
import { clock } from "../wording/time.ts";

// A post on the trip's channel that the dispatcher moved off it (R-MSG-08), or
// the dispatcher's way to move one. The message never changes: the other stores
// stop seeing it, and who moved it, when and why are kept beside it.

export function ChannelRedirect({
  message: m,
  tr,
  onRedirect,
}: {
  message: MessageView;
  tr: Translate;
  onRedirect?: (messageId: string, reason: string) => Promise<void>;
}): React.JSX.Element | null {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (m.redirectedAt !== null) {
    return (
      <p data-testid="redirected" className="max-w-full px-1 text-xs text-go-secondary">
        {m.mine
          ? tr("The dispatcher took this off the stores' channel at {time}.", { time: clock(m.redirectedAt) })
          : tr("Taken off the stores' channel at {time}", { time: clock(m.redirectedAt) })}
        {!m.mine && m.redirectReason ? ` · ${m.redirectReason}` : ""}
      </p>
    );
  }
  if (!onRedirect) return null;

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      await onRedirect(m.messageId, reason.trim());
      setOpen(false);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : tr("Could not take it off. Try again."));
    } finally {
      setBusy(false);
    }
  };

  if (!open) {
    return (
      <button type="button" onClick={() => setOpen(true)} className="min-h-8 px-1 text-xs font-medium text-go-teal">
        {tr("Take off the stores' channel")}
      </button>
    );
  }
  return (
    <form
      aria-label={tr("Take this off the stores' channel")}
      className="flex w-full flex-col gap-2"
      onSubmit={(e) => {
        e.preventDefault();
        void submit();
      }}
    >
      <input
        aria-label={tr("Why")}
        value={reason}
        minLength={3}
        maxLength={500}
        required
        onChange={(e) => setReason(e.target.value)}
        placeholder={tr("Why, for example: names another store's order")}
        className="min-h-9 rounded-full border border-go-divider bg-go-card px-3 text-[13px] text-go-ink outline-none focus:border-go-teal"
      />
      <span className="flex gap-2">
        <button type="submit" disabled={busy || reason.trim().length < 3} className="min-h-8 rounded-full bg-go-teal px-3 text-[13px] font-medium text-white disabled:opacity-50">
          {tr(busy ? "Taking off" : "Take off")}
        </button>
        <button type="button" onClick={() => setOpen(false)} className="min-h-8 rounded-full px-3 text-[13px] font-medium text-go-secondary">
          {tr("Cancel")}
        </button>
      </span>
      {error && <span role="alert" className="text-xs text-go-danger-strong">{error}</span>}
    </form>
  );
}
