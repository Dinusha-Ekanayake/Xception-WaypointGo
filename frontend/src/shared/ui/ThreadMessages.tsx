"use client";

import { useEffect, useRef, useState } from "react";
import type { MessageView } from "../domain/messaging.ts";
import { audienceLabel, byDay, plain, REPORT_LABEL, ROLE_LABEL, voiceLength, type Translate } from "../messaging/thread.ts";
import { voiceUrl } from "../messaging/useThread.ts";
import { clock, dayLabel } from "../wording/time.ts";
import { cx } from "./primitives.tsx";
import { VoiceNote } from "./VoiceNote.tsx";

// The messages of a trip's thread (issue #136), oldest first under a line for
// each day. A report reads as a warning, red like the timeline sign that opens
// it, until the dispatcher resolves it; a voice note plays in place. The
// reader's own messages sit on the right.

export function ThreadMessages({
  messages,
  focusMessageId,
  hasOlder,
  onOlder,
  compact,
  tr = plain,
  onResolve,
}: {
  messages: MessageView[];
  /** Scrolled to and marked, when a warning sign or a notification opened the thread. */
  focusMessageId: string | null;
  hasOlder: boolean;
  onOlder: () => void;
  compact: boolean;
  tr?: Translate;
  /** The dispatcher's way to resolve a report (R-MSG-07); absent for everyone else. */
  onResolve?: (messageId: string, note: string) => Promise<void>;
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
              <Message
                key={m.messageId}
                message={m}
                focused={m.messageId === focusMessageId}
                compact={compact}
                tr={tr}
                {...(onResolve ? { onResolve } : {})}
              />
            ))}
          </ol>
        </section>
      ))}
      <div ref={end} />
    </div>
  );
}

function Message({
  message: m,
  focused,
  compact,
  tr,
  onResolve,
}: {
  message: MessageView;
  focused: boolean;
  compact: boolean;
  tr: Translate;
  onResolve?: (messageId: string, note: string) => Promise<void>;
}): React.JSX.Element {
  const report = m.kind === "report";
  const resolved = report && m.resolvedAt !== null;
  const open = report && !resolved;
  const [resolving, setResolving] = useState(false);
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const resolve = async () => {
    if (!onResolve) return;
    setBusy(true);
    setError(null);
    try {
      await onResolve(m.messageId, note.trim());
      setResolving(false);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : tr("Could not resolve. Try again."));
    } finally {
      setBusy(false);
    }
  };

  return (
    <li
      id={`message-${m.messageId}`}
      data-testid="thread-message"
      data-kind={m.kind}
      data-resolved={resolved ? "true" : undefined}
      className={cx("flex max-w-[88%] flex-col gap-1", m.mine ? "self-end items-end" : "self-start items-start")}
    >
      <span className="px-1 text-xs text-go-secondary">
        {m.mine ? tr("You") : m.authorName === ROLE_LABEL[m.authorRole] ? tr(m.authorName) : m.authorName}
        {!m.mine && m.authorName !== ROLE_LABEL[m.authorRole] ? ` · ${tr(ROLE_LABEL[m.authorRole])}` : ""} · {clock(m.createdAt)}
      </span>
      <div
        className={cx(
          "flex min-w-0 flex-col gap-2 overflow-hidden rounded-[18px] px-3.5 py-2.5",
          compact ? "text-[14px]" : "text-[15px]",
          open
            ? "bg-go-danger-tint text-go-ink shadow-[inset_0_0_0_1px_rgb(220_38_38/0.18)]"
            : resolved
              ? "bg-go-surface text-go-ink shadow-[inset_0_0_0_1px_rgb(0_0_0/0.06)]"
              : m.mine
                ? "rounded-br-[6px] bg-go-soft text-go-on-soft"
                : "rounded-bl-[6px] bg-go-surface text-go-ink",
          focused && cx("outline outline-2 outline-offset-2", open ? "outline-go-danger" : "outline-go-teal"),
        )}
      >
        {report && (
          <span className={cx("flex items-center gap-1.5 text-xs font-semibold", open ? "text-go-danger-strong" : "text-go-secondary")}>
            <span aria-hidden className={cx("flex size-4 items-center justify-center rounded-full text-[10px] font-bold leading-none text-white", open ? "bg-go-danger" : "bg-go-secondary/60")}>
              !
            </span>
            {tr("Report")} · {tr(m.reportType ? REPORT_LABEL[m.reportType] : "Problem")}
          </span>
        )}
        {m.voiceNoteId && (
          <VoiceNote
            src={voiceUrl(m.threadId, m.voiceNoteId)}
            durationMs={m.voiceDurationMs}
            {...(m.voicePeaks?.length ? { peaks: m.voicePeaks.map((p) => p / 100) } : {})}
            seed={m.voiceNoteId}
            tone={open ? "report" : m.mine && !report ? "mine" : "theirs"}
            label={`${tr("Voice note")}${m.voiceDurationMs ? `, ${voiceLength(m.voiceDurationMs)}` : ""}`}
            tr={tr}
          />
        )}
        {m.body && <p className="whitespace-pre-wrap break-words">{m.body}</p>}
        {resolved && (
          <p data-testid="resolution" className="flex flex-col gap-0.5 border-t border-go-divider pt-2 text-xs text-go-secondary">
            <span className="flex items-center gap-1.5 font-semibold text-go-teal">
              <span aria-hidden>✓</span>
              {m.resolvedByName === "Issue resolved" ? tr("Resolved with its issue") : `${tr("Resolved")} · ${m.resolvedByName ?? ""}`} · {clock(m.resolvedAt)}
            </span>
            {m.resolutionNote && <span className="text-go-ink">{m.resolutionNote}</span>}
          </p>
        )}
        {open && onResolve && !resolving && (
          <button
            type="button"
            onClick={() => setResolving(true)}
            className="min-h-8 self-start rounded-full bg-go-card px-3 text-[13px] font-medium text-go-teal shadow-[inset_0_0_0_1px_rgb(0_0_0/0.08)]"
          >
            {tr("Mark resolved")}
          </button>
        )}
        {open && onResolve && resolving && (
          <form
            aria-label={tr("Resolve this report")}
            className="flex flex-col gap-2"
            onSubmit={(e) => {
              e.preventDefault();
              void resolve();
            }}
          >
            <input
              aria-label={tr("What was done")}
              value={note}
              maxLength={500}
              onChange={(e) => setNote(e.target.value)}
              placeholder={tr("What was done (optional)")}
              className="min-h-9 rounded-full border border-go-divider bg-go-card px-3 text-[13px] text-go-ink outline-none focus:border-go-teal"
            />
            <span className="flex gap-2">
              <button type="submit" disabled={busy} className="min-h-8 rounded-full bg-go-teal px-3 text-[13px] font-medium text-white disabled:opacity-50">
                {tr(busy ? "Resolving" : "Resolve")}
              </button>
              <button type="button" onClick={() => setResolving(false)} className="min-h-8 rounded-full px-3 text-[13px] font-medium text-go-secondary">
                {tr("Cancel")}
              </button>
            </span>
            {error && <span role="alert" className="text-xs text-go-danger-strong">{error}</span>}
          </form>
        )}
      </div>
      <span className="px-1 text-[11px] text-go-secondary">{audienceLabel(m.audience, m.audienceOutlet, tr)}</span>
    </li>
  );
}
