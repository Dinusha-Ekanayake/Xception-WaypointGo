"use client";

import { useEffect, useState } from "react";
import { useResource } from "@shared/api/useResource";
import type { StoreGateway } from "../../data/gateway.ts";
import { dayLabel } from "../../data/format.ts";
import { outlookChip } from "../../data/outlook.ts";
import { useT } from "../../i18n.tsx";

// "A better day for this order" (R-ORD-13, R-ORD-15, issue #199). When a nearby
// open day already has a trip to the store's district (for a chilled order, a
// refrigerated one), the manager is shown that day beside the one they picked
// and why it is better: their goods ride a trip that is going there anyway, so
// they are more likely to arrive in the window, and a vehicle is freed. Tech is
// offered this for any order and Fresh for a chilled order; a Fresh dry order
// is daily and Style is held to its scheduled day, so neither is asked.
// A day the date outlook (R-ML-07) calls busy or at risk is never offered, so
// the card and the date strip can never disagree; when the outlook cannot be
// read the days stand on bookings alone. Advice only: the order goes for
// whatever day the manager keeps, and Planning still decides the load.

export default function RideAlongHint({
  gateway,
  outletId,
  brandCode,
  districtName,
  temperature,
  date,
  onPick,
  onDays,
}: {
  gateway: StoreGateway;
  outletId: string;
  brandCode: string;
  districtName: string;
  /** The order being built: a Fresh chilled order is matched to refrigerated trips. */
  temperature: string;
  date: string;
  onPick: (date: string) => void;
  /** The days offered, so the date strip can prefer them when it suggests a day (#224). */
  onDays?: (dates: string[]) => void;
}): React.JSX.Element | null {
  const t = useT();
  const brand = brandCode.toLowerCase();
  const chilled = brand === "fresh" && temperature === "chilled";
  const asked = outletId !== "" && (brand === "tech" || chilled);
  const hint = useResource(
    asked ? (s) => gateway.rideAlong(outletId, date, s, chilled ? "chilled" : undefined) : null,
    `${outletId}|${date}|${chilled ? "chilled" : "any"}`,
  );
  const raw = hint.data?.offered ? hint.data.days : [];
  const span = raw.map((d) => d.date).concat(date).sort();
  const outlook = useResource(
    asked && raw.length > 0 ? (s) => gateway.outlook(outletId, span[0]!, span[span.length - 1]!, s) : null,
    `${outletId}|${span.join(",")}`,
  );
  const statusOf = (d: string) => outlook.data?.days.find((x) => x.date === d)?.status;
  const usable = raw.filter((d) => {
    const st = statusOf(d.date);
    return st !== "BUSY" && st !== "AT_RISK" && st !== "CLOSED";
  });
  const [kept, setKept] = useState<string | null>(null);
  const [moved, setMoved] = useState<{ from: string; to: string } | null>(null);
  const offered = (outlook.loading ? [] : usable).map((d) => d.date).join(",");
  useEffect(() => onDays?.(offered ? offered.split(",") : []), [offered, onDays]);

  if (!asked) return null;
  if (moved && moved.to === date) {
    return (
      <p role="status" className="flex items-center gap-2 rounded-[14px] bg-go-success-tint px-3.5 py-2.5 text-[13px] text-go-success">
        <Tick />
        {t("Moved to {day} to share the trip already going to {district}.", { day: dayLabel(moved.to), district: districtName })}
        <button type="button" onClick={() => (onPick(moved.from), setMoved(null))} className="ml-auto shrink-0 font-medium whitespace-nowrap text-go-teal">
          {t("Undo")}
        </button>
      </p>
    );
  }
  // Degrade visibly (rule 9): say the hint is missing rather than show nothing.
  if (hint.error) {
    return (
      <p role="status" className="text-[13px] text-go-muted">
        {t("Shared trip suggestions are unavailable right now.")}
      </p>
    );
  }
  const days = outlook.loading ? [] : usable;
  if (days.length === 0 || kept === date) return null;
  const chosenStatus = statusOf(date);
  const bestStatus = statusOf(days[0]!.date);
  const best = days[0]!;
  const others = hint.data?.stopsOnChosen ?? 0;
  const trip = chilled ? t("a refrigerated trip") : t("a trip");
  const pick = (to: string) => {
    setMoved({ from: date, to });
    onPick(to);
  };

  return (
    <section
      aria-label={t("A better day for this order")}
      className="flex flex-col gap-3 rounded-[18px] border border-go-teal/25 bg-gradient-to-br from-go-success-tint to-white p-4 motion-safe:animate-[go-rise-in_320ms_ease-out]"
    >
      <div className="flex items-start gap-3">
        <span aria-hidden className="flex size-9 shrink-0 items-center justify-center rounded-full bg-go-mint text-go-ink">
          <Spark />
        </span>
        <div className="min-w-0">
          <p className="text-[11px] font-semibold tracking-[0.06em] text-go-teal uppercase">{t("A better day for this order")}</p>
          <h2 className="mt-0.5 text-[16px] font-medium text-black">
            {t("{day}: {trip} already goes to {district}", { day: dayLabel(best.date), trip, district: districtName })}
          </h2>
        </div>
      </div>

      <dl className="grid grid-cols-2 gap-2 text-[13px]">
        <div className="rounded-[14px] bg-white/80 p-3">
          <dt className="text-go-muted">
            {t("You picked {day}", { day: dayLabel(date) })}
            {chosenStatus && chosenStatus !== "TOO_EARLY" ? ` · ${t(outlookChip(chosenStatus).label)}` : ""}
          </dt>
          <dd className="mt-0.5 font-medium text-black">
            {others === 0 ? t("No other store booked yet") : t(others === 1 ? "1 other store booked" : "{n} other stores booked", { n: others })}
          </dd>
        </div>
        <div className="rounded-[14px] bg-go-mint/60 p-3">
          <dt className="text-go-ink/70">
            {dayLabel(best.date)}
            {bestStatus === "ON_TRACK" ? ` · ${t("On track")}` : ""}
          </dt>
          <dd className="mt-0.5 font-medium text-black">
            {t(best.stopsBooked === 1 ? "1 other store booked" : "{n} other stores booked", { n: best.stopsBooked })}
            {hint.data?.roomChecked ? t(" · room on the vehicle") : ""}
          </dd>
        </div>
      </dl>

      <p className="text-[13px] leading-snug text-go-muted">
        {t("Your goods would join that trip instead of needing their own, so they are more likely to arrive in your window, and a vehicle is freed for another store.")}
      </p>

      <div className="flex flex-wrap items-center gap-2">
        <button
          type="button"
          onClick={() => pick(best.date)}
          className="min-h-11 rounded-[14px] bg-[#031a0c] px-4 text-[14px] font-medium text-white transition-transform active:scale-[0.97]"
        >
          {t("Move to {day}", { day: dayLabel(best.date) })}
        </button>
        {days.slice(1).map((d) => (
          <button key={d.date} type="button" onClick={() => pick(d.date)} className="min-h-11 rounded-[14px] bg-white px-3.5 text-[13px] font-medium text-black ring-1 ring-go-rule">
            {t("{day} instead", { day: dayLabel(d.date) })}
          </button>
        ))}
        <button type="button" onClick={() => setKept(date)} className="ml-auto min-h-11 px-2 text-[13px] font-medium text-go-teal">
          {t("Keep {day}", { day: dayLabel(date) })}
        </button>
      </div>
      <p className="text-[12px] text-go-muted">
        {hint.data?.roomChecked
          ? t("Room is estimated from your usual order. The dispatcher still confirms the load.")
          : t("Advice only. The dispatcher still confirms the load.")}
      </p>
    </section>
  );
}

function Spark(): React.JSX.Element {
  return (
    <svg aria-hidden viewBox="0 0 24 24" className="size-4" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d="M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z" />
      <path d="M19 15l.8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8z" />
    </svg>
  );
}

function Tick(): React.JSX.Element {
  return (
    <svg aria-hidden viewBox="0 0 24 24" className="size-4 shrink-0" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M5 12.5l4.5 4.5L19 7.5" />
    </svg>
  );
}
