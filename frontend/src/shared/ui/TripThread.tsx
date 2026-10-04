"use client";

import { useState } from "react";
import { ApiError } from "../api/problem.ts";
import { resolveReport, useThread, useWaiting } from "../messaging/useThread.ts";
import { audienceLabel, plain, type Translate } from "../messaging/thread.ts";
import type { Sender } from "../messaging/senders.ts";
import { clock } from "../wording/time.ts";
import { Notice } from "./Notice.tsx";
import { cx } from "./primitives.tsx";
import { ThreadComposer, type Draft } from "./ThreadComposer.tsx";
import { ThreadMessages } from "./ThreadMessages.tsx";

// One trip's thread (issue #136, ADR-004): its messages and a place to write.
// Every role draws the same thread; what each reader sees and may write is the
// server's decision (R-MSG-01, R-MSG-02). "desk" sits in the dispatcher's side
// sheet, "phone" fills a phone's screen. A conversation, not an inbox, so the
// #14 decision of no shared notification component still holds.

export function TripThread({
  threadId,
  online,
  variant,
  focusMessageId = null,
  sender,
  keepsOffline = false,
  accountId,
  draft,
  tr = plain,
}: {
  threadId: string;
  online: boolean;
  variant: "desk" | "phone";
  focusMessageId?: string | null;
  /** A role whose writes must survive no signal passes a queuedSender. */
  sender?: Sender;
  keepsOffline?: boolean;
  /** Keeps the thread on this device for a role that reads it with no signal. */
  accountId?: string;
  draft?: Draft;
  /** The reader's language; English when absent. */
  tr?: Translate;
}): React.JSX.Element {
  const t = useThread(threadId, accountId);
  const [note, setNote] = useState<string | null>(null);
  const [sentCount, setSentCount] = useState(0);
  const waiting = useWaiting(threadId, accountId, `${sentCount}|${t.loadedAt?.getTime() ?? 0}`);
  const thread = t.thread.data;
  const members = t.members.data ?? [];

  if (t.thread.error instanceof ApiError && (t.thread.error.status === 403 || t.thread.error.status === 404)) {
    return <Notice tone="neutral" title={tr("This thread is not yours to read")}>{tr("Only the people on this trip can open it.")}</Notice>;
  }

  return (
    <div className={cx("flex min-h-0 flex-1 flex-col gap-3", variant === "phone" && "px-1")}>
      {t.error !== null && (
        <Notice tone="warning" title={tr("Messages could not be refreshed")} live>
          {t.loadedAt ? tr("Showing what arrived at {time}.", { time: clock(t.loadedAt) }) : tr("Check the connection and try again.")}
        </Notice>
      )}
      <div className="min-h-0 flex-1 overflow-y-auto pr-1" aria-live="polite" aria-busy={t.thread.loading}>
        {t.loadedAt === null && t.error === null ? (
          <p className="py-8 text-center text-sm text-go-secondary">{tr("Loading messages…")}</p>
        ) : (
          <ThreadMessages
            messages={t.messages}
            focusMessageId={focusMessageId}
            hasOlder={t.hasOlder}
            onOlder={t.loadOlder}
            compact={variant === "phone"}
            tr={tr}
            {...(thread?.memberRole === "dispatcher"
              ? {
                  onResolve: async (messageId: string, note: string) => {
                    await resolveReport(messageId, note);
                    t.refresh();
                  },
                }
              : {})}
          />
        )}
        {waiting.length > 0 && (
          <ol aria-label={tr("Waiting to send")} className="mt-3 flex flex-col gap-2">
            {waiting.map((w) => (
              <li key={w.commandId} data-testid="waiting-message" className="flex max-w-[88%] flex-col items-end gap-1 self-end">
                <span className="text-xs text-go-secondary">{tr("You · waiting to send")}</span>
                <div className="flex flex-col gap-1 rounded-go-card-s border border-dashed border-go-divider bg-go-surface px-3.5 py-2.5 text-[14px] text-go-ink">
                  {w.report && <span className="text-xs font-semibold text-go-danger-strong">{tr("Report")}</span>}
                  {w.voice && <span className="text-xs text-go-secondary">{tr("Voice note")}</span>}
                  {w.body && <p className="whitespace-pre-wrap break-words">{w.body}</p>}
                </div>
                <span className="text-[11px] text-go-secondary">{audienceLabel(w.to, w.outletId, tr)}</span>
              </li>
            ))}
          </ol>
        )}
      </div>
      {note && (
        <p role="status" className="text-xs text-go-secondary">
          {note}
        </p>
      )}
      {thread && !thread.open ? (
        <p className="rounded-go-card-s bg-go-surface px-3.5 py-2.5 text-[13px] text-go-secondary">
          {tr("This trip is over. Its messages can still be read, but nobody can write here now.")}
        </p>
      ) : thread ? (
        <ThreadComposer
          key={`${threadId}|${draft?.body ?? ""}|${draft?.address?.to ?? ""}|${draft?.address?.outletId ?? ""}|${draft?.report ? 1 : 0}`}
          threadId={threadId}
          role={thread.memberRole}
          members={members}
          online={online}
          keepsOffline={keepsOffline}
          tr={tr}
          {...(sender ? { sender } : {})}
          {...(draft ? { draft } : {})}
          onSent={(queued) => {
            setSentCount((n) => n + 1);
            setNote(queued ? tr("Saved on this device. It sends when the connection returns.") : null);
            t.refresh();
          }}
        />
      ) : null}
    </div>
  );
}
