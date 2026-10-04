"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { resample } from "./waveform.ts";

// A voice note from the phone's microphone (issue #136, R-MSG-06), in the
// format the device records: Opus in WebM on Chrome and Android, AAC in MP4 on
// Safari. At most two minutes; the server holds the same limits. While it
// records, the loudness is sampled ten times a second: the live bars, and the
// waveform the sent note shows.

export const VOICE_MAX_MS = 120_000;
/** Shorter than this is a tap, not a voice note. */
export const VOICE_MIN_MS = 700;

const TYPES = ["audio/webm;codecs=opus", "audio/webm", "audio/mp4", "audio/ogg;codecs=opus", "audio/aac"];

export type Recording = { blob: Blob; durationMs: number; peaks: number[] };

export type RecorderState =
  | { status: "idle" }
  | { status: "recording"; elapsedMs: number; levels: number[] }
  | { status: "ready"; recording: Recording; url: string }
  | { status: "unavailable"; reason: string };

/** The first type this browser can record, or null. */
export function recordableType(): string | null {
  if (typeof MediaRecorder === "undefined") return null;
  return TYPES.find((t) => MediaRecorder.isTypeSupported?.(t)) ?? "";
}

export type Recorder = {
  state: RecorderState;
  start: () => Promise<boolean>;
  /** Stops and keeps the note for a listen before sending; null when too short. */
  stop: () => Promise<Recording | null>;
  discard: () => void;
};

export function useRecorder(): Recorder {
  const [state, setState] = useState<RecorderState>({ status: "idle" });
  const recorder = useRef<MediaRecorder | null>(null);
  const chunks = useRef<Blob[]>([]);
  const levels = useRef<number[]>([]);
  const startedAt = useRef(0);
  const timer = useRef<number | null>(null);
  const audio = useRef<AudioContext | null>(null);
  const finished = useRef<((r: Recording | null) => void) | null>(null);

  const release = useCallback(() => {
    if (timer.current !== null) window.clearInterval(timer.current);
    timer.current = null;
    recorder.current?.stream.getTracks().forEach((t) => t.stop());
    void audio.current?.close().catch(() => undefined);
    audio.current = null;
  }, []);

  useEffect(() => release, [release]);

  const start = useCallback(async () => {
    const type = recordableType();
    if (type === null || !navigator.mediaDevices?.getUserMedia) {
      setState({ status: "unavailable", reason: "This browser cannot record. Type the message instead." });
      return false;
    }
    let stream: MediaStream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    } catch {
      setState({ status: "unavailable", reason: "The microphone is not allowed. Allow it in the browser to send a voice note." });
      return false;
    }
    const rec = new MediaRecorder(stream, type ? { mimeType: type } : undefined);
    chunks.current = [];
    levels.current = [];
    let analyser: AnalyserNode | null = null;
    try {
      const ctx = new AudioContext();
      analyser = ctx.createAnalyser();
      analyser.fftSize = 512;
      ctx.createMediaStreamSource(stream).connect(analyser);
      audio.current = ctx;
    } catch {
      analyser = null;
    }
    const buffer = analyser ? new Uint8Array(analyser.fftSize) : null;
    rec.ondataavailable = (e) => {
      if (e.data.size > 0) chunks.current.push(e.data);
    };
    rec.onstop = () => {
      const durationMs = Math.min(VOICE_MAX_MS, Date.now() - startedAt.current);
      const blob = new Blob(chunks.current, { type: (rec.mimeType || type || "audio/webm").split(";")[0] });
      const peaks = resample(levels.current);
      release();
      const done = finished.current;
      finished.current = null;
      if (durationMs < VOICE_MIN_MS || blob.size === 0) {
        setState({ status: "idle" });
        done?.(null);
        return;
      }
      const recording = { blob, durationMs, peaks };
      setState({ status: "ready", recording, url: URL.createObjectURL(blob) });
      done?.(recording);
    };
    recorder.current = rec;
    startedAt.current = Date.now();
    rec.start(250);
    setState({ status: "recording", elapsedMs: 0, levels: [] });
    timer.current = window.setInterval(() => {
      const elapsedMs = Date.now() - startedAt.current;
      if (analyser && buffer) {
        analyser.getByteTimeDomainData(buffer);
        let sum = 0;
        for (const v of buffer) sum += ((v - 128) / 128) ** 2;
        levels.current.push(Math.sqrt(sum / buffer.length));
      } else {
        levels.current.push(0.3 + Math.random() * 0.4);
      }
      if (elapsedMs >= VOICE_MAX_MS) rec.stop();
      else setState({ status: "recording", elapsedMs, levels: levels.current.slice(-48) });
    }, 100);
    return true;
  }, [release]);

  const stop = useCallback(
    () =>
      new Promise<Recording | null>((resolve) => {
        const rec = recorder.current;
        if (rec?.state !== "recording") return resolve(state.status === "ready" ? state.recording : null);
        finished.current = resolve;
        rec.stop();
      }),
    [state],
  );

  const discard = useCallback(() => {
    if (recorder.current?.state === "recording") {
      recorder.current.onstop = null;
      recorder.current.stop();
    }
    finished.current?.(null);
    finished.current = null;
    release();
    setState((s) => {
      if (s.status === "ready") URL.revokeObjectURL(s.url);
      return { status: "idle" };
    });
  }, [release]);

  return { state, start, stop, discard };
}
