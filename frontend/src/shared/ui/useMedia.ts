"use client";

import { useCallback, useSyncExternalStore } from "react";

// Whether a media query matches, kept current as the device turns: a tablet
// rotated from landscape to portrait changes layout without a reload (issue
// #201). False on the server, so the first paint is the phone layout.

export function useMedia(query: string): boolean {
  const subscribe = useCallback(
    (onChange: () => void) => {
      const media = window.matchMedia(query);
      media.addEventListener("change", onChange);
      return () => media.removeEventListener("change", onChange);
    },
    [query],
  );
  return useSyncExternalStore(
    subscribe,
    () => window.matchMedia(query).matches,
    () => false,
  );
}
