"use client";

import { useState } from "react";
import type { SyncState } from "@shared/offline";
import { cx, formatClock } from "@shared/ui";

// What is still on this device, and what the server refused. A refused write is
// never merged or dropped by the engine (architecture rule 6): it waits here,
// with the server's reason, for a person to send it again or discard it.

const describeKind = (kind: string) => kind.replace(":", " · ").replace(/([a-z])([A-Z])/g, "$1 $2").toLowerCase();

export default function SyncStatus({ sync, online }: { sync: SyncState; online: boolean }): React.JSX.Element | null {
  const [open, setOpen] = useState(false);
  const waiting = sync.pending - sync.held.length;
  if (sync.pending === 0 && !open) return null;

  return (
    <>
      {waiting > 0 && (
        <button
          type="button"
          onClick={sync.syncNow}
          title={sync.lastSyncedAt ? `Last synced ${formatClock(sync.lastSyncedAt)}` : undefined}
          className={cx("min-h-10 rounded-full px-3.5 text-[13px] font-medium", online ? "bg-go-mint text-black" : "bg-go-warning-tint text-go-warning-text")}
        >
          {sync.syncing ? "Sending…" : online ? `${waiting} to send · sync now` : `${waiting} saved on this device`}
        </button>
      )}
      {sync.held.length > 0 && (
        <button type="button" onClick={() => setOpen(true)} className="min-h-10 rounded-full bg-go-danger-tint px-3.5 text-[13px] font-medium text-go-danger-strong">
          {sync.held.length} to review
        </button>
      )}
      {open && (
        <div className="fixed inset-0 z-50 flex items-end justify-center sm:items-center" role="presentation">
          <button type="button" aria-label="Close" onClick={() => setOpen(false)} className="absolute inset-0 bg-black/25" />
          <div role="dialog" aria-modal="true" aria-label="Changes to review" className="relative flex max-h-[85dvh] w-full max-w-[520px] flex-col gap-4 overflow-y-auto rounded-t-[32px] bg-white px-6 pt-6 pb-8 font-go sm:rounded-[32px]">
            <h2 className="text-[22px] font-medium text-black">Changes the server refused</h2>
            <p className="text-[14px] text-go-muted">
              These were saved on this device but could not be applied, usually because the record changed while you were offline. Check the screen they came from, then send again or discard.
            </p>
            {sync.held.length === 0 && <p className="text-[15px] text-go-muted">Nothing left to review.</p>}
            <ul className="flex flex-col gap-3">
              {sync.held.map((e) => (
                <li key={e.commandId} className="flex flex-col gap-2 rounded-[20px] bg-go-canvas p-4">
                  <span className="text-[15px] font-medium text-black capitalize">{describeKind(e.kind)}</span>
                  <span className="text-[13px] text-go-muted">
                    Saved {formatClock(new Date(e.enqueuedAt))} · {e.lastError ?? "refused"}
                  </span>
                  <span className="flex gap-2">
                    <button type="button" onClick={() => void sync.retry(e)} className="min-h-12 flex-1 rounded-[18px] border border-go-mint bg-white text-[14px] font-medium">
                      Send again
                    </button>
                    <button
                      type="button"
                      onClick={() => window.confirm("Discard this change? It will not be sent.") && void sync.discard(e.commandId)}
                      className="min-h-12 flex-1 rounded-[18px] bg-[#ea2525] text-[14px] font-medium text-white"
                    >
                      Discard
                    </button>
                  </span>
                </li>
              ))}
            </ul>
            <button type="button" onClick={() => setOpen(false)} className="min-h-12 rounded-[22px] bg-[#031a0c] text-[15px] font-medium text-white">
              Close
            </button>
          </div>
        </div>
      )}
    </>
  );
}
