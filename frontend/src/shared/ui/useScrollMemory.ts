"use client";

import { useLayoutEffect, useRef, type RefObject } from "react";
import { readKept, writeKept } from "./usePersistentState.ts";

/**
 * Where a screen was scrolled, given back when the person returns to it, as a
 * native app does; a screen not seen before opens at the top. Called once in a
 * role's frame with a key per screen. The scroller is the window, or an inner
 * element named by a ref where the layout scrolls one (the dispatcher's main
 * column on desktops); both are watched, whichever is in use counts. A screen
 * draws its data a moment after it opens, so the restore retries for up to
 * about a second until the content is tall enough to hold the offset. A null
 * key is a one-off screen (a form, a detail): it opens at the top, unremembered.
 */

const TRIES = 60;

function scrollTo(el: HTMLElement | null, y: number, page: boolean): void {
  if (el) el.scrollTop = y;
  if (page) window.scrollTo(0, y);
}

function current(el: HTMLElement | null, page: boolean): number {
  return el && (el.scrollTop > 0 || !page) ? el.scrollTop : window.scrollY;
}

function room(el: HTMLElement | null, page: boolean): number {
  const inner = el ? el.scrollHeight - el.clientHeight : 0;
  const outer = page ? document.documentElement.scrollHeight - window.innerHeight : 0;
  return Math.max(inner, outer);
}

/**
 * `page: false` for a list inside a screen that scrolls on its own (a table):
 * only that element is read and moved, never the window, which the role's
 * frame looks after.
 */
export function useScrollMemory(key: string | null, scroller?: RefObject<HTMLElement | null>, { page = true }: { page?: boolean } = {}): void {
  const settled = useRef(false);

  // A layout effect: the last screen's listener is gone before the new screen
  // paints, so a scroll the browser makes while clamping is never saved for it.
  useLayoutEffect(() => {
    const el = scroller?.current ?? null;
    if (key === null) {
      scrollTo(el, 0, page);
      return;
    }
    const saved = readKept(`scroll:${key}`, 0);
    settled.current = false;
    let frame = 0;
    let tries = 0;

    const restore = () => {
      if (saved <= 0 || room(el, page) >= saved || tries >= TRIES) {
        scrollTo(el, Math.min(saved, Math.max(0, room(el, page))), page);
        settled.current = true;
        return;
      }
      tries += 1;
      frame = requestAnimationFrame(restore);
    };
    scrollTo(el, 0, page);
    restore();

    let saveFrame = 0;
    const onScroll = () => {
      if (!settled.current) return;
      cancelAnimationFrame(saveFrame);
      saveFrame = requestAnimationFrame(() => writeKept(`scroll:${key}`, current(el, page)));
    };
    // A touch or wheel before the restore lands means the person has taken over.
    const takeOver = () => {
      cancelAnimationFrame(frame);
      settled.current = true;
    };
    if (page) window.addEventListener("scroll", onScroll, { passive: true });
    el?.addEventListener("scroll", onScroll, { passive: true });
    window.addEventListener("wheel", takeOver, { passive: true, once: true });
    window.addEventListener("touchstart", takeOver, { passive: true, once: true });
    return () => {
      cancelAnimationFrame(frame);
      cancelAnimationFrame(saveFrame);
      window.removeEventListener("scroll", onScroll);
      el?.removeEventListener("scroll", onScroll);
      window.removeEventListener("wheel", takeOver);
      window.removeEventListener("touchstart", takeOver);
    };
  }, [key, scroller, page]);
}
