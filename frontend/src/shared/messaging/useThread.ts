"use client";

import { useCallback, useEffect, useState } from "react";
import { request } from "@shared/api/client";
import { newCommand, type Command } from "@shared/api/commands";
import { parseProblem, ApiError } from "@shared/api/problem";
import { useResource, type Resource } from "@shared/api/useResource";
import { pendingEntries, QUEUED_EVENT, readThrough } from "@shared/offline";
import {
  MessageCommandKind,
  type MemberView,
  type MessagePage,
  type MessageView,
  type PostMessagePayload,
  type ThreadView,
} from "@shared/domain/types";
import { chronological } from "./thread.ts";

// Reading and writing a trip's thread (issue #136). Reads poll every ten
// seconds while the screen is open. Writing is in senders.ts.

const POLL_MS = 10_000;

const q = encodeURIComponent;

/**
 * A read, kept on the device for a role that works with no signal: with an
 * account, the last answer is kept and served in an outage (the page says how
 * old it is); without one, the read is live only.
 */
function kept<T>(accountId: string | undefined, key: string, read: () => Promise<T>): Promise<T> {
  return accountId ? readThrough(accountId, key, read) : read();
}

/** The thread of a trip, or null before its plan is published (404). */
export function useTripThread(tripId: string | null, accountId?: string): Resource<ThreadView | null> {
  return useResource(
    tripId === null
      ? null
      : async (signal: AbortSignal) => {
          try {
            return await kept(accountId, `thread-of:${tripId}`, () =>
              request<ThreadView>(`/api/threads/by-subject?type=trip&id=${q(tripId)}`, { signal }),
            );
          } catch (error) {
            if (error instanceof ApiError && error.status === 404) return null;
            throw error;
          }
        },
    `thread-of|${tripId ?? ""}`,
  );
}

export type ThreadState = {
  thread: Resource<ThreadView>;
  members: Resource<MemberView[]>;
  /** Oldest first. */
  messages: MessageView[];
  hasOlder: boolean;
  loadOlder: () => void;
  refresh: () => void;
  loadedAt: Date | null;
  error: Error | null;
};

export function useThread(threadId: string | null, accountId?: string): ThreadState {
  const thread = useResource(
    threadId === null
      ? null
      : (signal: AbortSignal) => kept(accountId, `thread:${threadId}`, () => request<ThreadView>(`/api/threads/${q(threadId)}`, { signal })),
    `thread|${threadId ?? ""}`,
  );
  const members = useResource(
    threadId === null
      ? null
      : (signal: AbortSignal) =>
          kept(accountId, `thread-members:${threadId}`, () => request<MemberView[]>(`/api/threads/${q(threadId)}/members`, { signal })),
    `thread-members|${threadId ?? ""}`,
  );
  const latest = useResource(
    threadId === null
      ? null
      : (signal: AbortSignal) =>
          kept(accountId, `thread-messages:${threadId}`, () =>
            request<MessagePage>(`/api/threads/${q(threadId)}/messages?limit=50`, { signal }),
          ),
    `thread-messages|${threadId ?? ""}`,
    POLL_MS,
  );
  const [older, setOlder] = useState<MessageView[][]>([]);
  const [cursor, setCursor] = useState<string | null>(null);

  useEffect(() => {
    setOlder([]);
    setCursor(null);
  }, [threadId]);
  useEffect(() => {
    if (older.length === 0) setCursor(latest.data?.nextCursor ?? null);
  }, [latest.data, older.length]);

  const loadOlder = useCallback(() => {
    if (!threadId || !cursor) return;
    void request<MessagePage>(`/api/threads/${q(threadId)}/messages?limit=50&cursor=${q(cursor)}`).then((page) => {
      setOlder((pages) => [...pages, page.items]);
      setCursor(page.nextCursor);
    });
  }, [threadId, cursor]);

  return {
    thread,
    members,
    messages: chronological([latest.data?.items ?? [], ...older]),
    hasOlder: cursor !== null,
    loadOlder,
    refresh: latest.refresh,
    loadedAt: latest.loadedAt,
    error: latest.error ?? thread.error,
  };
}

export function postCommand(payload: PostMessagePayload): Command<PostMessagePayload> {
  return newCommand(MessageCommandKind.post, payload);
}

/** The phone's id for a message or a voice note: what makes a resend the same one. */
export function newId(): string {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
  const b = new Uint8Array(16);
  globalThis.crypto.getRandomValues(b);
  b[6] = (b[6]! & 0x0f) | 0x40;
  b[8] = (b[8]! & 0x3f) | 0x80;
  const h = Array.from(b, (x) => x.toString(16).padStart(2, "0")).join("");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

/** Uploads a voice note before the message that carries it. Needs a connection (R-MSG-06). */
export async function uploadVoice(threadId: string, voiceNoteId: string, audio: Blob, durationMs: number): Promise<void> {
  const type = (audio.type || "audio/webm").split(";")[0]!;
  const response = await fetch(voicePath(threadId, voiceNoteId, durationMs), {
    method: "PUT",
    headers: { "content-type": type },
    body: audio,
    credentials: "same-origin",
  });
  if (!response.ok) {
    const text = await response.text();
    let body: unknown = null;
    try {
      body = text ? JSON.parse(text) : null;
    } catch {
      body = null;
    }
    throw new ApiError(parseProblem(response.status, body));
  }
}

/** Where a voice note's audio is uploaded, with its length. */
export function voicePath(threadId: string, voiceNoteId: string, durationMs: number): string {
  return `/api/threads/${q(threadId)}/voice/${q(voiceNoteId)}?durationMs=${Math.round(durationMs)}`;
}

export function voiceUrl(threadId: string, voiceNoteId: string): string {
  return `/api/threads/${q(threadId)}/voice/${q(voiceNoteId)}`;
}

/** A message still on this device, waiting for a connection. */
export type Waiting = { commandId: string; body: string; to: PostMessagePayload["to"]; outletId: string | null; report: boolean; voice: boolean; at: string };

/** This thread's messages kept on the device and not yet sent, oldest first. */
export function useWaiting(threadId: string | null, accountId: string | undefined, tick: unknown): Waiting[] {
  const [waiting, setWaiting] = useState<Waiting[]>([]);
  useEffect(() => {
    if (!threadId || !accountId) return;
    let live = true;
    const read = () =>
      void pendingEntries(accountId).then((entries) => {
        if (!live) return;
        setWaiting(
          entries
            .filter((e) => e.kind === MessageCommandKind.post && !e.needsReview)
            .map((e) => e.payload as Command<PostMessagePayload>)
            .filter((c) => c.payload.threadId === threadId)
            .map((c) => ({
              commandId: c.commandId,
              body: c.payload.body,
              to: c.payload.to,
              outletId: c.payload.outletId ?? null,
              report: Boolean(c.payload.report),
              voice: Boolean(c.payload.voiceNoteId),
              at: c.clientRecordedAt,
            })),
        );
      });
    read();
    window.addEventListener(QUEUED_EVENT, read);
    window.addEventListener("online", read);
    const timer = window.setInterval(read, 2_000);
    return () => {
      live = false;
      window.removeEventListener(QUEUED_EVENT, read);
      window.removeEventListener("online", read);
      window.clearInterval(timer);
    };
  }, [threadId, accountId, tick]);
  return waiting;
}
