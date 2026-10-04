"use client";

import { createContext, useContext, useState, type ReactNode } from "react";
import { useInbox, type Inbox } from "@shared/notifications/useInbox";
import type { NotificationView } from "@shared/domain/types";
import type { ThreadDraft } from "@shared/ui";
import type { ViewId } from "./navigation.ts";

// The dispatcher's notifications (issue #118), shared by every screen's bell
// and the Overview card, so the count and the list are read once.

type DispatcherInbox = {
  inbox: Inbox;
  open: boolean;
  setOpen: (open: boolean) => void;
  /** The screen that shows a notification's subject, when there is one. */
  viewOf: (n: NotificationView) => ViewId | null;
  /** The trip thread open in the side sheet (issue #136), at a message or with a draft. */
  thread: OpenThread | null;
  openThread: (thread: OpenThread | null) => void;
};

export type OpenThread = { threadId: string; messageId?: string; draft?: ThreadDraft };

const Context = createContext<DispatcherInbox | null>(null);

const VIEWS: Record<string, ViewId> = {
  issue: "issues",
  order: "orders",
  trip: "live",
  delivery: "live",
  vehicle: "vehicles",
  receipt: "issues",
};

export function InboxProvider({ userId, children }: { userId: string; children: ReactNode }): React.JSX.Element {
  const inbox = useInbox(userId);
  const [open, setOpen] = useState(false);
  const [thread, openThread] = useState<OpenThread | null>(null);
  const viewOf = (n: NotificationView) => (n.subjectType ? VIEWS[n.subjectType] ?? null : null);
  return <Context.Provider value={{ inbox, open, setOpen, viewOf, thread, openThread }}>{children}</Context.Provider>;
}

export function useDispatcherInbox(): DispatcherInbox | null {
  return useContext(Context);
}
