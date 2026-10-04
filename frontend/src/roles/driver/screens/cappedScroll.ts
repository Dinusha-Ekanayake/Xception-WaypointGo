"use client";

import { useEffect, type RefObject } from "react";

/**
 * The fastest the notification feed may move, in px per second. About twelve
 * cards a second: quick enough to cross a long list, slow enough to read what
 * passes. A flick of any strength cannot go faster than this.
 */
export const MAX_SCROLL_SPEED = 1400;

/** How far past the feed's current position a wheel or trackpad may aim, so it stops soon after the hand does. */
const MAX_LEAD = 360;
/** How quickly the feed closes on its target, per second (higher is snappier, never past the cap). */
const FOLLOW = 14;
/** How much of a touch flick's release speed carries on, in seconds of travel. */
const FLING_SECONDS = 0.25;

/**
 * Scrolls `ref` toward a target at no more than `maxSpeed` whatever the input:
 * the mouse wheel, a trackpad flick or a finger drag with its fling. The
 * browser's own scrolling is taken over for those; moving the scroll position
 * from code (restoring where the driver was) is left alone.
 */
export function useCappedScroll(ref: RefObject<HTMLElement | null>, maxSpeed: number = MAX_SCROLL_SPEED): void {
  useEffect(() => {
    const el = ref.current;
    if (!el) return;

    let target = el.scrollTop;
    let position = el.scrollTop;
    let written = el.scrollTop;
    let frame = 0;
    let last = 0;

    const limit = () => Math.max(0, el.scrollHeight - el.clientHeight);
    const clamp = (value: number) => Math.min(limit(), Math.max(0, value));

    const step = (now: number) => {
      const dt = Math.min(0.05, (now - last) / 1000);
      last = now;
      const remaining = target - position;
      const wanted = remaining * Math.min(1, FOLLOW * dt);
      const most = maxSpeed * dt;
      const move = Math.abs(wanted) > most ? Math.sign(wanted) * most : wanted;
      position += move;
      if (Math.abs(target - position) < 0.5) position = target;
      el.scrollTop = position;
      written = el.scrollTop;
      frame = position === target ? 0 : requestAnimationFrame(step);
    };

    const aim = (to: number) => {
      target = clamp(to);
      if (!frame) {
        last = performance.now();
        frame = requestAnimationFrame(step);
      }
    };

    // Moved from elsewhere (scroll restore, a focus, the keyboard): follow it, do not fight it.
    const onScroll = () => {
      if (Math.abs(el.scrollTop - written) < 2) return;
      if (frame) cancelAnimationFrame(frame);
      frame = 0;
      target = position = written = el.scrollTop;
    };

    const onWheel = (event: WheelEvent) => {
      if (event.ctrlKey) return; // a pinch-zoom gesture
      const unit = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? el.clientHeight : 1;
      const delta = event.deltaY * unit;
      // At an edge with the wheel still pushing outward: leave it to the pull-back effect.
      if ((position <= 0 && delta < 0) || (position >= limit() - 1 && delta > 0)) return;
      event.preventDefault();
      aim(Math.min(position + MAX_LEAD, Math.max(position - MAX_LEAD, target + delta)));
    };

    let touchY = 0;
    let touchStart = 0;
    let samples: Array<{ y: number; t: number }> = [];

    const onTouchStart = (event: TouchEvent) => {
      const touch = event.touches[0];
      if (!touch || limit() <= 0) return;
      if (frame) cancelAnimationFrame(frame);
      frame = 0;
      target = position = written = el.scrollTop;
      touchY = touch.clientY;
      touchStart = position;
      samples = [{ y: touch.clientY, t: event.timeStamp }];
    };

    const onTouchMove = (event: TouchEvent) => {
      const touch = event.touches[0];
      if (!touch || limit() <= 0) return;
      if (event.cancelable) event.preventDefault();
      samples.push({ y: touch.clientY, t: event.timeStamp });
      if (samples.length > 6) samples.shift();
      aim(touchStart + (touchY - touch.clientY));
    };

    const onTouchEnd = () => {
      const first = samples[0];
      const lastSample = samples[samples.length - 1];
      samples = [];
      if (!first || !lastSample || lastSample.t - first.t < 10) return;
      const velocity = ((first.y - lastSample.y) / (lastSample.t - first.t)) * 1000;
      const capped = Math.max(-maxSpeed, Math.min(maxSpeed, velocity));
      aim(target + capped * FLING_SECONDS);
    };

    el.addEventListener("scroll", onScroll, { passive: true });
    el.addEventListener("wheel", onWheel, { passive: false });
    el.addEventListener("touchstart", onTouchStart, { passive: true });
    el.addEventListener("touchmove", onTouchMove, { passive: false });
    el.addEventListener("touchend", onTouchEnd, { passive: true });
    el.addEventListener("touchcancel", onTouchEnd, { passive: true });
    return () => {
      if (frame) cancelAnimationFrame(frame);
      el.removeEventListener("scroll", onScroll);
      el.removeEventListener("wheel", onWheel);
      el.removeEventListener("touchstart", onTouchStart);
      el.removeEventListener("touchmove", onTouchMove);
      el.removeEventListener("touchend", onTouchEnd);
      el.removeEventListener("touchcancel", onTouchEnd);
    };
  }, [ref, maxSpeed]);
}
