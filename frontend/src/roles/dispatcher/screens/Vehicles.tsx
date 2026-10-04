"use client";

import { useCallback, useMemo, useState } from "react";
import type { Resource } from "@shared/api/useResource";
import type { VehicleView } from "@shared/domain/types";
import { Card, CardHead, KpiCard, Pending, useToast } from "@shared/ui";
import PageHeader from "../PageHeader.tsx";
import ReeferNeed from "./ReeferNeed.tsx";
import FleetError from "./FleetError.tsx";
import FleetTable, { type RoadStatus } from "./FleetTable.tsx";
import VehicleDrawer from "./VehicleDrawer.tsx";
import { summarise } from "../data/fleet.ts";
import { vehicleDay } from "../data/live.ts";
import { dayLabel } from "../data/scope.ts";
import { useFleetFuel, useLive, usePlans } from "../data/useDay.ts";
import { useSetDayStatus } from "../data/useSetDayStatus.ts";

// Figma "06 Vehicles": today's fleet, where each vehicle is, its trips and its
// week's fuel, and sending a vehicle to the workshop. Container only; the
// table and the drawer are views.

export default function Vehicles({
  depots,
  scopeLabel,
  date,
  fleet,
  online,
}: {
  depots: string[];
  scopeLabel: string;
  /** Today, depot time: the fleet, the road and the plan are read for it. */
  date: string;
  fleet: Resource<VehicleView[]>;
  online: boolean;
}): React.JSX.Element {
  const [openId, setOpenId] = useState<string | null>(null);
  const toast = useToast();
  const { state: submitState, submit, reset } = useSetDayStatus();
  const vehicles = fleet.data ?? [];
  const summary = summarise(vehicles);
  const open = vehicles.find((v) => v.vehicleId === openId) ?? null;
  const live = useLive(depots, date);
  const plans = usePlans(depots, date);
  const ids = useMemo(() => vehicles.map((v) => v.vehicleId), [vehicles]);
  const fuel = useFleetFuel(ids, date);
  const status = useMemo(() => {
    if (!live.data) return null;
    const now = new Date();
    const out: Record<string, RoadStatus> = {};
    for (const sheet of live.data.sheets) {
      const state = vehicleDay(sheet, now).state;
      if (state === "driving" || state === "at-stop") out[sheet.vehicleId] = "road";
    }
    return out;
  }, [live.data]);
  const trips = useMemo(() => {
    if (!plans.data) return null;
    const out: Record<string, number> = {};
    for (const depot of plans.data) for (const trip of depot.published?.trips ?? []) out[trip.vehicleId] = (out[trip.vehicleId] ?? 0) + 1;
    return out;
  }, [plans.data]);
  const onRoad = status ? Object.keys(status).length : null;
  const fuelShares = fuel.data ? Object.values(fuel.data).map((f) => (Number(f.quotaLitres) > 0 ? Number(f.usedLitres) / Number(f.quotaLitres) : 0)) : [];
  const fuelAvg = fuelShares.length ? Math.round((fuelShares.reduce((a, b) => a + b, 0) / fuelShares.length) * 100) : null;
  const overNinety = fuelShares.filter((share) => share > 0.9).length;

  const close = useCallback(() => {
    setOpenId(null);
    reset();
  }, [reset]);

  const sendToWorkshop = async (reason: string) => {
    if (!open) return;
    const ok = await submit({ vehicleId: open.vehicleId, status: "in_workshop", serviceDate: date, reason });
    if (ok) {
      toast({ title: "Workshop request sent", detail: `${open.vehicleId} leaves the next plan run on ${dayLabel(date)}.` });
      setOpenId(null);
      reset();
      fleet.refresh();
    }
  };

  return (
    <>
      <PageHeader
        title="Vehicles"
        subtitle={`${fleet.data ? `${summary.total} available` : "Loading"} · ${scopeLabel} · ${dayLabel(date)}`}
        online={online}
        lastSyncedAt={fleet.loadedAt}
        onSync={fleet.refresh}
        syncing={fleet.loading}
      />

      <div className="flex w-full gap-3.5 max-md:flex-col">
        <KpiCard label="Available today" value={fleet.data ? summary.total : "…"} note={onRoad === null ? "for planning today" : `${onRoad} on the road · ${summary.total - onRoad} at depot`} />
        <KpiCard label="In workshop" value="-" valueClassName="text-go-warning-text" note="Not listed yet" />
        <KpiCard label="Refrigerated available" value={fleet.data ? `${summary.refrigerated} / ${summary.total}` : "…"} note="vehicles and vans" />
        <KpiCard
          label="Fuel quota used"
          value={fuelAvg === null ? "…" : `${fuelAvg}%`}
          valueClassName="text-go-teal"
          note={fuelAvg === null ? "reading the week's plans" : `fleet average${overNinety ? ` · ${overNinety} over 90%` : ""} · planned this week`}
        />
      </div>

      <div className="flex min-h-0 w-full flex-1 gap-[18px] max-lg:flex-col">
        <section aria-label="Fleet" className="flex min-w-0 flex-1 flex-col rounded-go-panel bg-go-card px-5 pt-3.5 pb-3">
          {fleet.error && <FleetError error={fleet.error} onRetry={fleet.refresh} />}
          {fleet.data ? (
            <FleetTable fleet={vehicles} status={status} trips={trips} fuel={fuel.data} onOpen={setOpenId} />
          ) : (
            !fleet.error && <p className="py-6 text-center text-[13px] text-go-secondary">Loading the fleet…</p>
          )}
        </section>

        <div className="flex w-full flex-col gap-[18px] lg:w-[360px] lg:shrink-0">
          <Card label="Back in service">
            <CardHead title="Back in service" meta="Vehicles in the workshop and when they return" />
            <Pending what="the workshop list" waitingOn="Vehicles in the workshop are left out of the counts on this screen; they are not listed yet." />
          </Card>
          <Card label="Refrigerated vehicles">
            <CardHead title="Refrigerated vehicles" meta="Available vs needed a day · next 4 weeks" />
            <ReeferNeed depots={depots} available={fleet.data ? summary.refrigerated : null} />
          </Card>
        </div>
      </div>

      {open && (
        <VehicleDrawer
          vehicle={open}
          serviceDate={date}
          online={online}
          submitState={submitState}
          onSendToWorkshop={sendToWorkshop}
          onClose={close}
        />
      )}
    </>
  );
}
