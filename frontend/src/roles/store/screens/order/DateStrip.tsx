"use client";

import { useEffect, useRef } from "react";
import { useResource } from "@shared/api/useResource";
import { Notice, Pill, cx } from "@shared/ui";
import type { StoreGateway } from "../../data/gateway.ts";
import { addDays, dayLabel } from "../../data/format.ts";
import { BOOKING_DAYS, needsWarning, outlookChip, suggestDay } from "../../data/outlook.ts";
import { useT } from "../../i18n.tsx";

// Issue #224 (R-ML-07): four weeks of delivery days, each with how likely it is
// to be kept, so "next Friday" can be chosen and a busy day is said before the
// order is sent. Advice only: any day can still be picked, and the plan made the
// afternoon before decides. With the outlook down the days stay choosable and
// the strip says the outlook is missing (rule 9).

export default function DateStrip({
  gateway,
  outletId,
  first,
  date,
  shared,
  onPick,
}: {
  gateway: StoreGateway;
  outletId: string;
  /** The first day still open for ordering. */
  first: string;
  date: string;
  /** Days a trip already serves the district (R-ORD-13), preferred when suggesting. */
  shared: string[];
  onPick: (date: string) => void;
}): React.JSX.Element {
  const t = useT();
  const last = addDays(first, BOOKING_DAYS - 1);
  const dates = Array.from({ length: BOOKING_DAYS }, (_, i) => addDays(first, i));
  const outlook = useResource(outletId ? (s) => gateway.outlook(outletId, first, last, s) : null, `${outletId}|${first}`);
  const days = outlook.data?.days ?? [];
  const chosen = days.find((d) => d.date === date);
  const suggestion = needsWarning(chosen) ? suggestDay(days, date, shared) : null;

  const strip = useRef<HTMLDivElement>(null);
  useEffect(() => {
    strip.current?.querySelector<HTMLElement>("[aria-pressed=true]")?.scrollIntoView?.({ block: "nearest", inline: "nearest" });
  }, [date]);

  return (
    <fieldset className="flex min-w-0 flex-col gap-2">
      <legend className="mb-2 text-[13px] text-go-muted">{t("Delivery day")}</legend>
      <div ref={strip} className="-mx-1 flex gap-2 overflow-x-auto px-1 pb-1">
        {dates.map((d) => {
          const day = days.find((x) => x.date === d);
          const chip = day ? outlookChip(day.status) : null;
          return (
            <button
              key={d}
              type="button"
              aria-pressed={d === date}
              aria-label={chip ? `${dayLabel(d)} · ${t(chip.label)}` : dayLabel(d)}
              onClick={() => onPick(d)}
              className={cx(
                "flex min-h-16 w-[92px] shrink-0 flex-col items-center justify-center gap-1 rounded-[18px] px-2 text-[15px] font-medium",
                d === date ? "bg-[#031a0c] text-white" : "bg-white text-black",
              )}
            >
              {dayLabel(d)}
              {chip && <Pill tone={chip.tone}>{t(chip.label)}</Pill>}
            </button>
          );
        })}
      </div>
      {outlook.error && (
        <p role="status" className="text-[13px] text-go-muted">
          {t("Outlook unavailable right now. Every day can still be chosen.")}
        </p>
      )}
      {chosen && needsWarning(chosen) && (
        <Notice tone="warning" live title={t(chosen.status === "AT_RISK" ? "{day} is at risk: your order may move a day." : "{day} is busy: your order may move a day.", { day: dayLabel(date) })}>
          {chosen.reason}.{" "}
          {suggestion ? (
            <>
              {t("{day} is on track.", { day: dayLabel(suggestion) })}{" "}
              <button type="button" onClick={() => onPick(suggestion)} className="font-medium underline">
                {t("Deliver {day}", { day: dayLabel(suggestion) })}
              </button>
            </>
          ) : (
            t("Dispatch plans each day the afternoon before and tells you at once if it moves.")
          )}
        </Notice>
      )}
    </fieldset>
  );
}
