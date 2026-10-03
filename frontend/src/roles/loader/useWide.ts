"use client";

import { useMedia } from "@shared/ui";

// Tablet and wider (Tailwind's md, 768px): the width Figma 07, 09 and 10 start
// from. Read from the media query, so a dock tablet turned from landscape to
// portrait and back keeps the right layout without a reload.
export function useWide(): boolean {
  return useMedia("(min-width: 768px)");
}
