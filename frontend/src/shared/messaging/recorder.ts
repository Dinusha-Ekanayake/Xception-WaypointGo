"use client";

import { useCallback, useEffect, useRef, useState } from "react";

// A voice note from the phone's microphone (issue #136, R-MSG-06), in the
// format the device records: Opus in WebM on Chrome and Android, AAC in MP4 on
// Safari. At most two minutes; the server holds the same limits.

export const VOICE_MAX_MS = 120_000;

const TYPES = ["audio/webm;codecs=opus", "audio/webm", "audio/mp4", "audio/ogg;codecs=opus", "audio/aac"];

export type Recording = { blob: Blob; durationMs: number };

export type RecorderState =
  | { status: "idle" }
  | { status: "recording"; elapsedMs: number }
  | { status: "ready"; recording: Recording; url: string }
  | { status: "unavailable"; reason: string };

/** The first type this browser can record, or null. */
export function recordableType(): string | null {
  if (typeof MediaRecorder === "undefined") return null;
  return TYPES.find((t) => MediaRecorder.isTypeSupported?.(t)) ?? "";
}

export function useRecorder(): {
  state: RecorderState;
  start: () => Promise<void>;
  stop: () => void;
  discard: () => void;
  /** Puts back a recording that was discarded, for undo. */
  restore: (recording: Recording) => void;
} {
  const [state, setState] = useState<RecorderState>({ status: "idle" });
  const recorder = useRef<MediaRecorder | null>(null);
  const chunks = useRef<Blob[]>([]);
  const startedAt = useRef(0);
  const timer = useRef<number | null>(null);

  const release = useCallback(() => {
    if (timer.current !== null) window.clearInterval(timer.current);
    timer.current = null;
    recorder.current?.stream.getTracks().forEach((t) => t.stop());
  }, []);

  useEffect(() => release, [release]);

  const start = useCallback(async () => {
    const type = recordableType();
    if (type === null || !navigator.mediaDevices?.getUserMedia) {
      setState({ status: "unavailable", reason: "This browser cannot record. Type the message instead." });
      return;
    }
    let stream: MediaStream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    } catch {
      setState({ status: "unavailable", reason: "The microphone is not allowed. Allow it in the browser to send a voice note." });
      return;
    }
    const rec = new MediaRecorder(stream, type ? { mimeType: type } : undefined);
    chunks.current = [];
    rec.ondataavailable = (e) => {
      if (e.data.size > 0) chunks.current.push(e.data);
    };
    rec.onstop = () => {
      const durationMs = Math.min(VOICE_MAX_MS, Date.now() - startedAt.current);
      const blob = new Blob(chunks.current, { type: (rec.mimeType || type || "audio/webm").split(";")[0] });
      release();
      setState({ status: "ready", recording: { blob, durationMs }, url: URL.createObjectURL(blob) });
    };
    recorder.current = rec;
    startedAt.current = Date.now();
    rec.start(1000);
    setState({ status: "recording", elapsedMs: 0 });
    timer.current = window.setInterval(() => {
      const elapsedMs = Date.now() - startedAt.current;
      if (elapsedMs >= VOICE_MAX_MS) rec.stop();
      else setState({ status: "recording", elapsedMs });
    }, 250);
  }, [release]);

  const stop = useCallback(() => {
    if (recorder.current?.state === "recording") recorder.current.stop();
  }, []);

  const discard = useCallback(() => {
    if (recorder.current?.state === "recording") {
      recorder.current.onstop = null;
      recorder.current.stop();
    }
    release();
    setState((s) => {
      if (s.status === "ready") URL.revokeObjectURL(s.url);
      return { status: "idle" };
    });
  }, [release]);

  const restore = useCallback((recording: Recording) => {
    setState((s) => {
      if (s.status === "ready") URL.revokeObjectURL(s.url);
      return { status: "ready", recording, url: URL.createObjectURL(recording.blob) };
    });
  }, []);

  return { state, start, stop, discard, restore };
}
