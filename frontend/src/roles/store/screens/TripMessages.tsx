"use client";

import { useMemo } from "react";
import { queuedSender } from "@shared/messaging/senders";
import { useTripThread } from "@shared/messaging/useThread";
import { Sheet, TripThread } from "@shared/ui";

// A trip's thread for the store (issue #136): what the dispatcher or the driver
// wrote to this store or to everyone on the trip, and the store's own replies
// and reports, which go to the dispatcher alone (R-MSG-01 to R-MSG-03). Opened
// from a message notification, or from a delivery's row by its trip. With no
// signal a message, voice included, is kept on the phone (resilient tier).

export type OpenThread = { threadId: string } | { tripId: string; vehicleId: string };

export default function TripMessages({
  accountId,
  open,
  online,
  onQueued,
  onClose,
}: {
  accountId: string;
  open: OpenThread;
  online: boolean;
  onQueued: () => void;
  onClose: () => void;
}): React.JSX.Element {
  const byTrip = useTripThread("tripId" in open ? open.tripId : null, accountId);
  const sender = useMemo(() => queuedSender({ accountId, role: "store_manager", online, onQueued }), [accountId, online, onQueued]);
  const threadId = "threadId" in open ? open.threadId : byTrip.data?.threadId ?? null;
  const title = "vehicleId" in open ? `Messages · ${open.vehicleId}` : "Trip messages";
  return (
    <Sheet label={title} onClose={onClose}>
      <header className="flex items-start justify-between gap-3">
        <div className="flex flex-col gap-1">
          <h2 className="text-[20px] font-medium text-go-ink">{title}</h2>
          <p className="text-[13px] text-go-secondary">You see what is written to your store or to everyone on the trip. Replies go to the dispatcher.</p>
        </div>
        <button type="button" onClick={onClose} className="min-h-11 shrink-0 rounded-full bg-go-surface px-4 text-sm font-medium text-go-teal">
          Close
        </button>
      </header>
      <div className="flex h-[62dvh] min-h-0 flex-col">
        {threadId ? (
          <TripThread threadId={threadId} online={online} variant="phone" sender={sender} keepsOffline accountId={accountId} />
        ) : (
          <p className="py-8 text-center text-sm text-go-secondary">
            {byTrip.loading ? "Loading messages…" : byTrip.error ? "Messages could not be loaded. Try again when the connection is back." : "This trip has no messages yet."}
          </p>
        )}
      </div>
    </Sheet>
  );
}
