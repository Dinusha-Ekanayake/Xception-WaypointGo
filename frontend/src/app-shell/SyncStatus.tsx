"use client";

import { useRef, useState } from "react";
import type { StoredEntry, SyncState } from "@shared/offline";
import { cx, useOverlay } from "@shared/ui";
import { clock } from "@shared/wording";

// What is still on this device, and what the server refused. A refused write is
// never merged or dropped by the engine (architecture rule 6): it waits here,
// with the server's reason, for its owner (decision D-O) to redo it on the
// current version or to discard it with a reason. Both are recorded on the server.

/** Short reasons a person picks when dropping a change; kept with it on the server (rule 8). */
const DISCARD_REASONS = ["No longer needed", "Entered by mistake", "Done another way"] as const;

/** A short, friendly name for a command kind, for the roles that queue writes (driver, loader, store manager). */
const KIND_LABEL: Record<string, string> = {
  "delivery:Start": "Delivery started",
  "delivery:RecordArrival": "Arrival recorded",
  "delivery:Record": "Delivery recorded",
  "delivery:CaptureProof": "Proof of delivery saved",
  "delivery:ReportVehicleStatus": "Vehicle status reported",
  "delivery:ArriveAtDepot": "At the depot",
  "delivery:ReportFault": "Vehicle fault reported",
  "delivery:RecordPositions": "Position recorded",
  "issue:Raise": "Issue reported",
  "issue:Assign": "Issue assigned",
  "issue:Resolve": "Issue resolved",
  "issue:RecordReplacement": "Replacement recorded",
  "issue:ScheduleRedelivery": "Redelivery scheduled",
  "issue:Close": "Issue closed",
  "issue:Cancel": "Issue cancelled",
  "loading:Start": "Loading started",
  "loading:Check": "Item checked",
  "loading:Shortfall": "Shortfall reported",
  "loading:RequestInterchange": "Interchange requested",
  "loading:Release": "Vehicle released",
  "loading:HandBack": "Trip handed back",
  "message:Post": "Message sent",
  "message:Resolve": "Report resolved",
  "order:Place": "Order placed",
  "order:Amend": "Order changed",
  "order:Cancel": "Order cancelled",
  "order:CloseForDay": "Orders closed for the day",
  "order:AcceptShortfall": "Shortfall accepted",
  "receipt:Confirm": "Delivery confirmed",
  "receipt:ConfirmPartial": "Partial delivery confirmed",
  "receipt:Dispute": "Delivery disputed",
  "receipt:VerifyHandover": "Handover verified",
  "receipt:ReissueHandoverPin": "Handover PIN reissued",
};

/** The command kind in words; an unlisted kind still reads as words, never as its raw lowercase code. */
const describeKind = (kind: string): string => {
  const known = KIND_LABEL[kind];
  if (known) return known;
  return kind.replace(":", " ").replace(/([a-z])([A-Z])/g, "$1 $2").toLowerCase();
};

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
          title={sync.lastSyncedAt ? `Last synced ${clock(sync.lastSyncedAt)}` : undefined}
          className={cx("min-h-10 whitespace-nowrap rounded-full px-3.5 text-[13px] font-medium", online ? "bg-go-mint text-black" : "bg-go-warning-tint text-go-warning-text")}
        >
          {sync.syncing ? "Sending…" : online ? `${waiting} to send · sync now` : `${waiting} saved on this device`}
          {/* A queued-writes count change is announced even though the visible pill carries no role of its own. */}
          <span role="status" aria-live="polite" className="sr-only">
            {waiting} {waiting === 1 ? "change" : "changes"} waiting to send
          </span>
        </button>
      )}
      {sync.held.length > 0 && (
        <button type="button" onClick={() => setOpen(true)} className="min-h-10 whitespace-nowrap rounded-full bg-go-danger-tint px-3.5 text-[13px] font-medium text-go-danger-strong">
          {sync.held.length} to review
          <span role="status" aria-live="polite" className="sr-only">
            {sync.held.length} {sync.held.length === 1 ? "change" : "changes"} held for review
          </span>
        </button>
      )}
      {open && <ReviewDialog sync={sync} onClose={() => setOpen(false)} />}
    </>
  );
}

/** The held-changes review dialog, with a focus trap and Escape (shared/ui's useOverlay). */
function ReviewDialog({ sync, onClose }: { sync: SyncState; onClose: () => void }): React.JSX.Element {
  const panel = useRef<HTMLDivElement>(null);
  const { closing, requestClose } = useOverlay(panel, onClose);

  return (
    <div data-closing={closing || undefined} className="go-overlay fixed inset-0 z-50 flex items-end justify-center sm:items-center" role="presentation">
      <button type="button" aria-label="Close" onClick={requestClose} className="go-backdrop absolute inset-0 bg-black/25" />
      <div
        ref={panel}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-label="Changes to review"
        className="go-panel go-panel-dialog relative flex max-h-[85dvh] w-full max-w-[520px] flex-col gap-4 overflow-y-auto rounded-t-[32px] bg-white px-6 pt-6 pb-8 font-go outline-none sm:rounded-[32px]"
      >
        <h2 className="text-[22px] font-medium text-black">Changes the server refused</h2>
        <p className="text-[14px] text-go-muted">
          These were saved on this device but could not be applied, usually because the record changed while you were offline. Check the screen they came from, then redo them on the current version or discard them.
        </p>
        {sync.held.length === 0 && <p className="text-[15px] text-go-muted">Nothing left to review.</p>}
        <ul className="flex flex-col gap-3">
          {sync.held.map((e) => (
            <HeldChange key={e.commandId} entry={e} sync={sync} />
          ))}
        </ul>
        <button type="button" onClick={requestClose} className="min-h-12 rounded-[22px] bg-[#031a0c] text-[15px] font-medium text-white">
          Close
        </button>
      </div>
    </div>
  );
}

function HeldChange({ entry, sync }: { entry: StoredEntry; sync: SyncState }): React.JSX.Element {
  const [dropping, setDropping] = useState(false);
  const [reason, setReason] = useState<string>(DISCARD_REASONS[0]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const act = async (work: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await work();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "Could not do that. Try again.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <li className="flex flex-col gap-2 rounded-[20px] bg-go-canvas p-4">
      <span className="text-[15px] font-medium text-black">{describeKind(entry.kind)}</span>
      <span className="text-[13px] text-go-muted">
        Saved {clock(new Date(entry.enqueuedAt))} · {entry.lastError ?? "refused"}
      </span>
      {error && (
        <span role="alert" className="text-[13px] text-go-danger-strong">
          {error}
        </span>
      )}
      {dropping ? (
        <span className="flex flex-col gap-2">
          <label className="flex flex-col gap-1 text-[13px] text-go-muted">
            Why discard it?
            <select value={reason} onChange={(ev) => setReason(ev.target.value)} className="min-h-12 rounded-[14px] border border-go-rule bg-white px-3 text-[15px] text-black">
              {DISCARD_REASONS.map((r) => (
                <option key={r} value={r}>
                  {r}
                </option>
              ))}
            </select>
          </label>
          <span className="flex gap-2">
            <button type="button" onClick={() => setDropping(false)} disabled={busy} className="min-h-12 flex-1 rounded-[18px] border border-go-rule bg-white text-[14px] font-medium">
              Keep it
            </button>
            <button
              type="button"
              disabled={busy}
              onClick={() => void act(() => sync.discard(entry.commandId, reason))}
              className="min-h-12 flex-1 rounded-[18px] bg-[#ea2525] text-[14px] font-medium text-white disabled:opacity-50"
            >
              Discard
            </button>
          </span>
        </span>
      ) : (
        <span className="flex gap-2">
          {sync.canRedo(entry) && (
            <button
              type="button"
              disabled={busy}
              onClick={() => void act(() => sync.redo(entry))}
              className="min-h-12 flex-1 rounded-[18px] border border-go-mint bg-white text-[14px] font-medium disabled:opacity-50"
            >
              Redo on the current version
            </button>
          )}
          <button type="button" disabled={busy} onClick={() => setDropping(true)} className="min-h-12 flex-1 rounded-[18px] border border-[#ea2525] bg-white text-[14px] font-medium text-[#ea2525]">
            Discard…
          </button>
        </span>
      )}
    </li>
  );
}
