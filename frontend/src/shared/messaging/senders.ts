"use client";

import { send, type Command } from "@shared/api/commands";
import type { PostMessagePayload } from "@shared/domain/types";
import { enqueue, isOutage, saveUpload, type Role } from "@shared/offline";
import { uploadVoice, voicePath } from "./useThread.ts";

// How a message leaves the phone (issue #136). The dispatcher works online and
// posts straight away. A driver, a loader or a store manager may have no
// signal: their message is sent now when it can be, else kept on the device,
// its voice note with it, and sent when the connection returns. The audio goes
// first and the message waits for it (StoredEntry.waitsFor), so the server
// never sees a message naming audio it does not have (R-MSG-06).

/** A recording to send with the message. */
export type Voice = { threadId: string; voiceNoteId: string; blob: Blob; durationMs: number; peaks: number[] };

/** Sends a post; answers whether it was kept on the device instead. */
export type Sender = (command: Command<PostMessagePayload>, voice?: Voice) => Promise<{ queued: boolean }>;

/** Straight to the server, the audio first. Online only. */
export const sendNow: Sender = async (command, voice) => {
  if (voice) await uploadVoice(voice.threadId, voice.voiceNoteId, voice.blob, voice.durationMs, voice.peaks);
  await send(command);
  return { queued: false };
};

/**
 * Sent now when there is a connection, else kept on this device. A refusal from
 * the server is said, never kept: resending would be refused the same way.
 *
 * @param actingUserId the loader who entered their PIN on a shared device; the
 *     server writes the message as them (R-IAM-25)
 */
export function queuedSender(options: {
  accountId: string;
  role: Role;
  online: boolean;
  onQueued: () => void;
  actingUserId?: string | null;
}): Sender {
  const { accountId, role, online, onQueued, actingUserId } = options;
  const keep = async (command: Command<PostMessagePayload>, voice?: Voice) => {
    if (voice) {
      const saved = await saveUpload(accountId, {
        id: voice.voiceNoteId,
        path: voicePath(voice.threadId, voice.voiceNoteId, voice.durationMs, voice.peaks),
        subject: voice.threadId,
        contentType: (voice.blob.type || "audio/webm").split(";")[0]!,
        blob: voice.blob,
      });
      if (!saved.durable) throw new Error(`Not saved on this device: ${saved.reason}`);
    }
    const kept = await enqueue(accountId, role, command, voice ? [voice.voiceNoteId] : []);
    if (!kept.durable) throw new Error(`Not saved on this device: ${kept.reason}`);
    onQueued();
    return { queued: true };
  };
  return async (posted, voice) => {
    const command = actingUserId ? { ...posted, actingUserId } : posted;
    if (!online) return keep(command, voice);
    let uploaded = false;
    try {
      if (voice) {
        await uploadVoice(voice.threadId, voice.voiceNoteId, voice.blob, voice.durationMs, voice.peaks);
        uploaded = true;
      }
      await send(command);
      return { queued: false };
    } catch (failure) {
      if (!isOutage(failure)) throw failure;
      // The audio may already be on the server; the same id stores it once.
      return keep(command, uploaded ? undefined : voice);
    }
  };
}
