"use client";

import { useRef, useState } from "react";
import type { MessageView } from "../domain/messaging.ts";
import { heardUpTo, markHeard, unheard } from "../messaging/playlist.ts";
import { playServerSpeech, speak, stopSpeaking } from "../messaging/speech.ts";
import { plain, type Translate } from "../messaging/thread.ts";
import { voiceUrl } from "../messaging/useThread.ts";
import { playableUrl, speechUrl } from "../messaging/audioCache.ts";

// "Play messages" (roadmap: listen to every message): the messages not heard yet,
// one after another, a voice note as its own recording and a typed message read
// aloud in its language. For a driver it shows only when the Messages screen is
// not covered for driving (R-EXE-28), so it plays only while stopped.

export function PlayMessages({
  threadId,
  messages,
  accountId,
  tr = plain,
}: {
  threadId: string;
  messages: MessageView[];
  /** Plays the copies kept on this phone first, so it works with no signal (R-MSG-11). */
  accountId?: string;
  tr?: Translate;
}): React.JSX.Element | null {
  const [playing, setPlaying] = useState<number | null>(null);
  const [skipped, setSkipped] = useState(0);
  const stop = useRef(false);
  const audio = useRef<HTMLAudioElement | null>(null);
  const queue = unheard(messages, heardUpTo(threadId));
  if (queue.length === 0 && playing === null) return null;

  const playOne = async (m: MessageView): Promise<boolean> => {
    if (m.voiceNoteId) {
      const src = await playableUrl(accountId, voiceUrl(threadId, m.voiceNoteId));
      return new Promise((resolve) => {
        const a = new Audio(src);
        audio.current = a;
        a.onended = () => resolve(true);
        a.onerror = () => resolve(false);
        a.play().catch(() => resolve(false));
      });
    }
    // Our server's voice first; the phone's own voice when it is not ready.
    const src = await playableUrl(accountId, speechUrl(threadId, m.messageId));
    const played = await playServerSpeech(threadId, m.messageId, (a) => (audio.current = a), src);
    return played === "done" ? true : speak(m.body).then((s) => s === "done");
  };

  const start = async () => {
    stop.current = false;
    setSkipped(0);
    const list = unheard(messages, heardUpTo(threadId));
    for (let i = 0; i < list.length && !stop.current; i++) {
      setPlaying(i);
      const ok = await playOne(list[i]!);
      if (stop.current) break;
      if (!ok) setSkipped((n) => n + 1);
      markHeard(threadId, list[i]!.createdAt);
    }
    setPlaying(null);
  };

  const halt = () => {
    stop.current = true;
    audio.current?.pause();
    stopSpeaking();
    setPlaying(null);
  };

  return (
    <div data-testid="play-messages" className="flex items-center gap-3 rounded-go-card-s bg-go-surface px-3.5 py-2.5">
      {playing === null ? (
        <button type="button" onClick={() => void start()} className="min-h-11 flex-1 rounded-full bg-go-teal px-4 text-[15px] font-medium text-white">
          {tr(queue.length === 1 ? "Play 1 new message" : "Play {n} new messages", { n: queue.length })}
        </button>
      ) : (
        <>
          <span role="status" className="flex-1 text-[14px] text-go-ink">
            {tr("Playing {n} of {total}", { n: playing + 1, total: playing + queue.length })}
          </span>
          <button type="button" onClick={halt} className="min-h-11 rounded-full bg-go-card px-4 text-[14px] font-medium text-go-ink">
            {tr("Stop")}
          </button>
        </>
      )}
      {skipped > 0 && playing === null && (
        <span className="text-[12px] text-go-secondary">{tr("{n} could not be played: read them below", { n: skipped })}</span>
      )}
    </div>
  );
}
