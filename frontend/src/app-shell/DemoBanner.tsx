"use client";

import { useEffect, useRef } from "react";
import { useDemo } from "@shared/demo/useDemo";
import { clock } from "@shared/wording";

// Issue #231: while demo mode is on, every role says so (rule 9), with the demo
// clock, so nobody mistakes a rehearsal for real operations. Off, unreadable or
// not signed in: nothing is rendered and nothing else changes.

export default function DemoBanner(): React.JSX.Element | null {
  const view = useDemo();
  const bar = useRef<HTMLDivElement | null>(null);
  const shown = Boolean(view?.banner);
  // Its height as --demo-banner-h, so a role that fills the screen (the driver)
  // fills what is left under it instead of running off the bottom.
  useEffect(() => {
    const root = document.documentElement;
    const element = bar.current;
    if (!shown || !element) {
      root.style.removeProperty("--demo-banner-h");
      return;
    }
    const measure = () => root.style.setProperty("--demo-banner-h", `${element.offsetHeight}px`);
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    return () => {
      observer.disconnect();
      root.style.removeProperty("--demo-banner-h");
    };
  }, [shown]);
  if (!view || !view.banner) return null;
  return (
    <div ref={bar} role="status" className="w-full bg-[#fff4d6] px-4 py-1.5 text-center font-go text-[13px] font-medium text-[#5b4300]">
      Demo mode · demo clock {clock(view.now)} · not real operations
    </div>
  );
}
