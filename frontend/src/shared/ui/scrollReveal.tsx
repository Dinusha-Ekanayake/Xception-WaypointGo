"use client";

// The phone lists' scroll, one copy for every screen (driver home feed, the
// route's stops, inboxes): Apple's rubber-band pull at the edges, a fade under
// the header and above the bottom, and rows that shrink and fade as they leave
// (ScrollRevealCard), deeper rows more, like a dial (dialStyle).

import { useEffect, useRef, useState } from "react";
import { dialStyle } from "./dial.ts";

export { dialStyle };

/**
 * Apple UIScrollView rubber-band resistance formula:
 * f(x) = (x * dimension * c) / (dimension + c * x)
 * where c is Apple's standard rubber band coefficient (0.55).
 */
export function appleRubberBand(offset: number, dimension = 600, coefficient = 0.55): number {
  const abs = Math.abs(offset);
  const damped = (abs * dimension * coefficient) / (dimension + coefficient * abs);
  return offset < 0 ? -damped : damped;
}

export function useRubberBandScroll() {
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const [pullY, setPullY] = useState(0);
  const [isPulling, setIsPulling] = useState(false);
  const [topFadeSize, setTopFadeSize] = useState(0);
  const [bottomFadeSize, setBottomFadeSize] = useState(0);
  // Once the voice list moves, the driver card above it folds up so the voices have the screen.
  const [compact, setCompact] = useState(false);

  // Gesture tracking
  const overshootStartY = useRef<number | null>(null);
  const isMouseDown = useRef(false);
  const rawWheelOvershoot = useRef(0);
  const wheelReleaseTimer = useRef<NodeJS.Timeout | null>(null);
  // A trackpad flick keeps sending wheel events after the list reaches an edge.
  // Only a gesture that began at the edge pulls the list; momentum that arrives
  // there must not, or the list is held pushed down for as long as it lasts.
  const lastWheelAt = useRef(0);
  const wheelBeganInside = useRef(false);

  const updateMask = (el: HTMLElement) => {
    const FADE_DISTANCE = 40;
    // Top fade: strictly 0 at the very top (scrollTop === 0), scaling up to 40px as user scrolls down
    const top = Math.min(FADE_DISTANCE, Math.max(0, el.scrollTop));
    const remaining = el.scrollHeight - el.scrollTop - el.clientHeight;
    const bottom = Math.min(FADE_DISTANCE, Math.max(0, remaining));
    setTopFadeSize((prev) => (prev !== top ? top : prev));
    setBottomFadeSize((prev) => (prev !== bottom ? bottom : prev));
    const folded = el.scrollTop > 16;
    setCompact((prev) => (prev !== folded ? folded : prev));
  };

  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    updateMask(el);
    const onScroll = () => updateMask(el);
    el.addEventListener("scroll", onScroll, { passive: true });

    const ro = new ResizeObserver(() => {
      updateMask(el);
    });
    ro.observe(el);
    return () => {
      el.removeEventListener("scroll", onScroll);
      ro.disconnect();
      if (wheelReleaseTimer.current) clearTimeout(wheelReleaseTimer.current);
    };
  }, []);

  const handleScroll = () => {
    // The event's current target is not always this list, so read the ref.
    const el = scrollRef.current;
    if (el) updateMask(el);
  };

  // 1. Touch Gesture Handling
  const handleTouchStart = () => {
    overshootStartY.current = null;
    setIsPulling(false);
  };

  const handleTouchMove = (e: React.TouchEvent<HTMLDivElement>) => {
    const el = scrollRef.current;
    if (!el || !e.touches[0]) return;
    const currentY = e.touches[0].clientY;
    const maxScroll = Math.max(0, el.scrollHeight - el.clientHeight);

    // Pulling down at top
    if (el.scrollTop <= 0) {
      if (overshootStartY.current === null) {
        overshootStartY.current = currentY;
      }
      const rawOvershoot = currentY - overshootStartY.current;
      if (rawOvershoot > 0) {
        const damped = appleRubberBand(rawOvershoot, el.clientHeight, 0.55);
        setIsPulling(true);
        setPullY(damped);
        return;
      } else if (pullY !== 0) {
        setPullY(0);
        setIsPulling(false);
      }
    }
    // Pulling up at bottom
    else if (el.scrollTop >= maxScroll - 1) {
      if (overshootStartY.current === null) {
        overshootStartY.current = currentY;
      }
      const rawOvershoot = currentY - overshootStartY.current;
      if (rawOvershoot < 0) {
        const damped = appleRubberBand(rawOvershoot, el.clientHeight, 0.55);
        setIsPulling(true);
        setPullY(damped);
        return;
      } else if (pullY !== 0) {
        setPullY(0);
        setIsPulling(false);
      }
    }
    // In middle of feed
    else {
      overshootStartY.current = null;
      if (pullY !== 0) {
        setPullY(0);
        setIsPulling(false);
      }
    }
  };

  const handleTouchEnd = () => {
    overshootStartY.current = null;
    setIsPulling(false);
    setPullY(0);
  };

  // 2. Mouse Drag Handling (for desktop testing & preview)
  const handleMouseDown = (e: React.MouseEvent<HTMLDivElement>) => {
    if (e.button !== 0) return;
    isMouseDown.current = true;
    overshootStartY.current = null;
    setIsPulling(false);
  };

  const handleMouseMove = (e: React.MouseEvent<HTMLDivElement>) => {
    if (!isMouseDown.current) return;
    const el = scrollRef.current;
    if (!el) return;
    const currentY = e.clientY;
    const maxScroll = Math.max(0, el.scrollHeight - el.clientHeight);

    if (el.scrollTop <= 0) {
      if (overshootStartY.current === null) {
        overshootStartY.current = currentY;
      }
      const rawOvershoot = currentY - overshootStartY.current;
      if (rawOvershoot > 0) {
        const damped = appleRubberBand(rawOvershoot, el.clientHeight, 0.55);
        setIsPulling(true);
        setPullY(damped);
        return;
      } else if (pullY !== 0) {
        setPullY(0);
        setIsPulling(false);
      }
    } else if (el.scrollTop >= maxScroll - 1) {
      if (overshootStartY.current === null) {
        overshootStartY.current = currentY;
      }
      const rawOvershoot = currentY - overshootStartY.current;
      if (rawOvershoot < 0) {
        const damped = appleRubberBand(rawOvershoot, el.clientHeight, 0.55);
        setIsPulling(true);
        setPullY(damped);
        return;
      } else if (pullY !== 0) {
        setPullY(0);
        setIsPulling(false);
      }
    } else {
      overshootStartY.current = null;
      if (pullY !== 0) {
        setPullY(0);
        setIsPulling(false);
      }
    }
  };

  const handleMouseUp = () => {
    if (!isMouseDown.current) return;
    isMouseDown.current = false;
    overshootStartY.current = null;
    setIsPulling(false);
    setPullY(0);
  };

  // 3. Mouse Wheel / Trackpad Handling
  const handleWheel = (e: React.WheelEvent<HTMLDivElement>) => {
    const el = scrollRef.current;
    if (!el) return;
    const maxScroll = Math.max(0, el.scrollHeight - el.clientHeight);

    if (wheelReleaseTimer.current) {
      clearTimeout(wheelReleaseTimer.current);
    }

    if (e.timeStamp - lastWheelAt.current > 150) {
      const atEdge = (el.scrollTop <= 0 && e.deltaY < 0) || (el.scrollTop >= maxScroll - 1 && e.deltaY > 0);
      wheelBeganInside.current = !atEdge;
    }
    lastWheelAt.current = e.timeStamp;
    if (wheelBeganInside.current) {
      if (rawWheelOvershoot.current !== 0) {
        rawWheelOvershoot.current = 0;
        setIsPulling(false);
        setPullY(0);
      }
      return;
    }

    // Wheeling up at top
    if (el.scrollTop <= 0 && e.deltaY < 0) {
      rawWheelOvershoot.current += -e.deltaY * 0.85;
      const damped = appleRubberBand(rawWheelOvershoot.current, el.clientHeight, 0.45);
      setIsPulling(true);
      setPullY(damped);

      wheelReleaseTimer.current = setTimeout(() => {
        setIsPulling(false);
        setPullY(0);
        rawWheelOvershoot.current = 0;
      }, 70);
    }
    // Wheeling down at bottom
    else if (el.scrollTop >= maxScroll - 1 && e.deltaY > 0) {
      rawWheelOvershoot.current += -e.deltaY * 0.85;
      const damped = appleRubberBand(rawWheelOvershoot.current, el.clientHeight, 0.45);
      setIsPulling(true);
      setPullY(damped);

      wheelReleaseTimer.current = setTimeout(() => {
        setIsPulling(false);
        setPullY(0);
        rawWheelOvershoot.current = 0;
      }, 70);
    }
    // User wheeled back into normal territory while in overscroll
    else if (rawWheelOvershoot.current !== 0) {
      rawWheelOvershoot.current = 0;
      setIsPulling(false);
      setPullY(0);
    }
  };

  const topStop = topFadeSize > 0 ? `transparent 0px, black ${topFadeSize}px` : `black 0px`;
  const bottomStop = bottomFadeSize > 0 ? `black calc(100% - ${bottomFadeSize}px), transparent 100%` : `black 100%`;
  const gradient = `linear-gradient(to bottom, ${topStop}, ${bottomStop})`;

  const maskStyle: React.CSSProperties = {
    WebkitMaskImage: gradient,
    maskImage: gradient,
  };

  return {
    scrollRef,
    pullY,
    isPulling,
    compact,
    maskStyle,
    handlers: {
      onScroll: handleScroll,
      onTouchStart: handleTouchStart,
      onTouchMove: handleTouchMove,
      onTouchEnd: handleTouchEnd,
      onTouchCancel: handleTouchEnd,
      onMouseDown: handleMouseDown,
      onMouseMove: handleMouseMove,
      onMouseUp: handleMouseUp,
      onMouseLeave: handleMouseUp,
      onWheel: handleWheel,
    },
  };
}


export function ScrollRevealCard({
  scrollContainerRef,
  pullY = 0,
  depth = 1,
  towardCard = "center",
  children,
}: {
  scrollContainerRef: React.RefObject<HTMLDivElement | null>;
  pullY?: number;
  /** Rows between this one and the card, counting itself (dialStyle). */
  depth?: number;
  /** Which edge faces the card, so the row shrinks toward it. */
  towardCard?: "top" | "bottom" | "center";
  children: React.ReactNode;
}) {
  const cardRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    const el = cardRef.current;
    const root = scrollContainerRef.current;
    if (!el || !root) return;

    let rafId: number | null = null;

    const update = () => {
      const rootRect = root.getBoundingClientRect();
      const elRect = el.getBoundingClientRect();
      const y = elRect.top - rootRect.top;
      const H = rootRect.height;

      // Top Exit Zone: only as card scrolls up past the top edge (y < 0)
      let topProgress = 1;
      if (y < 0) {
        topProgress = Math.max(0, Math.min(1, (y + 50) / 50));
      }

      // Bottom Exit/Entrance Zone: as item scrolls near the bottom edge
      let bottomProgress = 1;
      if (y > H - 80) {
        bottomProgress = Math.max(0, Math.min(1, (H - y) / 80));
      }

      // The scroll edges' own fade, on top of where the row sits on the dial.
      const edge = topProgress < 1 ? topProgress : bottomProgress;
      const dial = dialStyle(depth);
      const scale = dial.scale * (0.90 + 0.10 * edge);
      const opacity = dial.opacity * (0.50 + 0.50 * edge);
      el.style.opacity = opacity.toFixed(3);
      el.style.transform = `scale3d(${scale.toFixed(3)}, ${scale.toFixed(3)}, 1)`;
      el.style.transformOrigin = topProgress < 1 ? "center top" : bottomProgress < 1 ? "center bottom" : `center ${towardCard}`;
    };

    const onScroll = () => {
      if (rafId) cancelAnimationFrame(rafId);
      rafId = requestAnimationFrame(update);
    };

    update();
    root.addEventListener("scroll", onScroll, { passive: true });
    window.addEventListener("resize", onScroll, { passive: true });

    return () => {
      root.removeEventListener("scroll", onScroll);
      window.removeEventListener("resize", onScroll);
      if (rafId) cancelAnimationFrame(rafId);
    };
  }, [scrollContainerRef, pullY, depth, towardCard]);

  return (
    <div
      ref={cardRef}
      className="w-full will-change-[transform,opacity]"
    >
      {children}
    </div>
  );
}
