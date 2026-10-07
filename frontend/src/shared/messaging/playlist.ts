import type { MessageView } from "../domain/messaging.ts";

// The messages a person has not heard yet, in the order they came: what "Play
// messages" plays. Pure. What was heard is remembered per thread on the device,
// by the time of the last message played, so a new message after it is new.

export function unheard(messages: MessageView[], heardUpTo: string | null): MessageView[] {
  return messages
    .filter((m) => !m.mine && (m.body.trim() !== "" || m.voiceNoteId !== null))
    .filter((m) => heardUpTo === null || m.createdAt > heardUpTo)
    .sort((a, b) => a.createdAt.localeCompare(b.createdAt) || a.messageId.localeCompare(b.messageId));
}

const KEY = "waypoint.heard.";

export function heardUpTo(threadId: string): string | null {
  try {
    return window.localStorage.getItem(KEY + threadId);
  } catch {
    return null;
  }
}

export function markHeard(threadId: string, createdAt: string): void {
  try {
    window.localStorage.setItem(KEY + threadId, createdAt);
  } catch {
    // Private mode: the next visit plays them again, which is safe.
  }
}
