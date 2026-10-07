import type { Motion } from "./motion.ts";

// When the driving screen is on (Figma "Driving mode", R-EXE-28): the next stop
// in large type and nothing to tap. Pure, so the rule is tested without a
// browser. It comes on by itself once the vehicle moves and goes off by itself
// after the vehicle has stood still a while, long enough that a traffic light
// or a jam does not flicker it. Sliding out is always possible, since stopping
// is not always detected; it then stays out until the vehicle has stopped and
// moves again. With no location (motion unknown) nothing changes by itself.

/** Standing still this long ends a driving screen that came on by itself. */
export const STOPPED_TO_EXIT_MS = 90_000;

export type DrivingState = {
  on: boolean;
  /** It came on by itself, so it may also go off by itself. */
  auto: boolean;
  /** When the vehicle was first seen standing still, or null while it moves. */
  stoppedSince: number | null;
  /** The driver slid out while moving: stay out until a stop, then a new movement. */
  heldOff: boolean;
};

export const OFF: DrivingState = { on: false, auto: false, stoppedSince: null, heldOff: false };

/** What the screen should be after a motion reading, for a trip under way. */
export function onMotion(state: DrivingState, motion: Motion, now: number, tripUnderWay: boolean): DrivingState {
  if (!tripUnderWay) return state.on && state.auto ? OFF : state;
  if (motion === "moving") {
    if (state.on) return { ...state, stoppedSince: null };
    return state.heldOff ? { ...state, stoppedSince: null } : { on: true, auto: true, stoppedSince: null, heldOff: false };
  }
  if (motion === "stopped") {
    const since = state.stoppedSince ?? now;
    if (state.on && state.auto && now - since >= STOPPED_TO_EXIT_MS) return OFF;
    // A stop clears a slide-out: the next movement may turn the screen on again.
    return { ...state, stoppedSince: since, heldOff: false };
  }
  return state;
}

/** The driver opened it by hand: it stays until the slider closes it. */
export function openByHand(state: DrivingState): DrivingState {
  return { ...state, on: true, auto: false, heldOff: false };
}

/** The driver slid out. While moving it stays out until a stop and a new movement. */
export function slideOut(state: DrivingState, motion: Motion): DrivingState {
  return { on: false, auto: false, stoppedSince: state.stoppedSince, heldOff: motion === "moving" };
}

/** Message chimes while driving: at most one in this long, so a busy thread is not a stream of sounds. */
export const CHIME_GAP_MS = 5 * 60_000;

/** Whether a new message may chime now; a changed plan always does. */
export function mayChime(lastChimeAt: number | null, now: number, urgent: boolean): boolean {
  return urgent || lastChimeAt === null || now - lastChimeAt >= CHIME_GAP_MS;
}

/** An unread message chimes again this often (R-NOT-18), at most REMIND_TIMES times. */
export const REMIND_EVERY_MS = 5 * 60_000;
export const REMIND_TIMES = 3;

/**
 * Whether the oldest unread message should chime again: the phone first heard
 * it at `heardAt` and has reminded `reminded` times. After the last reminder
 * the dispatcher has been told (the server's unseen-message job) and the phone
 * stays quiet.
 */
export function remindDue(heardAt: number, reminded: number, now: number): boolean {
  return reminded < REMIND_TIMES && now - heardAt >= REMIND_EVERY_MS * (reminded + 1);
}
