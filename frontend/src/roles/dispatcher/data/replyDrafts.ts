import { request } from "@shared/api/client";
import type { MessageView } from "@shared/domain/types";
import { newestToAnswer } from "@shared/messaging/templates";
import type { DraftReply } from "@shared/ui/TripThread";

// R-ML-46: a reply drafted by the language model for the newest driver's or
// store's message that no ready reply fits (Jev read it as none of the acts).
// The server decides whether one is drafted and checks it; this only asks for
// the message the chips answer, and answers to whoever wrote it.

type ReplyDraftView = { state: "off" | "none" | "drafted"; draft: string | null };

export function draftTarget(messages: MessageView[]): ReturnType<DraftReply["of"]> {
  const last = newestToAnswer(messages);
  if (!last || last.mine || last.understood) return null;
  if (last.authorRole === "driver") return { messageId: last.messageId, to: "driver", outletId: null };
  if (last.authorRole === "store_manager" && last.aboutOutletId) return { messageId: last.messageId, to: "outlet", outletId: last.aboutOutletId };
  return null;
}

export const DRAFTS: DraftReply = {
  of: draftTarget,
  load: async (messageId, signal) => {
    try {
      const view = await request<ReplyDraftView>(`/api/ml/reply-draft?messageId=${encodeURIComponent(messageId)}`, { signal });
      return view.state === "drafted" ? view.draft : null;
    } catch {
      // The ready replies stand without it; nothing on screen depends on a draft.
      return null;
    }
  },
};
