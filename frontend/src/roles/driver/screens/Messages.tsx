"use client";

import { useEffect, useState } from "react";
import type { Sender } from "@shared/messaging/senders";
import { useTripThread } from "@shared/messaging/useThread";
import { SecondaryButton, SkeletonRows, TripThread, cx } from "@shared/ui";
import type { Motion } from "../data/motion.ts";
import { BackIcon } from "../ui.tsx";

// The trip's thread on the driver's phone (issue #136): what the dispatcher
// wrote to the driver or to everyone, the driver's replies to the dispatcher
// or a store on the trip, and reports, typed or spoken. With no signal both are
// kept on the phone and sent when it returns.
//
// No phone use while driving (R-EXE-28, Motor Traffic Act s.152A): while the
// vehicle moves the thread is covered and cannot be used; what was being
// written stays underneath and nothing is lost. With no location the driver
// says they have stopped. Report problem is never covered.

/** How long "I have stopped" holds when the phone cannot tell. */
const STOPPED_FOR_MS = 2 * 60_000;

export default function Messages({
  accountId,
  tripId,
  vehicleId,
  online,
  dark,
  sender,
  motion,
  onBack,
}: {
  accountId: string;
  tripId: string | null;
  vehicleId: string | null;
  online: boolean;
  dark: boolean;
  sender: Sender;
  motion: Motion;
  onBack: () => void;
}): React.JSX.Element {
  const thread = useTripThread(tripId, accountId);
  const threadId = thread.data?.threadId ?? null;
  const [saidStopped, setSaidStopped] = useState<number | null>(null);
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (motion === "moving") setSaidStopped(null);
  }, [motion]);
  useEffect(() => {
    if (saidStopped === null) return;
    const timer = window.setTimeout(() => setNow(Date.now()), saidStopped + STOPPED_FOR_MS - Date.now() + 50);
    return () => window.clearTimeout(timer);
  }, [saidStopped]);
  const blocked = motion === "moving" || (motion === "unknown" && (saidStopped === null || now - saidStopped > STOPPED_FOR_MS));

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
      <div className="relative flex min-h-0 flex-1 flex-col rounded-[24px] bg-go-card p-4">
        {blocked && tripId !== null && (
          <div
            role="alert"
            data-testid="driving-gate"
            className="absolute inset-0 z-10 flex flex-col items-center justify-center gap-3 rounded-[24px] bg-go-card px-6 text-center"
          >
            <h2 className="text-[22px] font-medium text-go-ink">{motion === "moving" ? "You are driving" : "Stop to read and write messages"}</h2>
            <p className="text-[15px] text-go-muted">
              {motion === "moving"
                ? "Messages wait here. Stop the vehicle to read and write them."
                : "Your location is not known, so Waypoint cannot tell whether you are driving."}
            </p>
            {motion === "unknown" && (
              <SecondaryButton
                onClick={() => {
                  const at = Date.now();
                  setSaidStopped(at);
                  setNow(at);
                }}
              >
                I have stopped
              </SecondaryButton>
            )}
            <p className="text-[13px] text-go-secondary">A fault or an emergency: use Report problem. It always works.</p>
          </div>
        )}
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
          <div inert={blocked} aria-hidden={blocked || undefined} className="flex min-h-0 flex-1 flex-col">
            <TripThread threadId={threadId} online={online} variant="phone" sender={sender} keepsOffline accountId={accountId} />
          </div>
        )}
      </div>
    </section>
  );
}
