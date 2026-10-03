"use client";

import { useSyncExternalStore } from "react";

// Tablet and wider (Tailwind's md, 768px): the width Figma 07, 09 and 10 start
// from. Read from the media query, so a dock tablet turned from landscape to
// portrait and back keeps the right layout without a reload.
const QUERY = "(min-width: 768px)";

function subscribe(onChange: () => void): () => void {
  const media = window.matchMedia(QUERY);
  media.addEventListener("change", onChange);
  return () => media.removeEventListener("change", onChange);
}

export function useWide(): boolean {
  return useSyncExternalStore(subscribe, () => window.matchMedia(QUERY).matches, () => false);
}
