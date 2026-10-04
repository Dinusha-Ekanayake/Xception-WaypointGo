"use client";

import { useState } from "react";
import { ApiError } from "../api/problem.ts";
import type { MemberRole, MemberView, ReportType } from "../domain/messaging.ts";
import { defaultAddress, parseMention, REPORT_LABEL, REPORTS_BY_ROLE, sameAddress, voiceLength, type Address } from "../messaging/thread.ts";
import { useRecorder } from "../messaging/recorder.ts";
import { newId, postCommand, sendNow, uploadVoice, type Sender } from "../messaging/useThread.ts";
import { cx } from "./primitives.tsx";

// Writing on a trip's thread (issue #136). Who it is for is a chip, or a
// leading mention such as "@driver" or "@OUT063" (R-MSG-02). Anyone but the
// dispatcher may make it a report, which only the dispatcher reads (R-MSG-03).
// A voice note is uploaded before the message that carries it, so it needs a
// connection; typed messages go through the role's sender, which may queue.

export type Draft = { address?: Address; body?: string; report?: boolean };

export function ThreadComposer({
  threadId,
  role,
  members,
  online,
  sender = sendNow,
  draft,
  onSent,
}: {
  threadId: string;
  role: MemberRole;
  members: MemberView[];
  online: boolean;
  sender?: Sender;
  draft?: Draft;
  onSent: (queued: boolean) => void;
}): React.JSX.Element {
  const reports = REPORTS_BY_ROLE[role];
  // Until the writer picks someone, the default follows the members as they load.
  const [chosen, setAddress] = useState<Address | null>(draft?.address ?? null);
  const address = chosen ?? defaultAddress(role, members);
  const [body, setBody] = useState(draft?.body ?? "");
  const [report, setReport] = useState<ReportType | null>(draft?.report && reports.length > 0 ? reports[0]! : null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const recorder = useRecorder();
  const voice = recorder.state.status === "ready" ? recorder.state : null;
  const to: Address = report ? { to: "dispatch", outletId: null } : address;

  const type = (text: string) => {
    // A mention followed by a space picks the recipient and leaves the text.
    if (/^\s*@\S+[\s,:]/.test(text)) {
      const parsed = parseMention(text, members);
      if (parsed.address) {
        setAddress(parsed.address);
        setReport(null);
        setBody(parsed.body);
        return;
      }
    }
    setBody(text);
  };

  const submit = async () => {
    const text = body.trim();
    if (busy || (!text && !voice)) return;
    setBusy(true);
    setError(null);
    try {
      const clientMessageId = newId();
      let voiceNoteId: string | undefined;
      if (voice) {
        voiceNoteId = newId();
        await uploadVoice(threadId, voiceNoteId, voice.recording.blob, voice.recording.durationMs);
      }
      const sent = await sender(
        postCommand({
          threadId,
          body: text,
          to: to.to,
          ...(to.to === "outlet" && to.outletId ? { outletId: to.outletId } : {}),
          ...(report ? { report } : {}),
          ...(voiceNoteId ? { voiceNoteId } : {}),
          clientMessageId,
        }),
      );
      setBody("");
      setReport(null);
      recorder.discard();
      onSent(sent ? sent.queued : false);
    } catch (failure) {
      setError(failure instanceof ApiError ? failure.problem.violations[0]?.message ?? failure.message : "Could not send. Try again.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <form
      aria-label="Write a message"
      className="flex flex-col gap-2.5 border-t border-go-divider pt-3"
      onSubmit={(event) => {
        event.preventDefault();
        void submit();
      }}
    >
      {members.length > 1 && !report && (
        <div role="radiogroup" aria-label="Send to" className="flex flex-wrap gap-1.5">
          {members.map((m) => {
            const value = { to: m.to, outletId: m.outletId };
            const on = sameAddress(value, address);
            return (
              <button
                key={`${m.to}|${m.outletId ?? ""}`}
                type="button"
                role="radio"
                aria-checked={on}
                onClick={() => setAddress(value)}
                className={cx("min-h-8 rounded-full px-3 text-[13px] font-medium", on ? "bg-go-ink text-go-card" : "bg-go-surface text-go-ink")}
              >
                {m.label}
              </button>
            );
          })}
        </div>
      )}
      {members.length <= 1 && !report && <span className="text-xs text-go-secondary">To {members[0]?.label ?? "the dispatcher"}</span>}

      {reports.length > 0 && (
        <label className="flex flex-wrap items-center gap-2 text-[13px] text-go-ink">
          <input type="checkbox" checked={report !== null} onChange={(e) => setReport(e.target.checked ? reports[0]! : null)} className="size-4 accent-go-danger" />
          Report a problem to the dispatcher
          {report && (
            <select
              aria-label="Problem"
              value={report}
              onChange={(e) => setReport(e.target.value as ReportType)}
              className="min-h-8 rounded-go-card-s border border-go-divider bg-go-card px-2 text-[13px]"
            >
              {reports.map((r) => (
                <option key={r} value={r}>
                  {REPORT_LABEL[r]}
                </option>
              ))}
            </select>
          )}
        </label>
      )}

      {recorder.state.status === "recording" && (
        <p role="status" className="flex items-center gap-2 text-[13px] text-go-danger-strong">
          <span aria-hidden className="size-2 animate-pulse rounded-full bg-go-danger" />
          Recording {voiceLength(recorder.state.elapsedMs) || "0:00"} of 2:00
        </p>
      )}
      {voice && (
        <span className="flex items-center gap-2">
          <audio controls src={voice.url} aria-label="Your voice note" className="h-9 max-w-[220px]" />
          <button type="button" onClick={recorder.discard} className="min-h-8 rounded-full px-3 text-[13px] font-medium text-go-teal">
            Remove
          </button>
        </span>
      )}
      {recorder.state.status === "unavailable" && <p className="text-[13px] text-go-warning-text">{recorder.state.reason}</p>}

      <div className="flex items-end gap-2">
        <textarea
          aria-label="Message"
          value={body}
          rows={2}
          maxLength={1000}
          onChange={(e) => type(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) {
              e.preventDefault();
              void submit();
            }
          }}
          placeholder={report ? "What happened?" : role === "dispatcher" ? "Write, or start with @driver, @all or a store code" : "Write a message"}
          className="min-h-11 flex-1 resize-none rounded-go-card-s border border-go-divider bg-go-card px-3 py-2 text-[15px] text-go-ink outline-none focus:border-go-teal"
        />
        {recorder.state.status === "recording" ? (
          <button type="button" onClick={recorder.stop} className="min-h-11 rounded-full bg-go-danger px-4 text-sm font-medium text-white">
            Stop
          </button>
        ) : (
          <button
            type="button"
            onClick={() => void recorder.start()}
            disabled={!online || voice !== null || busy}
            title={online ? "Record a voice note" : "Voice notes need a connection"}
            className="min-h-11 rounded-full bg-go-surface px-4 text-sm font-medium text-go-ink disabled:opacity-50"
          >
            Voice
          </button>
        )}
        <button
          type="submit"
          disabled={busy || recorder.state.status === "recording" || (!body.trim() && !voice)}
          className="min-h-11 rounded-full bg-go-ink px-5 text-sm font-medium text-go-card disabled:opacity-50"
        >
          {busy ? "Sending" : report ? "Report" : "Send"}
        </button>
      </div>
      {!online && <p className="text-xs text-go-secondary">Offline. Voice notes need a connection.</p>}
      {error && (
        <p role="alert" className="text-[13px] text-go-danger-strong">
          {error}
        </p>
      )}
    </form>
  );
}
