"use client";

import { useCallback, useEffect, useRef, useState } from "react";

// Undo for something removed on this device only (a signature, a voice note):
// what was removed is held for a few seconds and can be put back. Never for a
// server command, which is not delayed or faked to look undoable.

export const UNDO_MS = 5_000;

export function useUndo<T>(): {
  /** What was removed, while it can still be put back. */
  held: T | null;
  hold: (value: T) => void;
  /** Hands back what was held and forgets it. */
  take: () => T | null;
  drop: () => void;
} {
  const [held, setHeld] = useState<T | null>(null);
  const timer = useRef<number | null>(null);

  const stop = useCallback(() => {
    if (timer.current !== null) window.clearTimeout(timer.current);
    timer.current = null;
  }, []);

  useEffect(() => stop, [stop]);

  const drop = useCallback(() => {
    stop();
    setHeld(null);
  }, [stop]);

  const hold = useCallback(
    (value: T) => {
      stop();
      setHeld(() => value);
      timer.current = window.setTimeout(() => {
        timer.current = null;
        setHeld(null);
      }, UNDO_MS);
    },
    [stop],
  );

  const take = () => {
    const value = held;
    drop();
    return value;
  };

  return { held, hold, take, drop };
}
