"use client";

import { useEffect, useState } from "react";
import { cannotSpeak, playServerSpeech, speak, stopSpeaking } from "../messaging/speech.ts";
import { plain, type Translate } from "../messaging/thread.ts";
import { cx } from "./primitives.tsx";

// Listen to a typed message (roadmap: every message can be heard). The phone's
// own voice in the message's language, labelled as the phone's; when the phone
// has no voice for that language the button says so instead of reading it in
// the wrong one.

export function ListenButton({
  text,
  threadId,
  messageId,
  tr = plain,
  className,
}: {
  text: string;
  /** With these, the server's audio of the message is played first (R-MSG-10). */
  threadId?: string;
  messageId?: string;
  tr?: Translate;
  className?: string;
}): React.JSX.Element {
  const [state, setState] = useState<"idle" | "speaking">("idle");
  const [note, setNote] = useState<string | null>(null);
  const [audio, setAudio] = useState<HTMLAudioElement | null>(null);
  useEffect(() => () => stopSpeaking(), []);

  const toggle = async () => {
    if (state === "speaking") {
      audio?.pause();
      stopSpeaking();
      setState("idle");
      return;
    }
    setNote(null);
    setState("speaking");
    // Our server's voice first, in every language; the phone's own voice when it is not ready.
    if (threadId && messageId && (await playServerSpeech(threadId, messageId, setAudio)) === "done") {
      setState("idle");
      return;
    }
    const result = await speak(text);
    setState("idle");
    if (result === "no-voice" || result === "unsupported") setNote(tr(cannotSpeak(text, result)));
  };

  return (
    <span className={cx("flex items-center gap-1.5", className)}>
      <button
        type="button"
        onClick={() => void toggle()}
        aria-label={tr(state === "speaking" ? "Stop reading" : "Listen")}
        aria-pressed={state === "speaking" || undefined}
        className="flex min-h-7 items-center gap-1 rounded-full px-2 text-[12px] font-medium text-go-teal"
      >
        <svg aria-hidden width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <path d="M11 5 6 9H2v6h4l5 4V5z" />
          {state === "speaking" ? <path d="M15 9l6 6M21 9l-6 6" /> : <path d="M15.5 8.5a5 5 0 0 1 0 7M19 5a10 10 0 0 1 0 14" />}
        </svg>
        {tr(state === "speaking" ? "Stop" : "Listen")}
      </button>
      {note && <span role="status" className="text-[11px] text-go-secondary">{note}</span>}
    </span>
  );
}
