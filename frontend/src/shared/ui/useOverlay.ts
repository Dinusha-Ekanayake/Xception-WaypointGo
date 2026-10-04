"use client";

import { useCallback, useEffect, useRef, useState, type RefObject } from "react";

/**
 * What every sheet, dialog and drawer does the same way: focus moves in on
 * open, stays inside, returns where it was on close; Escape closes; the page
 * behind does not scroll. Closing plays a short exit first (150 ms, none under
 * reduced motion); whatever the overlay sent has already been sent.
 */

const EXIT_MS = 150;
let locks = 0;

function lockPage(): () => void {
  const root = document.documentElement;
  if (locks === 0) root.style.overflow = "hidden";
  locks += 1;
  return () => {
    locks -= 1;
    if (locks === 0) root.style.overflow = "";
  };
}

export function reducedMotion(): boolean {
  return typeof window !== "undefined" && window.matchMedia?.("(prefers-reduced-motion: reduce)").matches === true;
}

/** Escape closes, while `onClose` is given; for a panel that keeps its own focus handling. */
export function useEscape(onClose: (() => void) | undefined): void {
  const close = useRef(onClose);
  close.current = onClose;
  const open = onClose !== undefined;
  useEffect(() => {
    if (!open) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") close.current?.();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open]);
}

const FOCUSABLE = "button, [href], input, select, textarea, [tabindex]:not([tabindex='-1'])";

export function useOverlay(
  panel: RefObject<HTMLElement | null>,
  onClose: (() => void) | undefined,
): { closing: boolean; requestClose: () => void } {
  const close = useRef(onClose);
  close.current = onClose;
  const [closing, setClosing] = useState(false);
  const closingRef = useRef(false);
  // Read while rendering, before any autoFocus inside the panel moves focus.
  const [opener] = useState(() => (typeof document === "undefined" ? null : (document.activeElement as HTMLElement | null)));

  const requestClose = useCallback(() => {
    if (!close.current || closingRef.current) return;
    if (reducedMotion()) {
      close.current();
      return;
    }
    closingRef.current = true;
    setClosing(true);
    window.setTimeout(() => close.current?.(), EXIT_MS);
  }, []);

  useEffect(() => {
    const focusable = () =>
      Array.from(panel.current?.querySelectorAll<HTMLElement>(FOCUSABLE) ?? []).filter((el) => !el.hasAttribute("disabled"));
    // An input that focused itself (autoFocus) keeps it.
    // Without preventScroll, focusing a panel that is still sliding in from below
    // scrolls the frame that clips it, and everything behind flies up and back.
    if (!panel.current?.contains(document.activeElement)) (focusable()[0] ?? panel.current)?.focus({ preventScroll: true });
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape" && close.current) {
        event.preventDefault();
        requestClose();
        return;
      }
      if (event.key !== "Tab") return;
      const items = focusable();
      if (items.length === 0) return;
      const first = items[0]!;
      const last = items[items.length - 1]!;
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    const unlock = lockPage();
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("keydown", onKey);
      unlock();
      if (opener?.isConnected) opener.focus({ preventScroll: true });
    };
    // Mount and unmount only: the panel ref and requestClose are stable.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return { closing, requestClose };
}
