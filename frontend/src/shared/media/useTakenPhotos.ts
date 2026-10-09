"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { MAX_PHOTO_BYTES, QUALITY_HINT, checkPhoto, encodePhoto } from "./photo.ts";

// Roadmap FF-01: the one way a role's report sheet takes photos. Every photo is
// made small (M-02), checked for blur and darkness, and given a preview address
// that is released when the photo is removed or the sheet closes. The loader's
// shortfall sheet and the store's report and receive screens each keep their own
// look and their own command; this is what they had each written out.

/** A photo ready to keep or send: `hint` says why it may need retaking (it can still be kept). */
export type TakenPhoto = { id: string; blob: Blob; url: string; hint: string | null };

/** One photo from the camera: encoded, judged, and given a preview URL the caller must release. */
export async function preparePhoto(file: Blob, maxBytes = MAX_PHOTO_BYTES): Promise<TakenPhoto> {
  const { blob } = await encodePhoto(file, maxBytes);
  const quality = await checkPhoto(blob);
  return { id: globalThis.crypto.randomUUID(), blob, url: URL.createObjectURL(blob), hint: quality === "ok" ? null : QUALITY_HINT[quality] };
}

/**
 * The photos of one report. `limit` 1 replaces the photo on each take (the
 * loader's one photo of a line); more keeps adding. Preview URLs are released
 * on removal and when the sheet unmounts.
 */
export function useTakenPhotos(limit = Number.POSITIVE_INFINITY): {
  photos: TakenPhoto[];
  error: string | null;
  add: (file: File | undefined) => Promise<TakenPhoto | null>;
  remove: (id: string) => void;
} {
  const [photos, setPhotos] = useState<TakenPhoto[]>([]);
  const [error, setError] = useState<string | null>(null);
  const held = useRef<TakenPhoto[]>([]);
  useEffect(() => () => held.current.forEach((p) => URL.revokeObjectURL(p.url)), []);

  const add = useCallback(
    async (file: File | undefined) => {
      if (!file) return null;
      setError(null);
      try {
        const photo = await preparePhoto(file);
        setPhotos((all) => {
          const kept = limit <= 1 ? [] : all;
          if (limit <= 1) all.forEach((p) => URL.revokeObjectURL(p.url));
          const next = [...kept, photo];
          held.current = next;
          return next;
        });
        return photo;
      } catch (failure) {
        setError(failure instanceof Error ? failure.message : "The photo could not be used.");
        return null;
      }
    },
    [limit],
  );

  const remove = useCallback((id: string) => {
    setPhotos((all) => {
      all.filter((p) => p.id === id).forEach((p) => URL.revokeObjectURL(p.url));
      const next = all.filter((p) => p.id !== id);
      held.current = next;
      return next;
    });
  }, []);

  return { photos, error, add, remove };
}
