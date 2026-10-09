"use client";

import { useState } from "react";
import { ApiError } from "../api/problem.ts";
import type { MemberRole, MemberView, ReportType } from "../domain/messaging.ts";
import type { Reply } from "../messaging/templates.ts";
import { channelLeaks, defaultAddress, parseMention, plain, REPORT_LABEL, REPORTS_BY_ROLE, sameAddress, type Address, type Translate } from "../messaging/thread.ts";
import { useRecorder, type Recording } from "../messaging/recorder.ts";
import { newId, postCommand } from "../messaging/useThread.ts";
import { sendNow, type Sender } from "../messaging/senders.ts";
import { cx } from "./primitives.tsx";
import { SendIcon } from "./thread-icons.tsx";
import { VoiceCapture } from "./VoiceCapture.tsx";

// Writing on a trip's thread (issue #136). Who it is for is a chip, or a
// leading mention such as "@driver" or "@OUT063" (R-MSG-02). Anyone but the
// dispatcher may make it a report, which only the dispatcher reads (R-MSG-03).
// A voice note goes before the message that carries it; a role whose sender
// keeps writes on the device sends both later when there is no signal.

export type Draft = { address?: Address; body?: string; report?: boolean };

export function ThreadComposer({
  threadId,
  role,
  members,
  myOutlets = [],
  replies = [],
  repliesNote,
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
  /** The writer's own outlets on the trip: a store may name them on the channel. */
  myOutlets?: string[];
  /** FF-08: ready replies for the trip's state, every value filled by code; a tap fills the draft. */
  replies?: Reply[];
  /** AI-J5: how the replies are ordered, said under them ("Suggestions: rules only"). */
  repliesNote?: string;
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
  // FF-08: the ready reply tapped, so the post says which one and whether its words were changed.
  const [picked, setPicked] = useState<{ reply: Reply; rank: number } | null>(null);
  const offered = replies.filter((r) => members.some((m) => sameAddress({ to: m.to, outletId: m.outletId }, { to: r.to, outletId: r.outletId })));
  // Emptied and written afresh: no longer that reply.
  if (picked && body.trim() === "") setPicked(null);
  const [report, setReport] = useState<ReportType | null>(draft?.report && reports.length > 0 ? reports[0]! : null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // The body a writer was asked about before it went to the channel (R-MSG-08).
  const [asked, setAsked] = useState<string | null>(null);
  const recorder = useRecorder();
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

  const leaks = to.to === "channel" ? channelLeaks(body, role === "dispatcher" ? [] : myOutlets) : null;
  const leaking = leaks !== null && (leaks.outlets.length > 0 || leaks.order);

  const submit = async (voice?: Recording, address: Address = to) => {
    const text = voice ? "" : body.trim();
    if (busy || (!text && !voice)) return;
    // Every store still waiting reads the channel: ask once before a post that names another.
    if (!voice && address.to === "channel" && leaking && asked !== text) {
      setAsked(text);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const clientMessageId = newId();
      const voiceNoteId = voice ? newId() : undefined;
      const sent = await sender(
        postCommand({
          threadId,
          body: text,
          to: address.to,
          ...(address.to === "outlet" && address.outletId ? { outletId: address.outletId } : {}),
          ...(report ? { report } : {}),
          ...(voiceNoteId ? { voiceNoteId } : {}),
          ...(picked && !report && !voice
            ? {
                templateId: picked.reply.id,
                templateVersion: picked.reply.version,
                templateRank: picked.rank,
                templateEdited: text.trim() !== picked.reply.body.trim(),
                templateShown: offered.map((r) => r.id).join(","),
                templateExplored: offered.some((r) => r.explored === true),
              }
            : {}),
          clientMessageId,
        }),
        voice && voiceNoteId ? { threadId, voiceNoteId, blob: voice.blob, durationMs: voice.durationMs, peaks: voice.peaks } : undefined,
      );
      if (!voice) setBody("");
      setPicked(null);
      setAsked(null);
      setReport(null);
      recorder.discard();
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
      {replies.length > 0 && !report && body.trim() === "" && (
        <div role="group" aria-label={tr("Suggested replies")} className="flex flex-wrap gap-1.5">
          {offered.map((r, i) => (
              <button
                key={r.id}
                type="button"
                data-template={`${r.id}@${r.version}`}
                onClick={() => {
                  setAddress({ to: r.to, outletId: r.outletId });
                  setBody(r.body);
                  setPicked({ reply: r, rank: i + 1 });
                }}
                className="min-h-8 rounded-full border border-go-divider bg-go-card px-3 text-[13px] font-medium text-go-teal"
              >
                {tr(r.label)}
              </button>
            ))}
          {repliesNote && <span className="self-center text-[11px] text-go-muted">{repliesNote}</span>}
        </div>
      )}
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

      {recorder.state.status === "unavailable" && <p className="text-[13px] text-go-warning-text">{tr(recorder.state.reason)}</p>}

      <VoiceCapture
        recorder={recorder}
        enabled={(online || keepsOffline) && !busy}
        disabledReason={tr("Voice notes need a connection")}
        tr={tr}
        onSend={(recording) => submit(recording)}
        field={
          <textarea
            aria-label={tr("Message")}
            value={body}
            rows={1}
            maxLength={1000}
            onChange={(e) => type(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) {
                e.preventDefault();
                void submit();
              }
            }}
            placeholder={tr(report ? "What happened?" : role === "dispatcher" ? "Write, or start with @driver, @all or a store code" : "Write a message")}
            className="max-h-32 min-h-11 flex-1 resize-none rounded-[22px] border border-go-divider bg-go-card px-4 py-2.5 text-[15px] text-go-ink outline-none [field-sizing:content] focus:border-go-teal"
          />
        }
        trailing={
          body.trim() ? (
            <button
              type="submit"
              disabled={busy}
              aria-label={tr(busy ? "Sending" : report ? "Report" : "Send")}
              className={cx(
                "flex size-11 shrink-0 items-center justify-center rounded-full text-white shadow-sm transition-transform active:scale-95 disabled:opacity-50",
                report ? "bg-go-danger" : "bg-go-teal",
              )}
            >
              <SendIcon />
            </button>
          ) : null
        }
      />
      {leaking && asked === body.trim() && (
        <div role="alert" data-testid="channel-nudge" className="flex flex-col gap-2 rounded-go-card-s bg-go-warning-tint px-3.5 py-2.5 text-[13px] text-go-ink">
          <p>
            {leaks.outlets.length > 0
              ? tr("Every store still waiting reads this, and it names {outlets}.", { outlets: leaks.outlets.join(", ") })
              : tr("Every store still waiting reads this, and it names an order.")}
          </p>
          <span className="flex flex-wrap gap-2">
            {role !== "dispatcher" && (
              <button
                type="button"
                disabled={busy}
                onClick={() => {
                  setAddress({ to: "dispatch", outletId: null });
                  void submit(undefined, { to: "dispatch", outletId: null });
                }}
                className="min-h-8 rounded-full bg-go-teal px-3 text-[13px] font-medium text-white disabled:opacity-50"
              >
                {tr("Send to the dispatcher only")}
              </button>
            )}
            <button
              type="button"
              disabled={busy}
              onClick={() => void submit()}
              className="min-h-8 rounded-full bg-go-card px-3 text-[13px] font-medium text-go-ink shadow-[inset_0_0_0_1px_rgb(0_0_0/0.08)] disabled:opacity-50"
            >
              {tr("Send to every store")}
            </button>
          </span>
        </div>
      )}
      {!online && !keepsOffline && <p className="text-xs text-go-secondary">{tr("Offline. Voice notes need a connection.")}</p>}
      {error && (
        <p role="alert" className="text-[13px] text-go-danger-strong">
          {error}
        </p>
      )}
    </form>
  );
}
