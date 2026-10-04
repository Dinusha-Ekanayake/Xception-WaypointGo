"use client";

import { useTripThread } from "@shared/messaging/useThread";
import { Sheet, TripThread } from "@shared/ui";
import { useT } from "../i18n.tsx";

// The trip's thread from the load sheet (issue #136): the loader reads what the
// dispatcher wrote to the loaders or to everyone, and writes to the dispatcher
// alone, a report of missing or damaged items included (R-MSG-02, R-MSG-03).
// An item flagged on the load sheet already reaches the thread as a report.

export default function TripMessages({
  tripId,
  vehicleId,
  online,
  onClose,
}: {
  tripId: string;
  vehicleId: string;
  online: boolean;
  onClose: () => void;
}): React.JSX.Element {
  const tr = useT();
  const thread = useTripThread(tripId);
  const threadId = thread.data?.threadId ?? null;
  return (
    <Sheet label={tr("Messages for {vehicle}", { vehicle: vehicleId })} onClose={onClose}>
      <header className="flex items-start justify-between gap-3">
        <div className="flex flex-col gap-1">
          <h2 className="text-[20px] font-medium text-go-ink">{tr("Messages for {vehicle}", { vehicle: vehicleId })}</h2>
          <p className="text-[13px] text-go-secondary">{tr("Write to the dispatcher. A report of missing or damaged items goes to the dispatcher only.")}</p>
        </div>
        <button type="button" onClick={onClose} className="min-h-11 shrink-0 rounded-full bg-go-surface px-4 text-sm font-medium text-go-teal">
          {tr("Close")}
        </button>
      </header>
      <div className="flex h-[62dvh] min-h-0 flex-col">
        {threadId ? (
          <TripThread threadId={threadId} online={online} variant="phone" />
        ) : (
          <p className="py-8 text-center text-sm text-go-secondary">{thread.loading ? "…" : tr("This trip has no messages yet.")}</p>
        )}
      </div>
    </Sheet>
  );
}
