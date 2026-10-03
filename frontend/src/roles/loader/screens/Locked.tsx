"use client";

import { useResource } from "@shared/api/useResource";
import type { ReadyTripView } from "@shared/domain/types";
import type { Operator } from "@app-shell/session";
import type { LoadingGateway } from "../data/gateway.ts";
import { hhmm, progress } from "../data/manifest.ts";
import { useT } from "../i18n.tsx";
import { LockIcon, SwapIcon, TruckIcon } from "../icons.tsx";
import { BigButton, initials } from "../ui.tsx";

// Figma "08 Loader · Phone" 08 Device locked (rationale 08): the device is
// protected, the trip and its progress stay visible, the same loader resumes
// with their PIN, and anyone else switches user under their own name.

export default function Locked({
  gateway,
  operator,
  depot,
  trip,
  onUnlock,
  onSwitch,
}: {
  gateway: LoadingGateway;
  /** The loader who locked the device. */
  operator: Operator;
  depot: string;
  /** The trip they hold, if any. */
  trip: ReadyTripView | null;
  onUnlock: () => void;
  onSwitch: () => void;
}): React.JSX.Element {
  const tr = useT();
  const manifest = useResource(trip ? (signal) => gateway.manifest(trip.tripId, signal) : null, trip?.tripId ?? "none");
  const p = manifest.data ? progress(manifest.data.lines) : null;
  const first = operator.displayName.trim().split(/\s+/)[0] ?? operator.displayName;
  const progressLine = [
    p && tr("{a} of {b} orders loaded", { a: p.checked, b: p.total }),
    p && p.flagged > 0 && tr(p.flagged === 1 ? "{n} issue reported" : "{n} issues reported", { n: p.flagged }),
    trip && tr("departs {time}", { time: hhmm(trip.plannedDeparture) }),
  ].filter(Boolean).join(" · ");

  return (
    <main className="flex w-full flex-1 flex-col items-center gap-5 px-4 pt-6 pb-8 md:mx-auto md:max-w-[560px]">
      <span className="flex size-[72px] items-center justify-center rounded-full bg-go-soft text-go-on-soft"><LockIcon size={26} /></span>
      <div className="flex flex-col items-center gap-2 text-center">
        <h1 className="text-[34px] leading-tight font-semibold text-go-ink">{tr("Device locked")}</h1>
        <p className="text-[15px] text-go-muted">
          {trip ? tr("Locked by the loader on this trip. Work is saved.") : tr("Locked by the loader. Work is saved.")}
        </p>
      </div>
      <section className="flex w-full flex-col rounded-go-panel bg-go-card px-5 py-4 text-go-ink shadow-go-card">
        <div className="flex items-center gap-4 pb-3">
          <span className="flex size-12 shrink-0 items-center justify-center rounded-full bg-go-soft text-[16px] font-medium text-go-on-soft">
            {initials(operator.displayName)}
          </span>
          <div className="flex min-w-0 flex-col">
            <span className="truncate text-[24px] font-medium">{operator.displayName}</span>
            <span className="text-[13px] text-go-muted">
              {[tr("Loader"), operator.employeeCode, depot].filter(Boolean).join(" · ")}
            </span>
          </div>
        </div>
        {trip && (
          <div className="flex items-center gap-4 border-t border-go-rule pt-3">
            <span className="flex size-14 shrink-0 items-center justify-center rounded-[16px] bg-go-soft text-go-on-soft"><TruckIcon /></span>
            <div className="flex min-w-0 flex-col">
              <span className="text-[18px] font-medium">
                {[trip.vehicleId, trip.brandCode, tr(trip.stopCount === 1 ? "{n} stop" : "{n} stops", { n: trip.stopCount }), trip.districtName].join(" · ")}
              </span>
              <span className="text-[13px] text-go-muted">{progressLine}</span>
            </div>
          </div>
        )}
      </section>
      <BigButton size="l" icon="arrow-right" onClick={onUnlock}>
        {tr("Enter PIN to unlock")}
      </BigButton>
      <div className="mt-auto flex items-center gap-3 pt-10">
        <span className="text-[16px] text-go-muted">{tr("Not {name}?", { name: first })}</span>
        <button type="button" onClick={onSwitch} className="flex min-h-12 items-center gap-2 rounded-full bg-go-card px-5 text-[16px] text-go-ink shadow-go-float">
          <SwapIcon /> {tr("Switch user")}
        </button>
      </div>
    </main>
  );
}
