"use client";

import { useState } from "react";
import { ApiError } from "../api/problem.ts";
import type { MemberRole, MemberView, ReportType } from "../domain/messaging.ts";
import { defaultAddress, parseMention, plain, REPORT_LABEL, REPORTS_BY_ROLE, sameAddress, voiceLength, type Address, type Translate } from "../messaging/thread.ts";
import { useRecorder, type Recording } from "../messaging/recorder.ts";
import { newId, postCommand } from "../messaging/useThread.ts";
import { sendNow, type Sender } from "../messaging/senders.ts";
import { cx } from "./primitives.tsx";
import { useUndo } from "./useUndo.ts";

// Writing on a trip's thread (issue #136). Who it is for is a chip, or a
// leading mention such as "@driver" or "@OUT063" (R-MSG-02). Anyone but the
// dispatcher may make it a report, which only the dispatcher reads (R-MSG-03).
// A voice note goes before the message that carries it; a role whose sender
// keeps writes on the device sends both later when there is no signal. A
// removed voice note can be put back for a few seconds: it never left the device.

export type Draft = { address?: Address; body?: string; report?: boolean };

export function ThreadComposer({
  threadId,
  role,
  members,
  online,
  sender = sendNow,
  keepsOffline = false,
  draft,
  onSent,
  tr = plain,
}: {
  threadId: string;
  role: MemberRole;
  members: MemberView[];
  online: boolean;
  sender?: Sender;
  /** The sender keeps a message, voice included, on the device with no signal. */
  keepsOffline?: boolean;
  draft?: Draft;
  onSent: (queued: boolean) => void;
  tr?: Translate;
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
  const removed = useUndo<Recording>();
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
      const voiceNoteId = voice ? newId() : undefined;
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
        voice && voiceNoteId ? { threadId, voiceNoteId, blob: voice.recording.blob, durationMs: voice.recording.durationMs } : undefined,
      );
      setBody("");
      setReport(null);
      recorder.discard();
      removed.drop();
      onSent(sent.queued);
    } catch (failure) {
      setError(failure instanceof ApiError ? failure.problem.violations[0]?.message ?? failure.message : tr("Could not send. Try again."));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form
      aria-label={tr("Write a message")}
      className="flex flex-col gap-2.5 border-t border-go-divider pt-3"
      onSubmit={(event) => {
        event.preventDefault();
        void submit();
      }}
    >
      {members.length > 1 && !report && (
        <div role="radiogroup" aria-label={tr("Send to")} className="flex flex-wrap gap-1.5">
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
                {tr(m.label)}
              </button>
            );
          })}
        </div>
      )}
      {members.length <= 1 && !report && <span className="text-xs text-go-secondary">{tr("To {name}", { name: tr(members[0]?.label ?? "Dispatcher") })}</span>}

      {reports.length > 0 && (
        <label className="flex flex-wrap items-center gap-2 text-[13px] text-go-ink">
          <input type="checkbox" checked={report !== null} onChange={(e) => setReport(e.target.checked ? reports[0]! : null)} className="size-4 accent-go-danger" />
          {tr("Report a problem to the dispatcher")}
          {report && (
            <select
              aria-label={tr("Problem")}
              value={report}
              onChange={(e) => setReport(e.target.value as ReportType)}
              className="min-h-8 rounded-go-card-s border border-go-divider bg-go-card px-2 text-[13px]"
            >
              {reports.map((r) => (
                <option key={r} value={r}>
                  {tr(REPORT_LABEL[r])}
                </option>
              ))}
            </select>
          )}
        </label>
      )}

      {recorder.state.status === "recording" && (
        <p role="status" className="flex items-center gap-2 text-[13px] text-go-danger-strong">
          <span aria-hidden className="size-2 animate-pulse rounded-full bg-go-danger" />
          {tr("Recording {time} of 2:00", { time: voiceLength(recorder.state.elapsedMs) || "0:00" })}
        </p>
      )}
      {voice && (
        <span className="flex items-center gap-2">
          <audio controls src={voice.url} aria-label={tr("Your voice note")} className="h-9 max-w-[220px]" />
          <button
            type="button"
            onClick={() => {
              removed.hold(voice.recording);
              recorder.discard();
            }}
            className="min-h-8 rounded-full px-3 text-[13px] font-medium text-go-teal"
          >
            {tr("Remove")}
          </button>
        </span>
      )}
      {!voice && removed.held && (
        <span className="flex items-center gap-2">
          {/* Holds the player's place, so Undo sits where Remove was. */}
          <span aria-hidden className="h-9 w-[220px] max-w-full" />
          <button
            type="button"
            onClick={() => {
              const recording = removed.take();
              if (recording) recorder.restore(recording);
            }}
            className="min-h-8 rounded-full px-3 text-[13px] font-medium text-go-teal"
          >
            {tr("Undo")}
          </button>
        </span>
      )}
      {recorder.state.status === "unavailable" && <p className="text-[13px] text-go-warning-text">{tr(recorder.state.reason)}</p>}

      <div className="flex items-end gap-2">
        <textarea
          aria-label={tr("Message")}
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
          placeholder={tr(report ? "What happened?" : role === "dispatcher" ? "Write, or start with @driver, @all or a store code" : "Write a message")}
          className="min-h-11 flex-1 resize-none rounded-go-card-s border border-go-divider bg-go-card px-3 py-2 text-[15px] text-go-ink outline-none focus:border-go-teal"
        />
        {recorder.state.status === "recording" ? (
          <button type="button" onClick={recorder.stop} className="min-h-11 rounded-full bg-go-danger px-4 text-sm font-medium text-white">
            {tr("Stop")}
          </button>
        ) : (
          <button
            type="button"
            onClick={() => {
              removed.drop();
              void recorder.start();
            }}
            disabled={(!online && !keepsOffline) || voice !== null || busy}
            title={tr(online || keepsOffline ? "Record a voice note" : "Voice notes need a connection")}
            className="min-h-11 rounded-full bg-go-surface px-4 text-sm font-medium text-go-ink disabled:opacity-50"
          >
            {tr("Voice")}
          </button>
        )}
        <button
          type="submit"
          disabled={busy || recorder.state.status === "recording" || (!body.trim() && !voice)}
          className="min-h-11 rounded-full bg-go-ink px-5 text-sm font-medium text-go-card disabled:opacity-50"
        >
          {tr(busy ? "Sending" : report ? "Report" : "Send")}
        </button>
      </div>
      {!online && !keepsOffline && <p className="text-xs text-go-secondary">{tr("Offline. Voice notes need a connection.")}</p>}
      {error && (
        <p role="alert" className="text-[13px] text-go-danger-strong">
          {error}
        </p>
      )}
    </form>
  );
}
