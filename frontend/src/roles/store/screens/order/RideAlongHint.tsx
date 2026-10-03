"use client";

import { useResource } from "@shared/api/useResource";
import type { StoreGateway } from "../../data/gateway.ts";
import { dayLabel } from "../../data/format.ts";

// Issue #199 (R-ORD-13): a Tech store choosing a day is told when a nearby day
// already has a trip to its district, so it can join it. Advice only; the order
// still goes for whatever day the manager picks, and Planning decides the load.

export default function RideAlongHint({
  gateway,
  outletId,
  brandCode,
  date,
  onPick,
}: {
  gateway: StoreGateway;
  outletId: string;
  brandCode: string;
  date: string;
  onPick: (date: string) => void;
}): React.JSX.Element | null {
  const asked = brandCode.toLowerCase() === "tech" && outletId !== "";
  const hint = useResource(asked ? (s) => gateway.rideAlong(outletId, date, s) : null, `${outletId}|${date}`);
  if (!asked) return null;
  // Degrade visibly (rule 9): say the hint is missing rather than show nothing.
  if (hint.error) {
    return (
      <p role="status" className="text-[13px] text-go-muted">
        Shared trip suggestions are unavailable right now.
      </p>
    );
  }
  const days = hint.data?.offered ? hint.data.days : [];
  if (days.length === 0) return null;
  return (
    <section aria-label="Shared trip" className="flex flex-col gap-2 rounded-[18px] bg-white p-4">
      <h2 className="text-[15px] font-medium text-black">A trip already goes to your area</h2>
      {days.map((d) => (
        <div key={d.date} className="flex items-center justify-between gap-3">
          <p className="text-[13px] text-go-muted">
            {dayLabel(d.date)} · {d.stopsBooked === 1 ? "1 other store" : `${d.stopsBooked} other stores`} booked
          </p>
          <button
            type="button"
            onClick={() => onPick(d.date)}
            className="min-h-10 rounded-[14px] bg-[#031a0c] px-3 text-[13px] font-medium text-white"
          >
            Deliver {dayLabel(d.date)}
          </button>
        </div>
      ))}
      <p className="text-[12px] text-go-muted">Sharing a trip frees a vehicle. The dispatcher still confirms the load.</p>
    </section>
  );
}
