"use client";

import { request } from "../api/client.ts";
import { useResource } from "../api/useResource.ts";
import type { NotificationView, TranscriptView } from "../domain/types.ts";
import { usePlayableUrl } from "../messaging/audioCache.ts";
import type { Translate } from "../messaging/thread.ts";
import { voiceUrl } from "../messaging/useThread.ts";
import { VoiceNote } from "./VoiceNote.tsx";

// A voice message in a notifications list (the driver's Home, the loader's
// Notifications; Figma "Home · Notifications"): a play button and the words,
// never a chat. The words are the machine transcript once
// the engine has written it (FF-05), else the message's own text; with no signal
// the note still plays from the phone's copy and the text stands in.

const plain: Translate = (text, values) =>
  values ? text.replace(/\{(\w+)\}/g, (whole, key: string) => (key in values ? String(values[key]) : whole)) : text;

export function VoiceNotice({
  item,
  accountId,
  tr: t = plain,
}: {
  item: NotificationView;
  accountId?: string;
  tr?: Translate;
}): React.JSX.Element | null {
  const threadId = item.subjectId;
  const voiceNoteId = item.facts?.voiceNoteId;
  const messageId = item.facts?.messageId;
  const transcript = useResource(
    threadId && messageId
      ? (signal: AbortSignal) =>
          request<TranscriptView>(`/api/threads/${encodeURIComponent(threadId)}/messages/${encodeURIComponent(messageId)}/transcript`, { signal })
      : null,
    `notice-transcript|${messageId ?? ""}`,
    60_000,
  );
  // R-MSG-11: the phone's saved copy when there is no signal.
  const src = usePlayableUrl(accountId, threadId && voiceNoteId ? voiceUrl(threadId, voiceNoteId) : "");
  if (!threadId || !voiceNoteId) return null;
  const words = transcript.data?.status === "done" && transcript.data.text ? transcript.data.text : item.body.trim() || t("Voice message");
  return (
    <VoiceNote
      src={src}
      durationMs={item.facts?.voiceDurationMs ? Number(item.facts.voiceDurationMs) : null}
      seed={voiceNoteId}
      tone="theirs"
      label={t("Voice message from {name}", { name: item.title })}
      heading={item.title}
      caption={words}
      tr={t}
    />
  );
}
