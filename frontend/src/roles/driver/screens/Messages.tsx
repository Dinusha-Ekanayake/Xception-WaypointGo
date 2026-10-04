"use client";

import type { Sender } from "@shared/messaging/senders";
import { useTripThread } from "@shared/messaging/useThread";
import { SecondaryButton, SkeletonRows, TripThread, cx } from "@shared/ui";
import { BackIcon } from "../ui.tsx";

// The trip's thread on the driver's phone (issue #136): what the dispatcher
// wrote to the driver or to everyone, the driver's replies to the dispatcher
// or a store on the trip, and reports, typed or spoken. With no signal both are
// kept on the phone and sent when it returns.

export default function Messages({
  accountId,
  tripId,
  vehicleId,
  online,
  dark,
  sender,
  onBack,
}: {
  accountId: string;
  tripId: string | null;
  vehicleId: string | null;
  online: boolean;
  dark: boolean;
  sender: Sender;
  onBack: () => void;
}): React.JSX.Element {
  const thread = useTripThread(tripId, accountId);
  const threadId = thread.data?.threadId ?? null;

  return (
    <section
      aria-label="Messages"
      className={cx("absolute inset-0 z-50 flex flex-col gap-3 px-5 pt-6 pb-5", dark ? "go-dark bg-[#161616] text-white" : "bg-[#E7F3F2] text-go-ink")}
    >
      <header className="flex items-center gap-3">
        <button type="button" onClick={onBack} aria-label="Back" className="flex size-11 items-center justify-center rounded-full bg-go-card">
          <BackIcon />
        </button>
        <div className="flex flex-col">
          <h1 className="text-[22px] font-medium leading-tight">Messages</h1>
          <p className="text-[13px] text-go-secondary">{vehicleId ? `${vehicleId} · dispatcher and stores on this trip` : "Your trip"}</p>
        </div>
      </header>
      {!online && (
        <p role="status" className="rounded-go-card-s bg-go-warning-tint px-3.5 py-2.5 text-[13px] text-go-warning-text">
          No signal. What you write or record is kept on this phone and sent when the connection is back.
        </p>
      )}
      <div className="flex min-h-0 flex-1 flex-col rounded-[24px] bg-go-card p-4">
        {tripId === null ? (
          <p className="py-8 text-center text-sm text-go-secondary">No trip today, so there is no one to message here.</p>
        ) : thread.loading && !thread.data ? (
          <SkeletonRows label="Loading messages…" />
        ) : threadId === null && thread.error ? (
          <div className="flex flex-col items-center gap-3 py-8">
            <p className="text-center text-sm text-go-secondary">Messages could not be loaded. They load when the connection is back.</p>
            <SecondaryButton onClick={thread.refresh}>Try again</SecondaryButton>
          </div>
        ) : threadId === null ? (
          <p className="py-8 text-center text-sm text-go-secondary">This trip has no messages yet.</p>
        ) : (
          <TripThread threadId={threadId} online={online} variant="phone" sender={sender} keepsOffline accountId={accountId} />
        )}
      </div>
    </section>
  );
}
