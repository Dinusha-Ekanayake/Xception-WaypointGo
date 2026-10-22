"use client";

import { useEffect, useRef, useState } from "react";
import type { MessageView, TranscriptView } from "../domain/messaging.ts";
import { audienceLabel, byDay, plain, REPORT_LABEL, ROLE_LABEL, voiceLength, type Translate } from "../messaging/thread.ts";
import { voiceUrl } from "../messaging/useThread.ts";
import { clock, dayLabel } from "../wording/time.ts";
import { issueText } from "../wording/terms.ts";
import { ChannelRedirect } from "./ChannelRedirect.tsx";
import { ListenButton } from "./ListenButton.tsx";
import { cx } from "./primitives.tsx";
import { VoiceNote } from "./VoiceNote.tsx";
import { usePlayableUrl } from "../messaging/audioCache.ts";

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
  onRedirect,
  reportActions,
  accountId,
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
  /** The dispatcher's way to move a post off the trip's channel (R-MSG-08). */
  onRedirect?: (messageId: string, reason: string) => Promise<void>;
  /** The dispatcher's decisions beside a report, such as Skip store (R-EXE-31); absent for everyone else. */
  reportActions?: (message: MessageView) => React.ReactNode;
  /** Plays the audio kept on this phone first (R-MSG-11). */
  accountId?: string;
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
                {...(onRedirect ? { onRedirect } : {})}
                {...(reportActions ? { reportActions } : {})}
                {...(accountId ? { accountId } : {})}
              />
            ))}
          </ol>
        </section>
      ))}
      <div ref={end} />
    </div>
  );
}

const LANGUAGE_NAME = { en: "English", si: "Sinhala", ta: "Tamil" } as const;

/**
 * FF-05: what the engine heard, for every reader of the note. On a phone a note
 * with no transcript shows just its play button; the desk says so in words.
 */
function Transcript({ transcript, compact, tr }: { transcript: TranscriptView | null; compact: boolean; tr: Translate }): React.JSX.Element | null {
  if (transcript?.status === "done" && transcript.text) {
    return (
      <div data-testid="transcript" className="flex flex-col gap-0.5">
        <span className="text-[11px] font-medium text-go-secondary">
          {tr(transcript.audioKept === false ? "Machine transcript · audio no longer kept" : "Machine transcript, check audio")}
          {/* R-ML-37: said plainly to every reader when the machine was not sure of what it heard. */}
          {transcript.unclear ? ` · ${tr("heard unclearly, some words may be wrong")}` : ""}
          {transcript.language ? ` · ${tr(LANGUAGE_NAME[transcript.language])}` : ""}
        </span>
        <p lang={transcript.language ?? undefined} className="whitespace-pre-wrap break-words italic">{transcript.text}</p>
      </div>
    );
  }
  if (compact && transcript?.status !== "pending") return null;
  return (
    <span data-testid="transcript" className="text-[11px] text-go-secondary">
      {tr(transcript?.status === "pending" ? "Writing down the voice note…" : "No transcript, play audio")}
    </span>
  );
}

function Message({
  message: m,
  focused,
  compact,
  tr,
  onResolve,
  onRedirect,
  reportActions,
  accountId,
}: {
  message: MessageView;
  focused: boolean;
  compact: boolean;
  tr: Translate;
  onResolve?: (messageId: string, note: string) => Promise<void>;
  onRedirect?: (messageId: string, reason: string) => Promise<void>;
  reportActions?: (message: MessageView) => React.ReactNode;
  accountId?: string;
}): React.JSX.Element {
  const voiceSrc = usePlayableUrl(accountId, m.voiceNoteId ? voiceUrl(m.threadId, m.voiceNoteId) : "");
  const report = m.kind === "report";
  const resolved = report && m.resolvedAt !== null;
  const open = report && !resolved;
  // A report made from an issue carries its stored words; they read as on the Issues screen.
  const body = report ? issueText(m.body) : m.body;
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
        {m.seenByDriver === true && m.seenByDriverAt ? ` · ${tr("Seen {time}", { time: clock(m.seenByDriverAt) })}` : ""}
        {m.seenByDriver === false ? ` · ${tr("Not seen yet")}` : ""}
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
            src={voiceSrc}
            durationMs={m.voiceDurationMs}
            {...(m.voicePeaks?.length ? { peaks: m.voicePeaks.map((p) => p / 100) } : {})}
            seed={m.voiceNoteId}
            tone={open ? "report" : m.mine && !report ? "mine" : "theirs"}
            label={`${tr("Voice note")}${m.voiceDurationMs ? `, ${voiceLength(m.voiceDurationMs)}` : ""}`}
            tr={tr}
          />
        )}
        {/* FF-05: the dispatcher reads what was said without playing every note; the phone screens are unchanged. */}
        {m.voiceNoteId && <Transcript transcript={m.transcript ?? null} compact={compact} tr={tr} />}
        {body && <p className="whitespace-pre-wrap break-words">{body}</p>}
        {body && !m.voiceNoteId && !m.mine && <ListenButton text={body} threadId={m.threadId} messageId={m.messageId} {...(accountId ? { accountId } : {})} tr={tr} className="-ml-2 -mb-1" />}
        {resolved && (
          <p data-testid="resolution" className="flex flex-col gap-0.5 border-t border-go-divider pt-2 text-xs text-go-secondary">
            <span className="flex items-center gap-1.5 font-semibold text-go-teal">
              <span aria-hidden>✓</span>
              {m.resolvedByName === "Issue resolved" ? tr("Resolved with its issue") : `${tr("Resolved")} · ${m.resolvedByName ?? ""}`} · {clock(m.resolvedAt)}
            </span>
            {m.resolutionNote && <span className="text-go-ink">{m.resolutionNote}</span>}
          </p>
        )}
        {report && reportActions?.(m)}
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
      {m.audience === "channel" && (m.redirectedAt !== null || (onRedirect && !m.mine)) && (
        <ChannelRedirect message={m} tr={tr} {...(onRedirect && !m.mine ? { onRedirect } : {})} />
      )}
      <span className="px-1 text-[11px] text-go-secondary">{audienceLabel(m.audience, m.audienceOutlet, tr)}</span>
    </li>
  );
}
