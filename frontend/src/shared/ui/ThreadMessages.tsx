"use client";

import { useEffect, useRef } from "react";
import type { MessageView } from "../domain/messaging.ts";
import { audienceLabel, byDay, plain, REPORT_LABEL, ROLE_LABEL, voiceLength, type Translate } from "../messaging/thread.ts";
import { voiceUrl } from "../messaging/useThread.ts";
import { clock, dayLabel } from "../wording/time.ts";
import { cx } from "./primitives.tsx";

// The messages of a trip's thread (issue #136), oldest first under a line for
// each day. A report reads as a warning, red like the timeline sign that opens
// it; a voice note plays in place. The reader's own messages sit on the right.

export function ThreadMessages({
  messages,
  focusMessageId,
  hasOlder,
  onOlder,
  compact,
  tr = plain,
}: {
  messages: MessageView[];
  /** Scrolled to and marked, when a warning sign or a notification opened the thread. */
  focusMessageId: string | null;
  hasOlder: boolean;
  onOlder: () => void;
  compact: boolean;
  tr?: Translate;
}): React.JSX.Element {
  const end = useRef<HTMLDivElement>(null);
  const focused = useRef<string | null>(null);
  const newest = messages[messages.length - 1]?.messageId ?? null;

  // Open at the message asked for once it has arrived, else at the newest.
  useEffect(() => {
    if (focusMessageId && focused.current !== focusMessageId) {
      const el = document.getElementById(`message-${focusMessageId}`);
      if (el) {
        focused.current = focusMessageId;
        el.scrollIntoView({ block: "center" });
        return;
      }
    }
    if (!focusMessageId || focused.current === focusMessageId) end.current?.scrollIntoView({ block: "end" });
  }, [focusMessageId, newest]);

  if (messages.length === 0) {
    return <p className="py-8 text-center text-sm text-go-secondary">{tr("No messages yet. Anything written here reaches the people it is for.")}</p>;
  }

  return (
    <div className="flex flex-col gap-3">
      {hasOlder && (
        <button type="button" onClick={onOlder} className="min-h-9 self-center rounded-full bg-go-surface px-4 text-[13px] font-medium text-go-teal">
          {tr("Show earlier messages")}
        </button>
      )}
      {byDay(messages).map((group) => (
        <section key={group.day} aria-label={dayLabel(group.day)} className="flex flex-col gap-3">
          <h3 className="flex items-center gap-3 text-xs font-medium text-go-secondary">
            <span aria-hidden className="h-px flex-1 bg-go-divider" />
            {dayLabel(group.day)}
            <span aria-hidden className="h-px flex-1 bg-go-divider" />
          </h3>
          <ol className="flex flex-col gap-3">
            {group.messages.map((m) => (
              <Message key={m.messageId} message={m} focused={m.messageId === focusMessageId} compact={compact} tr={tr} />
            ))}
          </ol>
        </section>
      ))}
      <div ref={end} />
    </div>
  );
}

function Message({ message: m, focused, compact, tr }: { message: MessageView; focused: boolean; compact: boolean; tr: Translate }): React.JSX.Element {
  const report = m.kind === "report";
  return (
    <li
      id={`message-${m.messageId}`}
      data-testid="thread-message"
      data-kind={m.kind}
      className={cx("flex max-w-[88%] flex-col gap-1", m.mine ? "self-end items-end" : "self-start items-start")}
    >
      <span className="text-xs text-go-secondary">
        {m.mine ? tr("You") : m.authorName === ROLE_LABEL[m.authorRole] ? tr(m.authorName) : m.authorName}
        {!m.mine && m.authorName !== ROLE_LABEL[m.authorRole] ? ` · ${tr(ROLE_LABEL[m.authorRole])}` : ""} · {clock(m.createdAt)}
      </span>
      <div
        className={cx(
          "flex flex-col gap-1.5 rounded-go-card-s px-3.5 py-2.5",
          compact ? "text-[14px]" : "text-[15px]",
          report ? "border-l-4 border-l-go-danger bg-go-danger-tint text-go-ink" : m.mine ? "bg-go-soft text-go-on-soft" : "bg-go-surface text-go-ink",
          focused && "ring-2 ring-go-danger ring-offset-2 ring-offset-go-card",
        )}
      >
        {report && (
          <span className="flex items-center gap-1.5 text-xs font-semibold text-go-danger-strong">
            <span aria-hidden className="size-2 rounded-full bg-go-danger" />
            {tr("Report")} · {tr(m.reportType ? REPORT_LABEL[m.reportType] : "Problem")}
          </span>
        )}
        {m.voiceNoteId && (
          <span className="flex items-center gap-2">
            <audio controls preload="none" src={voiceUrl(m.threadId, m.voiceNoteId)} aria-label={`${tr("Voice note")}${m.voiceDurationMs ? `, ${voiceLength(m.voiceDurationMs)}` : ""}`} className="h-9 max-w-[240px]" />
            {m.voiceDurationMs ? <span className="text-xs text-go-secondary">{voiceLength(m.voiceDurationMs)}</span> : null}
          </span>
        )}
        {m.body && <p className="whitespace-pre-wrap break-words">{m.body}</p>}
      </div>
      <span className="text-[11px] text-go-secondary">{audienceLabel(m.audience, m.audienceOutlet, tr)}</span>
    </li>
  );
}
