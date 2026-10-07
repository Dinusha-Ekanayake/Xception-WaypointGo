// The driver's own sounds while driving (Figma "Driving mode"): short tones made
// in the page, so they are Waypoint's and not lost among other apps' sounds.
// A browser plays sound only after a tap, so the first tap anywhere unlocks it.
// Nothing is spoken: the message waits for a stop (R-EXE-28).

let context: AudioContext | null = null;

function audio(): AudioContext | null {
  if (typeof window === "undefined") return null;
  const Ctor = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
  if (!Ctor) return null;
  context ??= new Ctor();
  return context;
}

/** Call once: the first tap lets the page make sound. */
export function unlockSoundOnFirstTap(): () => void {
  const unlock = () => void audio()?.resume().catch(() => undefined);
  window.addEventListener("pointerdown", unlock, { once: true });
  return () => window.removeEventListener("pointerdown", unlock);
}

/**
 * A message: two soft rising tones. A changed plan: three firmer tones, so the
 * driver knows without looking that the stops have changed.
 */
export function chime(kind: "message" | "plan"): void {
  const ctx = audio();
  if (!ctx || ctx.state !== "running") return;
  const notes = kind === "plan" ? [660, 880, 990] : [740, 988];
  notes.forEach((hz, i) => {
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    const at = ctx.currentTime + i * 0.18;
    osc.frequency.value = hz;
    gain.gain.setValueAtTime(0.0001, at);
    gain.gain.exponentialRampToValueAtTime(kind === "plan" ? 0.35 : 0.2, at + 0.02);
    gain.gain.exponentialRampToValueAtTime(0.0001, at + 0.16);
    osc.connect(gain).connect(ctx.destination);
    osc.start(at);
    osc.stop(at + 0.17);
  });
  navigator.vibrate?.(kind === "plan" ? [120, 80, 120, 80, 120] : [80, 60, 80]);
}
