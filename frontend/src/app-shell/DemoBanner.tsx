"use client";

import { useDemo } from "@shared/demo/useDemo";
import { clock } from "@shared/wording";

// Issue #231: while demo mode is on, every role says so (rule 9), with the demo
// clock, so nobody mistakes a rehearsal for real operations. Off, unreadable or
// not signed in: nothing is rendered and nothing else changes.

export default function DemoBanner(): React.JSX.Element | null {
  const view = useDemo();
  if (!view || !view.banner) return null;
  return (
    <div role="status" className="w-full bg-[#fff4d6] px-4 py-1.5 text-center font-go text-[13px] font-medium text-[#5b4300]">
      Demo mode · demo clock {clock(view.now)} · not real operations
    </div>
  );
}
