"use client";

import { useCallback, useEffect, useState } from "react";
import { request } from "@shared/api/client";
import { newCommand, send, type Command } from "@shared/api/commands";
import { parseProblem, ApiError } from "@shared/api/problem";
import { useResource, type Resource } from "@shared/api/useResource";
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
// seconds while the screen is open; a role whose writes must survive no signal
// passes its own durable sender, everyone else posts straight to the server.

const POLL_MS = 10_000;

const q = encodeURIComponent;

/** The thread of a trip, or null before its plan is published (404). */
export function useTripThread(tripId: string | null): Resource<ThreadView | null> {
  return useResource(
    tripId === null
      ? null
      : async (signal: AbortSignal) => {
          try {
            return await request<ThreadView>(`/api/threads/by-subject?type=trip&id=${q(tripId)}`, { signal });
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

export function useThread(threadId: string | null): ThreadState {
  const thread = useResource(
    threadId === null ? null : (signal: AbortSignal) => request<ThreadView>(`/api/threads/${q(threadId)}`, { signal }),
    `thread|${threadId ?? ""}`,
  );
  const members = useResource(
    threadId === null ? null : (signal: AbortSignal) => request<MemberView[]>(`/api/threads/${q(threadId)}/members`, { signal }),
    `thread-members|${threadId ?? ""}`,
  );
  const latest = useResource(
    threadId === null ? null : (signal: AbortSignal) => request<MessagePage>(`/api/threads/${q(threadId)}/messages?limit=50`, { signal }),
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

/** Sends a post: straight to the server by default, or through a role's durable queue. */
export type Sender = (command: Command<PostMessagePayload>) => Promise<{ queued: boolean } | void>;

export const sendNow: Sender = async (command) => {
  await send(command);
};

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
  const response = await fetch(`/api/threads/${q(threadId)}/voice/${q(voiceNoteId)}?durationMs=${Math.round(durationMs)}`, {
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

export function voiceUrl(threadId: string, voiceNoteId: string): string {
  return `/api/threads/${q(threadId)}/voice/${q(voiceNoteId)}`;
}
