"use client";

import { useEffect, useRef, useState } from "react";
import { peaksFrom, placeholderPeaks, seekFraction } from "../messaging/waveform.ts";
import { plain, voiceLength, type Translate } from "../messaging/thread.ts";
import { cx } from "./primitives.tsx";
import { PauseIcon, PlayIcon } from "./thread-icons.tsx";

// A voice note as the thread draws it (issue #136): a round play button, the
// note's waveform as bars that fill in as it plays (tap a bar to jump there),
// the time, and the playback speed. Coloured by the bubble it sits in: the
// writer's own in teal, someone else's in ink, a report in red.

export type VoiceTone = "mine" | "theirs" | "report";

const TONE: Record<VoiceTone, { button: string; played: string; rest: string; text: string }> = {
  mine: { button: "bg-go-teal text-white", played: "bg-go-teal", rest: "bg-go-teal/45", text: "text-go-on-soft" },
  theirs: { button: "bg-go-ink text-go-card", played: "bg-go-ink", rest: "bg-go-ink/25", text: "text-go-secondary" },
  report: { button: "bg-go-danger text-white", played: "bg-go-danger", rest: "bg-go-danger/30", text: "text-go-danger-strong" },
};

const SPEEDS = [1, 1.5, 2] as const;

/** Waveforms already worked out, by audio address, so a thread re-renders without decoding again. */
const decoded = new Map<string, number[]>();

async function decode(src: string): Promise<number[] | null> {
  const known = decoded.get(src);
  if (known) return known;
  try {
    const response = await fetch(src, { credentials: "same-origin" });
    if (!response.ok) return null;
    const bytes = await response.arrayBuffer();
    const Ctx = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!Ctx) return null;
    const ctx = new Ctx();
    try {
      const buffer = await ctx.decodeAudioData(bytes);
      const peaks = peaksFrom(buffer.getChannelData(0));
      decoded.set(src, peaks);
      return peaks;
    } finally {
      void ctx.close().catch(() => undefined);
    }
  } catch {
    // Not decodable here (WebM on older Safari): the placeholder bars stay.
    return null;
  }
}

export function VoiceNote({
  src,
  durationMs,
  peaks: given,
  seed,
  tone,
  label,
  tr = plain,
}: {
  src: string;
  durationMs: number | null;
  /**
   * Known bars, 0 to 1: a note just recorded, or the waveform the recording
   * phone sent with the audio. Otherwise worked out from the audio.
   */
  peaks?: number[];
  /** Keeps the placeholder bars the same for one note. */
  seed: string;
  tone: VoiceTone;
  label: string;
  tr?: Translate;
}): React.JSX.Element {
  const audio = useRef<HTMLAudioElement>(null);
  const bars = useRef<HTMLDivElement>(null);
  const [peaks, setPeaks] = useState<number[]>(() => given ?? decoded.get(src) ?? placeholderPeaks(seed));
  const [playing, setPlaying] = useState(false);
  const [at, setAt] = useState(0);
  const [speed, setSpeed] = useState<(typeof SPEEDS)[number]>(1);
  const [failed, setFailed] = useState(false);
  const look = TONE[tone];
  const total = (durationMs ?? 0) / 1000;

  // A new array each render is the same waveform: follow its values, not its identity.
  const givenKey = given && given.length > 0 ? given.join(",") : null;
  useEffect(() => {
    if (given && given.length > 0) return setPeaks(given);
    let live = true;
    void decode(src).then((p) => live && p && setPeaks(p));
    return () => {
      live = false;
    };
  }, [src, givenKey]);

  useEffect(() => {
    const el = audio.current;
    if (!el) return;
    let frame = 0;
    const tick = () => {
      setAt(el.currentTime);
      if (!el.paused) frame = requestAnimationFrame(tick);
    };
    // The position the audio is really at: frames while playing, timeupdate as a backstop.
    const onTime = () => setAt(el.currentTime);
    const onMeta = () => setAt(el.currentTime);
    const onPlay = () => {
      setPlaying(true);
      frame = requestAnimationFrame(tick);
    };
    const onStop = () => {
      setPlaying(false);
      cancelAnimationFrame(frame);
      setAt(el.currentTime);
    };
    const onEnd = () => {
      onStop();
      el.currentTime = 0;
      setAt(0);
    };
    const onError = () => setFailed(true);
    el.addEventListener("timeupdate", onTime);
    el.addEventListener("loadedmetadata", onMeta);
    el.addEventListener("play", onPlay);
    el.addEventListener("pause", onStop);
    el.addEventListener("ended", onEnd);
    el.addEventListener("error", onError);
    return () => {
      cancelAnimationFrame(frame);
      el.removeEventListener("timeupdate", onTime);
      el.removeEventListener("loadedmetadata", onMeta);
      el.removeEventListener("play", onPlay);
      el.removeEventListener("pause", onStop);
      el.removeEventListener("ended", onEnd);
      el.removeEventListener("error", onError);
    };
  }, []);

  // The audio's own length when the browser knows it; a fresh WebM recording reports
  // Infinity until played through, so the length recorded with the note stands in.
  const known = audio.current && Number.isFinite(audio.current.duration) && audio.current.duration > 0 ? audio.current.duration : 0;
  const length = known || total;
  const progress = length > 0 ? Math.min(1, at / length) : 0;

  const toggle = () => {
    const el = audio.current;
    if (!el) return;
    if (el.paused) {
      // One note plays at a time, as in any messaging app.
      document.querySelectorAll<HTMLAudioElement>("audio[data-voice-note]").forEach((other) => other !== el && other.pause());
      el.playbackRate = speed;
      void el.play().catch(() => setFailed(true));
    } else el.pause();
  };

  const seek = (clientX: number) => {
    const el = audio.current;
    const box = bars.current?.getBoundingClientRect();
    if (!el || !box || length <= 0) return;
    el.currentTime = seekFraction(clientX, box.left, box.width) * length;
    setAt(el.currentTime);
  };

  const nextSpeed = () => {
    const next = SPEEDS[(SPEEDS.indexOf(speed) + 1) % SPEEDS.length]!;
    setSpeed(next);
    if (audio.current) audio.current.playbackRate = next;
  };

  return (
    <div role="group" aria-label={label} data-testid="voice-note" className="flex w-[248px] max-w-full items-center gap-2.5 py-0.5">
      <audio ref={audio} src={src} preload="metadata" data-voice-note aria-label={label} className="hidden" />
      <button
        type="button"
        onClick={toggle}
        disabled={failed}
        aria-label={playing ? tr("Pause") : tr("Play")}
        className={cx("flex size-10 shrink-0 items-center justify-center rounded-full shadow-sm transition-transform active:scale-95 disabled:opacity-40", look.button)}
      >
        {playing ? <PauseIcon className="size-[18px]" /> : <PlayIcon className="ml-0.5 size-[18px]" />}
      </button>
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <div
          ref={bars}
          role="slider"
          tabIndex={0}
          aria-label={tr("Position")}
          aria-valuemin={0}
          aria-valuemax={Math.round(length)}
          aria-valuenow={Math.round(at)}
          aria-valuetext={`${voiceLength(at * 1000) || "0:00"} / ${voiceLength(length * 1000) || "0:00"}`}
          onPointerDown={(e) => seek(e.clientX)}
          onKeyDown={(e) => {
            const el = audio.current;
            if (!el || length <= 0) return;
            if (e.key === "ArrowRight") el.currentTime = Math.min(length, el.currentTime + 2);
            else if (e.key === "ArrowLeft") el.currentTime = Math.max(0, el.currentTime - 2);
            else return;
            e.preventDefault();
            setAt(el.currentTime);
          }}
          className="flex h-8 cursor-pointer items-center gap-[2px] outline-none focus-visible:rounded focus-visible:ring-2 focus-visible:ring-go-teal"
        >
          {peaks.map((p, i) => (
            <span
              key={i}
              aria-hidden
              className={cx("min-w-[2px] flex-1 rounded-full transition-colors", (i + 0.5) / peaks.length <= progress ? look.played : look.rest)}
              style={{ height: `${Math.round(Math.max(0.12, p) * 100)}%` }}
            />
          ))}
        </div>
        <span className={cx("flex items-center justify-between text-[11px] tabular-nums", look.text)}>
          <span>{failed ? tr("Could not play") : voiceLength((playing || at > 0 ? at : length) * 1000) || "0:00"}</span>
          <button type="button" onClick={nextSpeed} aria-label={tr("Playback speed")} className="rounded-full px-1.5 font-semibold hover:bg-black/5">
            {speed}×
          </button>
        </span>
      </div>
    </div>
  );
}
