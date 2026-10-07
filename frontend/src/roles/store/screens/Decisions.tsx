"use client";

import { useState } from "react";
import { question, toAnswer } from "@shared/decisions/decisions";
import type { DecisionView } from "@shared/domain/types";
import { useRecorder } from "@shared/messaging/recorder";
import type { Sender } from "@shared/messaging/senders";
import { newId, postCommand, useTripThread } from "@shared/messaging/useThread";
import { clock } from "../data/format.ts";
import { useT } from "../i18n.tsx";
import { MicIcon, StopIcon, TrashIcon, cx } from "@shared/ui";
import { Button, Card, Chip } from "../ui.tsx";

// Live decisions on the store's home (roadmap FF-09, R-ISS-14): the dispatcher
// asks one question and the store answers with a tap, so nobody phones. Every
// name and number is filled by the system; the reason is optional, typed or
// spoken. A spoken reason goes to the dispatcher on the trip's thread, where it
// plays; the answer names it. An answer after the deadline is still kept.

export type Answer = (d: DecisionView, answer: "yes" | "no", reason: string, voiceNoteId: string | null) => Promise<string | null>;

export default function Decisions({
  decisions,
  accountId,
  sender,
  online,
  onAnswer,
}: {
  decisions: DecisionView[];
  accountId: string;
  /** Posts a spoken reason to the trip's thread, kept on the phone with no signal. */
  sender: Sender;
  online: boolean;
  onAnswer: Answer;
}): React.JSX.Element | null {
  const open = toAnswer(decisions);
  if (open.length === 0) return null;
  return (
    <div className="flex flex-col gap-3">
      {open.map((d) => (
        <DecisionCard key={d.requestId} decision={d} accountId={accountId} sender={sender} online={online} onAnswer={onAnswer} />
      ))}
    </div>
  );
}

function DecisionCard({
  decision: d,
  accountId,
  sender,
  online,
  onAnswer,
}: {
  decision: DecisionView;
  accountId: string;
  sender: Sender;
  online: boolean;
  onAnswer: Answer;
}): React.JSX.Element {
  const t = useT();
  const thread = useTripThread(d.tripId, accountId);
  const recorder = useRecorder();
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState<"yes" | "no" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const words = question(d);
  const past = d.state === "expired" || new Date(d.deadline).getTime() < Date.now();
  const recording = recorder.state.status === "recording" ? recorder.state : null;
  const ready = recorder.state.status === "ready" ? recorder.state.recording : null;

  const answer = async (value: "yes" | "no") => {
    setBusy(value);
    setError(null);
    try {
      let voiceNoteId: string | null = null;
      const threadId = thread.data?.threadId ?? null;
      const voice = recording ? await recorder.stop() : ready;
      if (voice && threadId) {
        voiceNoteId = newId();
        await sender(
          postCommand({
            threadId,
            body: `Reason for ${value === "yes" ? "Yes" : "No"}: ${words.text.replace(/\{(\w+)\}/g, (w, k: string) => words.vars[k] ?? w)}`,
            to: "dispatch",
            voiceNoteId,
            clientMessageId: newId(),
          }),
          { threadId, voiceNoteId, blob: voice.blob, durationMs: voice.durationMs, peaks: voice.peaks },
        );
      }
      const failed = await onAnswer(d, value, reason.trim(), voiceNoteId);
      if (failed) setError(failed);
      else recorder.discard();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : t("Could not send. Try again."));
    } finally {
      setBusy(null);
    }
  };

  return (
    <Card label={t("The dispatcher needs your answer")} className="ring-2 ring-go-mint">
      <div className="flex flex-wrap items-center gap-2">
        <Chip tone="warn">{t("The dispatcher needs your answer")}</Chip>
        <Chip outline>{d.vehicleId}</Chip>
        <span className="text-[13px] text-go-muted">
          {past ? t("Past {time}. You can still answer.", { time: clock(d.deadline) }) : t("Answer by {time}", { time: clock(d.deadline) })}
        </span>
      </div>
      <p data-testid="decision-question" className="text-[19px] leading-snug font-medium text-black">
        {t(words.text, words.vars)}
      </p>
      <div className="flex flex-col gap-1.5">
        <div className="flex items-center gap-2">
          <input
            aria-label={t("Reason (optional)")}
            value={reason}
            maxLength={500}
            onChange={(e) => setReason(e.target.value)}
            placeholder={t("Reason (optional)")}
            className="min-h-12 min-w-0 flex-1 rounded-[16px] border border-[#dfe7e6] bg-go-surface px-3.5 text-[15px] text-black outline-none focus:border-go-teal"
          />
          {recorder.state.status !== "unavailable" && (
            <button
              type="button"
              aria-label={t(recording ? "Stop recording" : ready ? "Record the reason again" : "Say why (voice)")}
              aria-pressed={recording ? true : undefined}
              disabled={!thread.data || busy !== null}
              onClick={() => (recording ? void recorder.stop() : (recorder.discard(), void recorder.start()))}
              className={cx(
                "flex size-12 shrink-0 items-center justify-center rounded-full disabled:opacity-50",
                recording ? "bg-[#ea2525] text-white" : "bg-go-mint text-black",
              )}
            >
              {recording ? <StopIcon className="size-5" /> : <MicIcon className="size-5" />}
            </button>
          )}
        </div>
        {recording && <p role="status" className="px-1 text-[13px] text-[#c81e1e]">{t("Recording · tap to stop")}</p>}
        {ready && !recording && (
          <p className="flex items-center gap-2 px-1 text-[13px] text-go-muted">
            {t("Voice reason · {length}", { length: `0:${String(Math.round(ready.durationMs / 1000)).padStart(2, "0")}` })}
            <button type="button" aria-label={t("Remove the voice reason")} onClick={() => recorder.discard()} className="flex size-8 items-center justify-center rounded-full text-go-muted">
              <TrashIcon className="size-4" />
            </button>
          </p>
        )}
        {recorder.state.status === "unavailable" && <p className="px-1 text-[13px] text-go-warning-text">{t(recorder.state.reason)}</p>}
      </div>
      <div className="grid grid-cols-2 gap-2.5">
        <Button large tone="mint" busy={busy === "yes"} disabled={busy !== null} onClick={() => void answer("yes")}>
          {t("Yes")}
        </Button>
        <Button large tone="plain" busy={busy === "no"} disabled={busy !== null} onClick={() => void answer("no")}>
          {t("No")}
        </Button>
      </div>
      {!online && <p className="text-[13px] text-go-muted">{t("Offline. Your answer is kept on this phone and sent when the connection returns.")}</p>}
      {error && <p role="alert" className="text-[13px] text-[#c81e1e]">{error}</p>}
    </Card>
  );
}
