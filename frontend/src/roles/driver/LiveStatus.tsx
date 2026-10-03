"use client";

import { cx } from "@shared/ui";

// What the driver must know about the phone's connection (issue #114), over the
// Figma screens: offline, writes still on the phone, the run read from the copy
// kept here, or a session that ended. Degrade visibly: a record that has not
// left the phone is never shown as sent.

function line({ online, waiting, keptAt, expired }: { online: boolean; waiting: number; keptAt: Date | null; expired: boolean }): string | null {
  if (expired) return "You have been signed out. Nothing on this phone is lost: sign in again and it is sent.";
  const onPhone = `${waiting} ${waiting === 1 ? "record" : "records"} saved on this phone`;
  if (!online) return waiting > 0 ? `Offline · ${onPhone}, sent when you are back online` : "Offline · showing the run saved on this phone";
  if (keptAt) return `Waypoint is not answering · ${waiting > 0 ? onPhone : "showing the run saved on this phone"}`;
  if (waiting > 0) return `Sending ${onPhone}`;
  return null;
}

export default function LiveStatus({
  online,
  waiting,
  keptAt,
  expired,
  loading,
  message,
}: {
  online: boolean;
  waiting: number;
  keptAt: Date | null;
  expired: boolean;
  loading: boolean;
  message: string | null;
}): React.JSX.Element | null {
  const status = loading ? "Loading your run…" : line({ online, waiting, keptAt, expired });
  if (!status && !message) return null;
  return (
    <div className="pointer-events-none absolute inset-x-4 top-[86px] z-40 flex flex-col items-center gap-2">
      {status && (
        <p
          role="status"
          className={cx(
            "rounded-full px-4 py-2 text-center text-[13px] font-medium shadow-lg",
            expired || !online ? "bg-[#FFF4E5] text-[#7A4B00]" : "bg-[#031B08] text-white",
          )}
        >
          {status}
        </p>
      )}
      {message && (
        <p role="alert" className="rounded-full bg-[#031B08] px-4 py-2 text-center text-[13px] font-medium text-white shadow-lg">
          {message}
        </p>
      )}
    </div>
  );
}

/**
 * Asked once, when the driver has a run (issue #161 on the live run, #114): the
 * dispatcher's live map follows the vehicle only while a run is open. Declining
 * never blocks the run; the dispatcher then sees the stops only.
 */
export function LocationPrompt({ onAllow, onDecline }: { onAllow: () => void; onDecline: () => void }): React.JSX.Element {
  return (
    <section
      aria-label="Share your location"
      className="absolute inset-x-4 bottom-6 z-40 flex flex-col gap-3 rounded-[24px] bg-white p-5 text-black shadow-[0_8px_30px_rgba(0,0,0,0.18)]"
    >
      <h2 className="text-[18px] font-medium">Share your location while the run is open?</h2>
      <p className="text-[14px] text-[#6B6B6B]">The dispatcher sees where the vehicle is until the last stop. Nothing is shared after the run.</p>
      <div className="flex gap-3">
        <button type="button" onClick={onDecline} className="h-12 flex-1 rounded-[16px] bg-[#E7F3F2] text-[16px] font-medium">
          Not now
        </button>
        <button type="button" onClick={onAllow} className="h-12 flex-1 rounded-[16px] bg-[#031B08] text-[16px] font-medium text-white">
          Share location
        </button>
      </div>
    </section>
  );
}
