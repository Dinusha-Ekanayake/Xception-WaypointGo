"use client";

import { useEffect, useRef } from "react";
import { request } from "@shared/api/client";
import { useResource } from "@shared/api/useResource";
import type { ThreadView } from "@shared/domain/types";
import { TripThread } from "@shared/ui";
import { dayLabel } from "@shared/wording";
import { useDispatcherInbox } from "./inbox.tsx";

// A trip's thread in a sheet on the right (issue #136), over whichever screen
// opened it: a warning sign on the timeline, the trip page, a notification.
// The dispatcher reads every message on it and writes to anyone on the trip.

export default function ThreadSheet({ online }: { online: boolean }): React.JSX.Element | null {
  const ctx = useDispatcherInbox();
  const open = ctx?.thread ?? null;
  const close = useRef(() => ctx?.openThread(null));
  close.current = () => ctx?.openThread(null);
  const threadId = open?.threadId ?? null;
  const thread = useResource(
    threadId === null ? null : (signal: AbortSignal) => request<ThreadView>(`/api/threads/${encodeURIComponent(threadId)}`, { signal }),
    `thread-head|${threadId ?? ""}`,
  );

  useEffect(() => {
    if (threadId === null) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") close.current();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [threadId]);

  if (!open) return null;
  const head = thread.data && thread.data.threadId === open.threadId ? thread.data : null;

  return (
    <div className="fixed inset-0 z-50 flex justify-end" role="presentation">
      <button type="button" tabIndex={-1} aria-label="Close messages" onClick={() => close.current()} className="absolute inset-0 animate-fade-in bg-black/20" />
      <section
        role="dialog"
        aria-modal="true"
        aria-label="Trip messages"
        className="relative flex h-dvh w-full max-w-[520px] animate-slide-in-end flex-col gap-3 overscroll-contain bg-go-card p-5 shadow-go-card md:p-6"
      >
        <header className="flex items-start justify-between gap-3">
          <div className="flex flex-col gap-0.5">
            <h2 className="text-[22px] font-medium text-go-ink">{head?.vehicleId ? `Messages · ${head.vehicleId}` : "Trip messages"}</h2>
            {head && (
              <p className="text-[13px] text-go-secondary">
                {head.serviceDate ? `${dayLabel(head.serviceDate)} · ` : ""}
                {head.outletIds.join(", ")}
              </p>
            )}
          </div>
          <button type="button" onClick={() => close.current()} className="min-h-9 rounded-full bg-go-surface px-3.5 text-sm font-medium text-go-teal">
            Close
          </button>
        </header>
        {!online && (
          <p role="status" className="rounded-go-card-s bg-go-warning-tint px-3.5 py-2.5 text-sm text-go-warning-text">
            Offline. Showing what arrived; writing needs a connection.
          </p>
        )}
        <TripThread
          key={open.threadId}
          threadId={open.threadId}
          online={online}
          variant="desk"
          focusMessageId={open.messageId ?? null}
          {...(open.draft ? { draft: open.draft } : {})}
        />
      </section>
    </div>
  );
}
