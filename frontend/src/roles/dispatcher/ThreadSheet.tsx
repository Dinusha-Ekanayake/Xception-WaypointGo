"use client";

import { useRef } from "react";
import { request } from "@shared/api/client";
import { useResource } from "@shared/api/useResource";
import type { ThreadView } from "@shared/domain/types";
import { TripThread, useOverlay } from "@shared/ui";
import { dayLabel } from "@shared/wording";
import { useDispatcherInbox, type OpenThread } from "./inbox.tsx";

// A trip's thread in a sheet on the right (issue #136), over whichever screen
// opened it: a warning sign on the timeline, the trip page, a notification.
// The dispatcher reads every message on it and writes to anyone on the trip.

export default function ThreadSheet({ online }: { online: boolean }): React.JSX.Element | null {
  const ctx = useDispatcherInbox();
  const open = ctx?.thread ?? null;
  const onClose = () => ctx?.openThread(null);
  if (!open) return null;
  return <ThreadSheetPanel open={open} online={online} onClose={onClose} />;
}

/** Mounted only while a thread is open, so useOverlay's focus trap and Escape set up and tear down cleanly with it. */
function ThreadSheetPanel({
  open,
  online,
  onClose,
}: {
  open: OpenThread;
  online: boolean;
  onClose: () => void;
}): React.JSX.Element {
  const panel = useRef<HTMLDivElement>(null);
  const { closing, requestClose } = useOverlay(panel, onClose);
  const threadId = open.threadId;
  const thread = useResource(
    (signal: AbortSignal) => request<ThreadView>(`/api/threads/${encodeURIComponent(threadId)}`, { signal }),
    `thread-head|${threadId}`,
  );
  const head = thread.data && thread.data.threadId === open.threadId ? thread.data : null;

  return (
    <div data-closing={closing || undefined} className="go-overlay fixed inset-0 z-50 flex justify-end" role="presentation">
      <button type="button" tabIndex={-1} aria-label="Close messages" onClick={requestClose} className="go-backdrop absolute inset-0 bg-black/20" />
      <section
        ref={panel}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-label="Trip messages"
        className="go-panel go-panel-drawer relative flex h-dvh w-full max-w-[520px] flex-col gap-3 overscroll-contain bg-go-card p-5 shadow-go-card outline-none md:p-6"
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
          <button type="button" onClick={requestClose} className="min-h-9 rounded-full bg-go-surface px-3.5 text-sm font-medium text-go-teal">
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
