// The bars of a voice note (issue #136): the loudness of each slice of the
// audio, 0 to 1, drawn as a row of rounded bars like a messaging app. Pure, so
// the recorder, the player and the tests share one definition.

/** How many bars a voice note shows. */
export const BARS = 36;

/** The quietest a bar is drawn, so silence still reads as a voice note. */
const FLOOR = 0.08;

/** Loudness of each of `bars` slices of the samples, scaled so the loudest is 1. */
export function peaksFrom(samples: ArrayLike<number>, bars = BARS): number[] {
  if (samples.length === 0) return flat(bars);
  const size = samples.length / bars;
  const raw: number[] = [];
  for (let i = 0; i < bars; i++) {
    const from = Math.floor(i * size);
    const to = Math.max(from + 1, Math.floor((i + 1) * size));
    let sum = 0;
    let n = 0;
    for (let j = from; j < to && j < samples.length; j++) {
      const v = samples[j]!;
      sum += v * v;
      n++;
    }
    raw.push(n ? Math.sqrt(sum / n) : 0);
  }
  return normalised(raw);
}

/** Levels sampled while recording (any count) to `bars` bars. */
export function resample(levels: number[], bars = BARS): number[] {
  if (levels.length === 0) return flat(bars);
  const out: number[] = [];
  for (let i = 0; i < bars; i++) {
    const from = Math.floor((i * levels.length) / bars);
    const to = Math.max(from + 1, Math.floor(((i + 1) * levels.length) / bars));
    let max = 0;
    for (let j = from; j < to && j < levels.length; j++) max = Math.max(max, levels[j]!);
    out.push(max);
  }
  return normalised(out);
}

/**
 * Bars that look like speech, the same every time for one note: for audio the
 * browser cannot decode (WebM on older Safari) or before it has loaded.
 */
export function placeholderPeaks(seed: string, bars = BARS): number[] {
  let h = 2166136261;
  for (let i = 0; i < seed.length; i++) h = Math.imul(h ^ seed.charCodeAt(i), 16777619);
  const out: number[] = [];
  for (let i = 0; i < bars; i++) {
    h = Math.imul(h ^ (h >>> 15), 2246822507) >>> 0;
    const envelope = Math.sin((Math.PI * (i + 0.5)) / bars) * 0.6 + 0.4;
    out.push(Math.max(FLOOR, ((h % 1000) / 1000) * envelope));
  }
  return out;
}

/**
 * Scaled so the loudest bar is 1, on a curve closer to how loud it sounds, so
 * quiet speech still shows beside a single loud word.
 */
function normalised(values: number[]): number[] {
  const max = Math.max(...values);
  return values.map((v) => (max > 0 ? Math.max(FLOOR, Math.pow(v / max, 0.6)) : FLOOR));
}

/** How tall a live bar is drawn while recording, 0 to 1, from the level heard. */
export function liveHeight(level: number): number {
  return Math.min(1, Math.max(0.12, Math.sqrt(level) * 2.2));
}

function flat(bars: number): number[] {
  return Array.from({ length: bars }, () => FLOOR);
}

/** How far through the note, 0 to 1, to the bar a pointer is over. */
export function seekFraction(x: number, left: number, width: number): number {
  if (width <= 0) return 0;
  return Math.min(1, Math.max(0, (x - left) / width));
}
