import { useLayoutEffect, useRef, useState } from "react";

/**
 * The height left between an element's top and the bottom of the window, so a
 * side panel that scrolls inside itself ends on screen with its actions in view,
 * wherever the page above it puts it. Measured again on resize and scroll; null
 * below `minWidth`, where the panel flows with the page.
 */
export function useFitToWindow<T extends HTMLElement>(minWidth = 1024, gap = 16): { ref: React.RefObject<T | null>; maxHeight: number | null } {
  const ref = useRef<T | null>(null);
  const [maxHeight, setMaxHeight] = useState<number | null>(null);
  useLayoutEffect(() => {
    const measure = () => {
      const el = ref.current;
      if (!el || window.innerWidth < minWidth) return setMaxHeight(null);
      const top = Math.max(0, el.getBoundingClientRect().top);
      setMaxHeight(Math.max(320, Math.floor(window.innerHeight - top - gap)));
    };
    measure();
    window.addEventListener("resize", measure);
    window.addEventListener("scroll", measure, true);
    return () => {
      window.removeEventListener("resize", measure);
      window.removeEventListener("scroll", measure, true);
    };
  }, [minWidth, gap]);
  return { ref, maxHeight };
}
