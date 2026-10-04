"use client";

import { useEffect, useRef, useState } from "react";
import type { FailureReason } from "@shared/domain/types";
import type { Sender } from "@shared/messaging/senders";
import { newId, postCommand, useTripThread } from "@shared/messaging/useThread";
import { useRecorder } from "@shared/messaging/recorder";
import { Sheet } from "@shared/ui";
import type { Stop } from "../data/run.ts";
import { ActionButton, Banner, OutlineButton, SoftButton } from "../ui.tsx";

// The driver's sheets: Figma "Driver: Report a problem", "Sign out: confirm"
// and the confirmed popup. A row sends when it is slid across, so a tap does
// not. A voice note starts on a tap and sends itself at 15 seconds. The sent
// card closes on Okay, or on its own after a few seconds.

export type Problem =
  | { kind: "report"; fault: "road" | "vehicle"; description: string }
  | { kind: "not-delivered"; reason: FailureReason };

const VOICE_MS = 15_000;

const CHOICES: Array<{ id: string; label: string; problem: () => Problem; onStop: boolean }> = [
  { id: "late", label: "Running late", onStop: true, problem: () => ({ kind: "report", fault: "road", description: "Running late" }) },
  { id: "unload", label: "Cannot unload here", onStop: true, problem: () => ({ kind: "not-delivered", reason: "access_blocked" }) },
  { id: "nobody", label: "No one at the outlet", onStop: true, problem: () => ({ kind: "not-delivered", reason: "outlet_closed" }) },
  { id: "vehicle", label: "Vehicle problem", onStop: false, problem: () => ({ kind: "report", fault: "vehicle", description: "Vehicle problem" }) },
  { id: "damaged", label: "Goods damaged or short", onStop: true, problem: () => ({ kind: "not-delivered", reason: "goods_damaged" }) },
];

/** A row that sends only when slid across. Arrow right or Enter does the same for a keyboard. */
function SlideRow({ label, disabled, onSend }: { label: string; disabled: boolean; onSend: () => Promise<boolean> }): React.JSX.Element {
  const track = useRef<HTMLDivElement>(null);
  const [x, setX] = useState(0);
  const dragging = useRef(false);
  const sent = useRef(false);
  const limit = () => Math.max(0, (track.current?.clientWidth ?? 0) - 56);

  const release = (at: number) => {
    dragging.current = false;
    const max = limit();
    if (!sent.current && !disabled && max > 0 && at >= max * 0.72) {
      sent.current = true;
      setX(max);
      void onSend().then((ok) => {
        if (!ok) {
          sent.current = false;
          setX(0);
        }
      });
    } else if (!sent.current) {
      setX(0);
    }
  };

  return (
    <div
      ref={track}
      role="slider"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={limit() === 0 ? 0 : Math.round((x / limit()) * 100)}
      aria-valuetext="Slide right to send"
      aria-disabled={disabled}
      tabIndex={disabled ? -1 : 0}
      onKeyDown={(e) => {
        if (disabled || sent.current) return;
        if (e.key === "ArrowRight" || e.key === "Enter") {
          e.preventDefault();
          sent.current = true;
          void onSend().then((ok) => {
            if (!ok) {
              sent.current = false;
              setX(0);
            }
          });
        }
      }}
      onPointerDown={(e) => {
        if (disabled || sent.current) return;
        dragging.current = true;
        (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
      }}
      onPointerMove={(e) => {
        if (!dragging.current || sent.current || !track.current) return;
        const rect = track.current.getBoundingClientRect();
        const next = Math.max(0, Math.min(e.clientX - rect.left - 28, limit()));
        setX(next);
        if (next >= limit() * 0.72) release(next);
      }}
      onPointerUp={(e) => {
        if (!dragging.current) return;
        const rect = track.current?.getBoundingClientRect();
        release(rect ? Math.max(0, e.clientX - rect.left - 28) : 0);
      }}
      onPointerCancel={() => release(0)}
      className="relative h-16 touch-none overflow-hidden rounded-[20px] bg-go-surface text-go-ink outline-none focus-visible:ring-2 focus-visible:ring-go-signal"
    >
      <span className="pointer-events-none absolute inset-y-0 right-0 w-1/3 bg-go-action/30" style={{ opacity: limit() === 0 ? 0 : x / limit() }} />
      <span className="pointer-events-none absolute inset-0 flex items-center gap-3 px-3">
        <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-go-card text-[18px]">›</span>
        <span className="min-w-0 flex-1 text-[17px] font-medium">{label}</span>
        <span className="text-[13px] text-go-muted">slide ›››</span>
      </span>
      {sent.current && <span className="absolute inset-0 flex items-center justify-center bg-go-action text-[17px] font-medium text-go-on-action">{label}</span>}
    </div>
  );
}

export function ProblemSheet({
  stop,
  tripId,
  accountId,
  sender,
  busy,
  error,
  onSend,
  onClose,
}: {
  stop: Stop | null;
  tripId: string | null;
  accountId: string;
  sender: Sender;
  busy: boolean;
  error: string | null;
  onSend: (problem: Problem) => Promise<boolean>;
  onClose: () => void;
}): React.JSX.Element {
  const thread = useTripThread(tripId, accountId);
  const recorder = useRecorder();
  const [sent, setSent] = useState<string | null>(null);
  const [voiceError, setVoiceError] = useState<string | null>(null);
  const sending = useRef(false);
  const close = useRef(onClose);
  close.current = onClose;
  // A report from the trip card is about the vehicle. A stop can also be about that delivery.
  const choices = stop ? CHOICES : CHOICES.filter((c) => !c.onStop);
  const recording = recorder.state.status === "recording" ? recorder.state : null;

  useEffect(() => {
    if (!sent) return;
    const timer = window.setTimeout(() => close.current(), 4000);
    return () => window.clearTimeout(timer);
  }, [sent]);

  const sendChoice = async (choice: (typeof CHOICES)[number]): Promise<boolean> => {
    if (sending.current) return false;
    sending.current = true;
    const problem = choice.problem();
    const ok = await onSend(problem);
    sending.current = false;
    if (ok && problem.kind === "report") setSent(choice.label);
    return ok;
  };

  const sendVoice = async () => {
    if (sending.current) return;
    sending.current = true;
    setVoiceError(null);
    const note = recorder.state.status === "recording" ? await recorder.stop() : recorder.state.status === "ready" ? recorder.state.recording : null;
    if (!note) {
      sending.current = false;
      setVoiceError("That was too short to send. Tap and speak, and it sends at 15 seconds.");
      return;
    }
    const threadId = thread.data?.threadId ?? null;
    try {
      if (threadId) {
        const voiceNoteId = newId();
        await sender(postCommand({
          threadId,
          body: "Voice note",
          to: "dispatch",
          report: stop ? "other" : "vehicle_fault",
          voiceNoteId,
          clientMessageId: newId(),
        }), { threadId, voiceNoteId, blob: note.blob, durationMs: note.durationMs, peaks: note.peaks });
      } else {
        const ok = await onSend({ kind: "report", fault: "vehicle", description: "Voice note" });
        if (!ok) {
          sending.current = false;
          return;
        }
      }
      setSent("Voice note");
    } catch (failure) {
      setVoiceError(failure instanceof Error ? failure.message : "The voice note could not be sent.");
    } finally {
      sending.current = false;
    }
  };

  useEffect(() => {
    if (!recording || recording.elapsedMs < VOICE_MS || sending.current) return;
    void sendVoice();
  }, [recording, sendVoice]);

  if (sent) {
    return (
      <Sheet label="Report sent" onClose={onClose}>
        <div role="status" aria-live="polite">
          <h2 className="text-[26px] font-medium text-go-ink">Report sent</h2>
          <p className="mt-1 text-[15px] text-go-muted">
            {sent}
            {stop ? ` · Stop ${String(stop.sequence).padStart(2, "0")} · ${stop.outletId}` : ""}
          </p>
          <p className="mt-2 text-[14px] text-go-muted">This closes on its own in a few seconds.</p>
        </div>
        <ActionButton onClick={onClose}>Okay</ActionButton>
      </Sheet>
    );
  }

  const elapsed = recording ? Math.min(VOICE_MS, recording.elapsedMs) : 0;
  const mic = recorder.state.status === "unavailable" ? recorder.state.reason : null;

  return (
    <Sheet label="Report a problem" onClose={onClose}>
      <div>
        <h2 className="text-[26px] font-medium text-go-ink">Report a problem</h2>
        <p className="text-[15px] text-go-muted">
          {stop ? `Stop ${String(stop.sequence).padStart(2, "0")} · ${stop.outletId}` : "About the vehicle"}
          {" · slide a row right to send it"}
        </p>
      </div>
      <div className="flex flex-col gap-2.5">
        {choices.map((c) => (
          <SlideRow key={c.id} label={c.label} disabled={busy || sending.current} onSend={() => sendChoice(c)} />
        ))}
      </div>
      <p className="text-[14px] text-go-muted">None of these? Record a voice note to explain.</p>
      <button
        type="button"
        onClick={() => void (recording ? sendVoice() : recorder.start())}
        disabled={busy || Boolean(mic)}
        className="flex h-[76px] w-full items-center gap-4 rounded-full bg-go-canvas px-3 text-left disabled:opacity-50"
      >
        <span
          aria-hidden
          className="relative flex size-14 shrink-0 items-center justify-center"
          style={{ background: recording ? `conic-gradient(var(--color-go-action) ${(elapsed / VOICE_MS) * 360}deg, transparent 0deg)` : undefined, borderRadius: "9999px" }}
        >
          <span className="flex size-12 items-center justify-center rounded-full bg-go-action text-go-on-action">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="currentColor">
              <path d="M12 15a3.2 3.2 0 0 0 3.2-3.2V6.2a3.2 3.2 0 1 0-6.4 0v5.6A3.2 3.2 0 0 0 12 15Z" />
              <path d="M17.2 11.2a1 1 0 1 0-2 0 3.2 3.2 0 0 1-6.4 0 1 1 0 1 0-2 0 5.2 5.2 0 0 0 4.2 5.1V19H9.2a1 1 0 1 0 0 2h5.6a1 1 0 1 0 0-2H13v-2.7a5.2 5.2 0 0 0 4.2-5.1Z" />
            </svg>
          </span>
        </span>
        <span className="text-[18px] font-medium text-go-ink">
          {recording ? `Recording · ${Math.ceil((VOICE_MS - elapsed) / 1000)}s` : "Tap to record"}
        </span>
      </button>
      {mic && <p className="text-[14px] text-go-muted">{mic}</p>}
      {(error || voiceError) && <Banner tone="bad" title={error ?? voiceError ?? ""} live />}
      <ActionButton onClick={onClose}>Close</ActionButton>
    </Sheet>
  );
}

export function SavedSheet({
  title,
  onPhone,
  last,
  warning,
  onNext,
  onHandover,
}: {
  title: string;
  /** Opens the store manager's PIN; absent when nothing was handed over. */
  onHandover?: () => void;
  /** Something about this stop still needs the driver, such as proof the server refused. */
  warning?: string | null;
  /** Saved on this phone and not yet on the server. */
  onPhone: boolean;
  /** No stop is left to do. */
  last: boolean;
  onNext: () => void;
}): React.JSX.Element {
  return (
    <Sheet label={title} onClose={onNext}>
      <div role="status" aria-live="polite">
        <h2 className="text-[26px] font-medium text-go-ink">{title}</h2>
        <p className="mt-1 text-[15px] text-go-muted">
          {onPhone
            ? "Saved on this phone. It is sent to dispatch as soon as the connection is back; keep the app installed until then."
            : "Saved and sent to dispatch."}
        </p>
      </div>
      {warning && <Banner tone="warn" title={warning} />}
      {onHandover && <OutlineButton onClick={onHandover}>Enter store manager PIN</OutlineButton>}
      <ActionButton onClick={onNext}>{last ? "Finish run" : "Next stop"}</ActionButton>
    </Sheet>
  );
}

export function SignOutSheet({
  waiting,
  online,
  onSignOut,
  onClose,
}: {
  /** Writes and proof artifacts still only on this phone. */
  waiting: number;
  online: boolean;
  onSignOut: () => void;
  onClose: () => void;
}): React.JSX.Element {
  return (
    <Sheet label="Sign out" onClose={onClose}>
      <div className="rounded-[22px] bg-go-surface p-5">
        <h2 className="text-[24px] font-medium text-go-ink">Sign out of GO?</h2>
        {waiting > 0 ? (
          <p className="mt-1 text-[16px] text-go-ink">
            {waiting} {waiting === 1 ? "record is" : "records are"} still only on this phone.{" "}
            {online
              ? `Wait for ${waiting === 1 ? "it" : "them"} to be sent, or review anything the server refused, before you sign out.`
              : `Reconnect so ${waiting === 1 ? "it" : "they"} can be sent before you sign out.`}
          </p>
        ) : (
          <p className="mt-1 text-[16px] text-go-muted">You'll need your email and password to sign in again. Your work is saved and sent.</p>
        )}
      </div>
      {waiting === 0 ? <ActionButton onClick={onSignOut}>Sign out</ActionButton> : <SoftButton onClick={onClose}>Stay signed in</SoftButton>}
      {waiting === 0 && <OutlineButton onClick={onClose}>Cancel</OutlineButton>}
    </Sheet>
  );
}
