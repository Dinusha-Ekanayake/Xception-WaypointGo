"use client";

import { useState } from "react";
import { ApiError } from "../api/problem.ts";
import { useThread, type Sender } from "../messaging/useThread.ts";
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
  draft,
}: {
  threadId: string;
  online: boolean;
  variant: "desk" | "phone";
  focusMessageId?: string | null;
  /** A role whose writes must survive no signal passes its queue. */
  sender?: Sender;
  draft?: Draft;
}): React.JSX.Element {
  const t = useThread(threadId);
  const [note, setNote] = useState<string | null>(null);
  const thread = t.thread.data;
  const members = t.members.data ?? [];

  if (t.thread.error instanceof ApiError && (t.thread.error.status === 403 || t.thread.error.status === 404)) {
    return <Notice tone="neutral" title="This thread is not yours to read">Only the people on this trip can open it.</Notice>;
  }

  return (
    <div className={cx("flex min-h-0 flex-1 flex-col gap-3", variant === "phone" && "px-1")}>
      {t.error !== null && (
        <Notice tone="warning" title="Messages could not be refreshed" live>
          {t.loadedAt ? `Showing what arrived at ${clock(t.loadedAt)}.` : "Check the connection and try again."}
        </Notice>
      )}
      <div className="min-h-0 flex-1 overflow-y-auto pr-1" aria-live="polite" aria-busy={t.thread.loading}>
        {t.loadedAt === null && t.error === null ? (
          <p className="py-8 text-center text-sm text-go-secondary">Loading messages…</p>
        ) : (
          <ThreadMessages
            messages={t.messages}
            focusMessageId={focusMessageId}
            hasOlder={t.hasOlder}
            onOlder={t.loadOlder}
            compact={variant === "phone"}
          />
        )}
      </div>
      {note && (
        <p role="status" className="text-xs text-go-secondary">
          {note}
        </p>
      )}
      {thread && !thread.open ? (
        <p className="rounded-go-card-s bg-go-surface px-3.5 py-2.5 text-[13px] text-go-secondary">
          This trip is over. Its messages can still be read, but nobody can write here now.
        </p>
      ) : thread ? (
        <ThreadComposer
          key={`${threadId}|${draft?.body ?? ""}|${draft?.address?.to ?? ""}|${draft?.address?.outletId ?? ""}|${draft?.report ? 1 : 0}`}
          threadId={threadId}
          role={thread.memberRole}
          members={members}
          online={online}
          {...(sender ? { sender } : {})}
          {...(draft ? { draft } : {})}
          onSent={(queued) => {
            setNote(queued ? "Saved on this device. It sends when the connection returns." : null);
            t.refresh();
          }}
        />
      ) : null}
    </div>
  );
}
