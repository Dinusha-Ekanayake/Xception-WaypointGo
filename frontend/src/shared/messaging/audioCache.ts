"use client";

import { useEffect, useState } from "react";
import { getSnapshot, putSnapshot, removeSnapshotKeys, snapshotTimes } from "../offline/store.ts";
import { request } from "../api/client.ts";
import type { MessagePage, MessageView, ThreadView } from "../domain/messaging.ts";
import { speechUrl, voiceUrl } from "./thread.ts";

// The audio of a driver's messages kept on the phone (R-MSG-11): voice notes and
// the server's spoken copy of typed messages, fetched while there is signal so
// Listen and Play messages work with none. Kept in the account's own store
// (cleared at sign-out, swept after 7 days by retention.ts), at most
// MAX_AUDIO clips, the oldest dropped first.

export const MAX_AUDIO = 60;
const PREFIX = "audio:";
/** A spoken copy not ready yet is asked for again after this long, not on every look. */
const RETRY_MS = 2 * 60_000;
const missed = new Map<string, number>();

export { speechUrl };

/** Which audio a message has: its voice note, or the server's spoken copy of its text. */
export function audioUrlOf(m: Pick<MessageView, "threadId" | "messageId" | "voiceNoteId" | "body">): string | null {
  if (m.voiceNoteId) return voiceUrl(m.threadId, m.voiceNoteId);
  return m.body.trim() ? speechUrl(m.threadId, m.messageId) : null;
}

/** The URL to play: a kept copy when there is one, else the network's. */
export async function playableUrl(accountId: string | undefined, url: string): Promise<string> {
  if (!accountId) return url;
  try {
    const kept = await getSnapshot<Blob>(accountId, PREFIX + url);
    if (kept?.value instanceof Blob) return URL.createObjectURL(kept.value);
  } catch {
    // No store on this device: the network's copy.
  }
  return url;
}

/** playableUrl for a component: the network's URL at once, the kept copy once it is found. */
export function usePlayableUrl(accountId: string | undefined, url: string): string {
  const [src, setSrc] = useState(url);
  useEffect(() => {
    let live = true;
    let made: string | null = null;
    setSrc(url);
    void playableUrl(accountId, url).then((found) => {
      if (found === url) return;
      if (live) {
        made = found;
        setSrc(found);
      } else URL.revokeObjectURL(found);
    });
    return () => {
      live = false;
      if (made) URL.revokeObjectURL(made);
    };
  }, [accountId, url]);
  return src;
}

/** Keeps one clip; true when it is on the phone now. A spoken copy not ready yet is not asked for again at once. */
export async function keepAudio(accountId: string, url: string, now = Date.now()): Promise<boolean> {
  try {
    if ((await getSnapshot<Blob>(accountId, PREFIX + url))?.value instanceof Blob) return true;
    if ((missed.get(url) ?? 0) > now) return false;
    const answer = await fetch(url, { credentials: "same-origin" });
    const type = answer.headers.get("content-type") ?? "";
    if (!answer.ok || !type.startsWith("audio/")) {
      missed.set(url, now + RETRY_MS);
      return false;
    }
    await putSnapshot(accountId, PREFIX + url, await answer.blob());
    return true;
  } catch {
    return false;
  }
}

/** Keeps the audio of the newest messages not written by this person, then drops the oldest clips past the cap. */
export async function keepThreadAudio(accountId: string, messages: MessageView[]): Promise<number> {
  const wanted = messages.filter((m) => !m.mine).slice(-30).map(audioUrlOf).filter((u): u is string => u !== null);
  let kept = 0;
  for (const url of wanted) {
    if (await keepAudio(accountId, url)) kept++;
  }
  try {
    const clips = (await snapshotTimes(accountId)).filter((s) => s.key.startsWith(PREFIX)).sort((a, b) => b.savedAt.localeCompare(a.savedAt));
    if (clips.length > MAX_AUDIO) await removeSnapshotKeys(accountId, clips.slice(MAX_AUDIO).map((c) => c.key));
  } catch {
    // Nothing to prune on a device with no store.
  }
  return kept;
}

/** Without a new message, the trip's audio is looked at again after this long. */
export const KEEP_EVERY_MS = 5 * 60_000;

/**
 * The driver's phone keeps the trip's audio whether or not Messages is open: on
 * the way in, whenever `news` changes (a new message notification), and every
 * KEEP_EVERY_MS, only while online. Two reads at most each time.
 */
export function useKeptTripAudio(accountId: string, tripId: string | null, online: boolean, news: string): void {
  useEffect(() => {
    if (!tripId || !online) return;
    let live = true;
    const keep = async () => {
      try {
        const thread = await request<ThreadView>(`/api/threads/by-subject?type=trip&id=${encodeURIComponent(tripId)}`);
        if (!live) return;
        const page = await request<MessagePage>(`/api/threads/${encodeURIComponent(thread.threadId)}/messages?limit=30`);
        if (live) await keepThreadAudio(accountId, page.items);
      } catch {
        // No thread yet, or the signal dropped: the next look tries again.
      }
    };
    void keep();
    const timer = window.setInterval(() => void keep(), KEEP_EVERY_MS);
    return () => {
      live = false;
      window.clearInterval(timer);
    };
  }, [accountId, tripId, online, news]);
}
