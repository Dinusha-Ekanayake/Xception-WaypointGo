"use client";

import { useEffect, useRef, type RefObject } from "react";
import { reducedMotion } from "./useOverlay.ts";

/**
 * Swipe a phone's bottom sheet down to close it, as on iOS. Pulling up past the
 * top resists instead of moving freely (a rubber band), and a short drag springs
 * back. Touch only, below 768 px, and only from the top of the sheet's scroll,
 * so the content still scrolls normally.
 */

const LIMIT = 160;
const DISMISS_PX = 120;
const DISMISS_SPEED = 0.6; // px per ms
const SPRING = "transform 200ms cubic-bezier(0.2, 0.8, 0.2, 1)";

/** How far the sheet moves for a finger offset: freely down, with resistance up. */
export function rubberBand(dy: number): number {
  if (dy >= 0) return dy;
  return -(1 - 1 / ((-dy * 0.55) / LIMIT + 1)) * LIMIT;
}

/** Whether a release at this offset and downward speed closes the sheet. */
export function dismisses(dy: number, speed: number): boolean {
  return dy > DISMISS_PX || (dy > 0 && speed > DISMISS_SPEED);
}

const SKIP = "input, textarea, select, canvas, [data-no-drag]";

/** The scrollers between the touched element and the sheet, the sheet included. */
function scrollers(from: Element, sheet: HTMLElement): HTMLElement[] {
  const out: HTMLElement[] = [];
  for (let n: Element | null = from; n && sheet.contains(n); n = n.parentElement) {
    const h = n as HTMLElement;
    if (h === sheet || h.scrollHeight > h.clientHeight + 1) out.push(h);
    if (h === sheet) break;
  }
  return out;
}

export function useSheetDrag(panel: RefObject<HTMLElement | null>, onDismiss: () => void, enabled = true): void {
  const dismiss = useRef(onDismiss);
  dismiss.current = onDismiss;

  useEffect(() => {
    const el = panel.current;
    if (!el || !enabled || !window.matchMedia?.("(max-width: 767px)").matches) return;
    let startX = 0;
    let startY = 0;
    let lastY = 0;
    let lastT = 0;
    let speed = 0;
    let mode: "idle" | "pending" | "drag" | "native" = "idle";
    let under: HTMLElement[] = [];

    const set = (y: number, transition = "none") => {
      el.style.transition = transition;
      el.style.transform = y === 0 ? "" : `translateY(${y}px)`;
    };
    const onStart = (e: TouchEvent) => {
      if (e.touches.length !== 1 || (e.target as Element).closest(SKIP)) {
        mode = "native";
        return;
      }
      under = scrollers(e.target as Element, el);
      startX = e.touches[0]!.clientX;
      startY = lastY = e.touches[0]!.clientY;
      lastT = e.timeStamp;
      speed = 0;
      mode = "pending";
    };
    const onMove = (e: TouchEvent) => {
      if (mode === "native" || mode === "idle") return;
      const y = e.touches[0]!.clientY;
      const dy = y - startY;
      if (mode === "pending") {
        if (Math.abs(e.touches[0]!.clientX - startX) > Math.abs(dy)) {
          mode = "native";
          return;
        }
        const atTop = under.every((n) => n.scrollTop <= 0);
        const fits = under.every((n) => n.scrollHeight <= n.clientHeight + 1);
        if ((dy > 0 && atTop) || (dy < 0 && fits)) mode = "drag";
        else if (Math.abs(dy) > 4) mode = "native";
        else return;
      }
      e.preventDefault();
      const dt = Math.max(1, e.timeStamp - lastT);
      speed = (y - lastY) / dt;
      lastY = y;
      lastT = e.timeStamp;
      set(rubberBand(dy));
    };
    const onEnd = () => {
      if (mode !== "drag") {
        mode = "idle";
        return;
      }
      mode = "idle";
      const dy = lastY - startY;
      if (dismisses(dy, speed)) {
        if (reducedMotion()) dismiss.current();
        else {
          set(el.offsetHeight, "transform 150ms cubic-bezier(0.4, 0, 1, 1)");
          window.setTimeout(() => dismiss.current(), 150);
        }
      } else set(0, reducedMotion() ? "none" : SPRING);
    };

    el.addEventListener("touchstart", onStart, { passive: true });
    el.addEventListener("touchmove", onMove, { passive: false });
    el.addEventListener("touchend", onEnd);
    el.addEventListener("touchcancel", onEnd);
    return () => {
      el.removeEventListener("touchstart", onStart);
      el.removeEventListener("touchmove", onMove);
      el.removeEventListener("touchend", onEnd);
      el.removeEventListener("touchcancel", onEnd);
    };
  }, [panel, enabled]);
}
