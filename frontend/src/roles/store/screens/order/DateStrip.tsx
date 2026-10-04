"use client";

import { useEffect, useRef } from "react";
import { useResource } from "@shared/api/useResource";
import { Notice, cx } from "@shared/ui";
import type { Tone } from "@shared/ui/primitives";
import type { OutlookStatus } from "@shared/domain/types";
import type { StoreGateway } from "../../data/gateway.ts";
import { addDays, dayLabel, longDay } from "../../data/format.ts";
import { BOOKING_DAYS, needsWarning, outlookChip, suggestDay } from "../../data/outlook.ts";
import { useT } from "../../i18n.tsx";

// Issue #224 (R-ML-07): four weeks of delivery days, each with how likely it is
// to be kept, so "next Friday" can be chosen and a busy day is said before the
// order is sent. Advice only: any day can still be picked, and the plan made the
// afternoon before decides. With the outlook down the days stay choosable and
// the strip says the outlook is missing (rule 9).

/** The dot under each day, one colour per outlook tone. */
const DOT: Partial<Record<Tone, string>> = {
  success: "bg-go-success",
  warning: "bg-go-warning",
  danger: "bg-go-danger",
  muted: "bg-go-offline",
};

const KEY: OutlookStatus[] = ["ON_TRACK", "BUSY", "AT_RISK", "CLOSED"];

/** "Sat 10 Oct" as its three parts, so a tile can set each on its own line. */
function parts(date: string): { weekday: string; day: string; month: string } {
  const [weekday = "", day = "", month = ""] = dayLabel(date).split(" ");
  return { weekday, day, month };
}

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
  const chosenChip = chosen ? outlookChip(chosen.status) : null;
  const suggestion = needsWarning(chosen) ? suggestDay(days, date, shared) : null;

  const strip = useRef<HTMLDivElement>(null);
  useEffect(() => {
    strip.current?.querySelector<HTMLElement>("[aria-pressed=true]")?.scrollIntoView?.({ block: "nearest", inline: "nearest" });
  }, [date]);

  /** Moves the strip by most of its visible width, about a week of days. */
  const scroll = (direction: 1 | -1) => {
    const el = strip.current;
    el?.scrollBy?.({ left: direction * el.clientWidth * 0.85, behavior: "smooth" });
  };

  return (
    <div className="flex min-w-0 flex-col gap-3">
      <div className="flex items-end justify-between gap-3">
        <div className="min-w-0">
          <p aria-hidden="true" className="text-[13px] text-go-muted">
            {t("Delivery day")}
          </p>
          <p className="truncate text-[17px] font-semibold text-go-ink">
            {longDay(date)}
            {chosenChip && <span className="font-normal text-go-muted"> · {t(chosenChip.label)}</span>}
          </p>
        </div>
        <div className="flex shrink-0 gap-1.5">
          <ArrowButton label={t("Earlier days")} direction={-1} onClick={() => scroll(-1)} />
          <ArrowButton label={t("Later days")} direction={1} onClick={() => scroll(1)} />
        </div>
      </div>

      {/* The arrows sit outside the group, so it holds only the days. */}
      <fieldset className="min-w-0">
        <legend className="sr-only">{t("Delivery day")}</legend>
        <div ref={strip} className="-mx-1 flex snap-x gap-2 overflow-x-auto scroll-smooth px-1 pt-0.5 pb-1.5 [scrollbar-width:none]">
          {dates.map((d, i) => {
            const day = days.find((x) => x.date === d);
            const chip = day ? outlookChip(day.status) : null;
            const selected = d === date;
            const closed = day?.status === "CLOSED";
            const { weekday, day: number, month } = parts(d);
            const showMonth = i === 0 || number === "1";
            return (
              <button
                key={d}
                type="button"
                aria-pressed={selected}
                aria-label={chip ? `${dayLabel(d)} · ${t(chip.label)}` : dayLabel(d)}
                onClick={() => onPick(d)}
                className={cx(
                  "flex h-[88px] w-[62px] shrink-0 snap-start flex-col items-center justify-center gap-1 rounded-go-tile border transition-colors",
                  "focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-go-teal",
                  selected
                    ? "border-go-action bg-go-action text-go-on-action shadow-go-float"
                    : closed
                      ? "border-transparent bg-go-subtle text-go-placeholder hover:border-go-rule"
                      : "border-go-rule bg-go-card text-go-ink hover:border-go-teal",
                )}
              >
                <span className={cx("text-[11px] font-medium uppercase tracking-[0.08em]", selected ? "text-go-on-action/75" : "text-go-secondary")}>
                  {weekday}
                </span>
                <span className="text-[22px] font-semibold leading-none tabular-nums">{number}</span>
                <span
                  className={cx(
                    "text-[11px] leading-none",
                    selected ? "text-go-on-action/75" : "text-go-secondary",
                    !showMonth && "invisible",
                  )}
                >
                  {month}
                </span>
                <span
                  aria-hidden="true"
                  className={cx("mt-0.5 size-1.5 rounded-full", chip ? DOT[chip.tone] : "bg-transparent", selected && chip && "ring-2 ring-go-on-action/50")}
                />
              </button>
            );
          })}
        </div>
      </fieldset>

      {outlook.error ? (
        <p role="status" className="text-[13px] text-go-muted">
          {t("Outlook unavailable right now. Every day can still be chosen.")}
        </p>
      ) : (
        <ul aria-label={t("Outlook key")} className="flex flex-wrap gap-x-4 gap-y-1 text-[12px] text-go-secondary">
          {KEY.map((status) => {
            const chip = outlookChip(status);
            return (
              <li key={status} className="flex items-center gap-1.5">
                <span aria-hidden="true" className={cx("size-1.5 rounded-full", DOT[chip.tone])} />
                {t(chip.label)}
              </li>
            );
          })}
        </ul>
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
    </div>
  );
}

function ArrowButton({ label, direction, onClick }: { label: string; direction: 1 | -1; onClick: () => void }): React.JSX.Element {
  return (
    <button
      type="button"
      aria-label={label}
      onClick={onClick}
      className="grid size-9 place-items-center rounded-full border border-go-rule bg-go-card text-go-ink hover:border-go-teal focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-go-teal"
    >
      <svg
        aria-hidden="true"
        viewBox="0 0 16 16"
        className={cx("size-4", direction === -1 && "rotate-180")}
        fill="none"
        stroke="currentColor"
        strokeWidth="1.8"
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        <path d="M6 3.5 10.5 8 6 12.5" />
      </svg>
    </button>
  );
}
