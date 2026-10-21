"use client";

import type { SharedDaysView } from "@shared/domain/types";
import { addDays, dayLabel } from "../../data/format.ts";
import { useT } from "../../i18n.tsx";

// R-ORD-17: what the bookings already on the strip mean for the day picked.
// When other stores of the brand in the district, or this store itself, have an
// order of the same temperature that day, a trip is likely to go there anyway
// and this order to ride on it. When the day picked has none but the day before
// or after has some, that is said too. Advice only: it never moves the order,
// and the dispatcher plans the trips.

export default function SharedDayNote({
  shared,
  error,
  date,
  brand,
  district,
  temperature,
}: {
  shared: SharedDaysView | null;
  error: boolean;
  date: string;
  brand: string;
  district: string;
  temperature: string;
}): React.JSX.Element | null {
  const t = useT();
  // Degrade visibly (rule 9): say the counts are missing rather than show nothing.
  if (error) {
    return (
      <p role="status" className="text-[13px] text-go-muted">
        {t("Shared trip counts are unavailable right now.")}
      </p>
    );
  }
  if (!shared) return null;
  const on = (d: string) => shared.days.find((x) => x.date === d);
  const chosen = on(date);
  const temp = t(temperature);
  // The brand as the data names it ("Fresh"), whatever case the code arrives in.
  const brandName = brand.charAt(0).toUpperCase() + brand.slice(1).toLowerCase();

  if (chosen && (chosen.otherStores > 0 || chosen.ownOrder)) {
    const others =
      chosen.otherStores === 0
        ? null
        : chosen.otherStores === 1
          ? t("1 other {brand} store in {district} already has {temp} goods booked for {day}.", { brand: brandName, district, temp, day: dayLabel(date) })
          : t("{n} other {brand} stores in {district} already have {temp} goods booked for {day}.", { n: chosen.otherStores, brand: brandName, district, temp, day: dayLabel(date) });
    return (
      <section
        aria-label={t("Likely to share a trip")}
        className="flex flex-col gap-1 rounded-[14px] bg-go-success-tint px-3.5 py-3 text-[13px] leading-snug text-go-ink"
      >
        <p className="font-medium text-go-success">{t("Likely to share a trip")}</p>
        <p>
          {chosen.ownOrder && <>{t("You already have {temp} goods booked for {day}.", { temp, day: dayLabel(date) })} </>}
          {others && <>{others} </>}
          {t("This order is likely to travel on the same trip.")}
        </p>
        <p className="text-[12px] text-go-muted">{t("Advice only. The dispatcher still confirms the load.")}</p>
      </section>
    );
  }

  // The day picked has none: name the busier of the day before and after.
  const near = [addDays(date, -1), addDays(date, 1)]
    .map((d) => ({ d, booked: (on(d)?.otherStores ?? 0) + (on(d)?.ownOrder ? 1 : 0) }))
    .filter((x) => x.booked > 0)
    .sort((a, b) => b.booked - a.booked)[0];
  if (!near) return null;
  return (
    <p role="status" className="rounded-[14px] bg-go-subtle px-3.5 py-2.5 text-[13px] leading-snug text-go-muted">
      {t("No other store has booked {day} yet. {other} already has {n} booked for {district}, so an order then is more likely to share a trip.", {
        day: dayLabel(date),
        other: dayLabel(near.d),
        n: near.booked,
        district,
      })}
    </p>
  );
}
