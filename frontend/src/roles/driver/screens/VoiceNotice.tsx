"use client";

import { request } from "@shared/api/client";
import { useResource } from "@shared/api/useResource";
import type { NotificationView, TranscriptView } from "@shared/domain/types";
import { voiceUrl } from "@shared/messaging/useThread";
import { VoiceNote } from "@shared/ui";
import { useT } from "../i18n.tsx";

// A voice message on the driver's Home (Figma "Home · Notifications"): a play
// button and the words, never a chat. The words are the machine transcript once
// the engine has written it (FF-05), else the message's own text; with no signal
// the note still plays from the phone's copy and the text stands in.

export default function VoiceNotice({ item }: { item: NotificationView }): React.JSX.Element | null {
  const t = useT();
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
  if (!threadId || !voiceNoteId) return null;
  const words = transcript.data?.status === "done" && transcript.data.text ? transcript.data.text : item.body.trim() || t("Voice message");
  return (
    <VoiceNote
      src={voiceUrl(threadId, voiceNoteId)}
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
