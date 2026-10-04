"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";
import { VOICE_MAX_MS, type Recorder, type Recording } from "../messaging/recorder.ts";
import { plain, voiceLength, type Translate } from "../messaging/thread.ts";
import { liveHeight } from "../messaging/waveform.ts";
import { cx } from "./primitives.tsx";
import { ChevronLeftIcon, ChevronUpIcon, LockIcon, MicIcon, SendIcon, StopIcon, TrashIcon } from "./thread-icons.tsx";
import { VoiceNote } from "./VoiceNote.tsx";

// Recording a voice note as in a messaging app (issue #136). Hold the mic to
// record and let go to send; slide up to lock it and keep talking hands free,
// slide left to throw it away. Locked, the note can be stopped and listened to
// before it is sent. From a keyboard, the mic starts a locked recording, so it
// never needs a pointer held down.

type Mode = "idle" | "holding" | "locked" | "review";

const LOCK_AT = 70;
const CANCEL_AT = 110;

export function VoiceCapture({
  recorder,
  enabled,
  disabledReason,
  field,
  trailing,
  onSend,
  tr = plain,
}: {
  recorder: Recorder;
  enabled: boolean;
  disabledReason: string;
  /** The text box, shown while nothing is being recorded. */
  field: ReactNode;
  /** The send button when there is text to send; the mic shows otherwise. */
  trailing: ReactNode | null;
  onSend: (recording: Recording) => Promise<void>;
  tr?: Translate;
}): React.JSX.Element {
  const [mode, setMode] = useState<Mode>("idle");
  const [drag, setDrag] = useState({ x: 0, y: 0 });
  const [hint, setHint] = useState<string | null>(null);
  const origin = useRef<{ x: number; y: number } | null>(null);
  const modeRef = useRef<Mode>("idle");
  const released = useRef(false);
  const state = recorder.state;
  const ready = state.status === "ready" ? state : null;

  const set = (m: Mode) => {
    modeRef.current = m;
    setMode(m);
  };

  useEffect(() => {
    if (!hint) return;
    const t = window.setTimeout(() => setHint(null), 2500);
    return () => window.clearTimeout(t);
  }, [hint]);

  // The recorder stops itself at two minutes: a held or locked note goes to review.
  useEffect(() => {
    if (ready && (modeRef.current === "holding" || modeRef.current === "locked")) set("review");
  }, [ready]);

  const cancel = () => {
    recorder.discard();
    origin.current = null;
    setDrag({ x: 0, y: 0 });
    set("idle");
  };

  const send = async (recording: Recording | null) => {
    set("idle");
    setDrag({ x: 0, y: 0 });
    if (!recording) {
      setHint(tr("Hold to record, release to send"));
      return;
    }
    await onSend(recording);
  };

  // While the mic is held, follow the finger anywhere on the page: the button
  // it went down on is replaced by the recording bar.
  const latest = useRef({ cancel, send, recorder });
  latest.current = { cancel, send, recorder };
  useEffect(() => {
    if (mode !== "holding") return;
    const move = (e: PointerEvent) => {
      if (modeRef.current !== "holding" || !origin.current) return;
      const x = Math.min(0, e.clientX - origin.current.x);
      const y = Math.min(0, e.clientY - origin.current.y);
      setDrag({ x, y });
      if (y < -LOCK_AT) {
        set("locked");
        setDrag({ x: 0, y: 0 });
      } else if (x < -CANCEL_AT) latest.current.cancel();
    };
    const up = () => {
      released.current = true;
      if (modeRef.current === "holding") void latest.current.recorder.stop().then(latest.current.send);
    };
    const lost = () => modeRef.current === "holding" && latest.current.cancel();
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", up);
    window.addEventListener("pointercancel", lost);
    return () => {
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", up);
      window.removeEventListener("pointercancel", lost);
    };
  }, [mode]);

  const begin = async (locked: boolean) => {
    released.current = false;
    set(locked ? "locked" : "holding");
    const ok = await recorder.start();
    if (!ok) return set("idle");
    // Let go before the microphone answered: that was a tap.
    if (!locked && released.current) {
      recorder.discard();
      set("idle");
      setHint(tr("Hold to record, release to send"));
    }
  };

  const recording = state.status === "recording" ? state : null;
  const elapsed = recording ? recording.elapsedMs : 0;
  const live = recording ? recording.levels : [];

  if (mode === "idle" && !ready) {
    return (
      <div className="flex flex-col gap-1">
        <div className="flex items-end gap-2">
          {field}
          {trailing ?? (
            <button
              type="button"
              aria-label={enabled ? tr("Hold to record a voice note") : disabledReason}
              title={enabled ? tr("Hold to record, release to send") : disabledReason}
              disabled={!enabled}
              onPointerDown={(e) => {
                if (e.button !== 0) return;
                e.preventDefault();
                origin.current = { x: e.clientX, y: e.clientY };
                void begin(false);
              }}
              onClick={(e) => {
                // A keyboard press: no pointer to hold, so record locked.
                if (e.detail === 0) void begin(true);
              }}
              className="flex size-11 shrink-0 touch-none items-center justify-center rounded-full bg-go-teal text-white shadow-sm transition-transform active:scale-95 disabled:bg-go-surface disabled:text-go-secondary"
            >
              <MicIcon />
            </button>
          )}
        </div>
        {hint && (
          <p role="status" className="self-end rounded-full bg-go-ink px-3 py-1 text-xs text-go-card">
            {hint}
          </p>
        )}
      </div>
    );
  }

  if (mode === "review" || ready) {
    return (
      <div className="flex items-center gap-2 rounded-[22px] bg-go-surface p-1.5 pl-2">
        <button type="button" onClick={cancel} aria-label={tr("Delete voice note")} className="flex size-10 shrink-0 items-center justify-center rounded-full text-go-danger-strong hover:bg-go-danger-tint">
          <TrashIcon />
        </button>
        <div className="min-w-0 flex-1">
          {ready && (
            <VoiceNote src={ready.url} durationMs={ready.recording.durationMs} peaks={ready.recording.peaks} seed="draft" tone="mine" label={tr("Your voice note")} tr={tr} />
          )}
        </div>
        <button
          type="button"
          aria-label={tr("Send voice note")}
          onClick={() => ready && void send(ready.recording)}
          className="flex size-11 shrink-0 items-center justify-center rounded-full bg-go-teal text-white shadow-sm active:scale-95"
        >
          <SendIcon />
        </button>
      </div>
    );
  }

  // Recording, held or locked.
  const holding = mode === "holding";
  const cancelling = holding && drag.x < -CANCEL_AT / 2;
  return (
    <div className="relative flex items-center gap-2">
      <div
        role="status"
        aria-label={tr("Recording {time}", { time: voiceLength(elapsed) || "0:00" })}
        className={cx("flex h-11 min-w-0 flex-1 items-center gap-2.5 rounded-[22px] bg-go-surface px-3", cancelling && "bg-go-danger-tint")}
        style={holding ? { transform: `translateX(${Math.max(drag.x, -CANCEL_AT) / 3}px)` } : undefined}
      >
        {holding ? (
          <span aria-hidden className="size-2.5 shrink-0 animate-pulse rounded-full bg-go-danger" />
        ) : (
          <button type="button" onClick={cancel} aria-label={tr("Delete voice note")} className="-ml-1 flex size-9 shrink-0 items-center justify-center rounded-full text-go-danger-strong hover:bg-go-danger-tint">
            <TrashIcon />
          </button>
        )}
        <span className="w-10 shrink-0 text-[13px] font-medium tabular-nums text-go-ink">{voiceLength(elapsed) || "0:00"}</span>
        <span aria-hidden className="flex h-6 min-w-0 flex-1 items-center justify-end gap-[2px] overflow-hidden">
          {live.map((v, i) => (
            <span key={i} className="w-[3px] shrink-0 rounded-full bg-go-danger/80" style={{ height: `${Math.round(liveHeight(v) * 100)}%` }} />
          ))}
        </span>
        {holding && (
          <span className={cx("flex shrink-0 items-center gap-0.5 text-xs", cancelling ? "text-go-danger-strong" : "text-go-secondary")}>
            <ChevronLeftIcon className="size-4" />
            {tr("Slide to cancel")}
          </span>
        )}
        {!holding && elapsed > VOICE_MAX_MS - 10_000 && <span className="text-xs text-go-warning-text">{tr("Ends at 2:00")}</span>}
      </div>

      {holding ? (
        <>
          <span
            aria-hidden
            className="absolute right-0 bottom-14 flex w-11 flex-col items-center gap-1 rounded-full bg-go-card py-2.5 text-go-secondary shadow-go-card"
            style={{ transform: `translateY(${Math.max(drag.y, -LOCK_AT) / 2}px)` }}
          >
            <LockIcon className="size-4" />
            <ChevronUpIcon className="size-4 animate-bounce" />
          </span>
          <span aria-hidden className="flex size-14 shrink-0 scale-110 items-center justify-center rounded-full bg-go-danger text-white shadow-lg">
            <MicIcon className="size-6" />
          </span>
        </>
      ) : (
        <>
          <button
            type="button"
            onClick={() => void recorder.stop().then((r) => (r ? set("review") : void send(null)))}
            aria-label={tr("Stop and listen")}
            className="flex size-11 shrink-0 items-center justify-center rounded-full bg-go-surface text-go-danger-strong"
          >
            <StopIcon />
          </button>
          <button
            type="button"
            onClick={() => void recorder.stop().then(send)}
            aria-label={tr("Send voice note")}
            className="flex size-11 shrink-0 items-center justify-center rounded-full bg-go-teal text-white shadow-sm active:scale-95"
          >
            <SendIcon />
          </button>
        </>
      )}
    </div>
  );
}
