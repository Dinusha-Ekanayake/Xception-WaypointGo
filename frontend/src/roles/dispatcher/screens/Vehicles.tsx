"use client";

import { useCallback, useState } from "react";
import type { Resource } from "@shared/api/useResource";
import type { VehicleView } from "@shared/domain/types";
import { Card, CardHead, KpiCard, Notice, Pending } from "@shared/ui";
import PageHeader from "../PageHeader.tsx";
import FleetError from "./FleetError.tsx";
import FleetTable from "./FleetTable.tsx";
import VehicleDrawer from "./VehicleDrawer.tsx";
import { litres, summarise } from "../data/fleet.ts";
import { formatDay } from "../data/scope.ts";
import { useSetDayStatus } from "../data/useSetDayStatus.ts";

// Figma "06 Vehicles": the fleet for a day, and sending a vehicle to the
// workshop. Container only; the table and the drawer are views.

export default function Vehicles({
  scopeLabel,
  date,
  onDate,
  fleet,
  online,
}: {
  scopeLabel: string;
  date: string;
  onDate: (date: string) => void;
  fleet: Resource<VehicleView[]>;
  online: boolean;
}): React.JSX.Element {
  const [openId, setOpenId] = useState<string | null>(null);
  const [confirmation, setConfirmation] = useState<string | null>(null);
  const { state: submitState, submit, reset } = useSetDayStatus();
  const vehicles = fleet.data ?? [];
  const summary = summarise(vehicles);
  const open = vehicles.find((v) => v.vehicleId === openId) ?? null;

  const close = useCallback(() => {
    setOpenId(null);
    reset();
  }, [reset]);

  const sendToWorkshop = async (reason: string) => {
    if (!open) return;
    const ok = await submit({ vehicleId: open.vehicleId, status: "in_workshop", serviceDate: date, reason });
    if (ok) {
      setConfirmation(`${open.vehicleId} is in the workshop on ${formatDay(date)} and leaves that day's next plan run.`);
      setOpenId(null);
      reset();
      fleet.refresh();
    }
  };

  return (
    <>
      <PageHeader
        title="Vehicles"
        subtitle={`${fleet.data ? `${summary.total} available` : "Loading"} · ${scopeLabel} · ${formatDay(date)}`}
        online={online}
        lastSyncedAt={fleet.loadedAt}
        onSync={fleet.refresh}
        syncing={fleet.loading}
        tools={
          <label className="flex shrink-0 items-center gap-1.5 rounded-[20px] bg-white px-3.5 py-2.5 text-[13px] font-medium text-go-ink">
            <span className="text-go-secondary">Day</span>
            <input
              type="date"
              value={date}
              onChange={(event) => event.target.value && onDate(event.target.value)}
              className="bg-transparent outline-none"
            />
          </label>
        }
      />

      {confirmation && (
        <Notice
          tone="info"
          title={confirmation}
          live
          action={
            <button type="button" onClick={() => setConfirmation(null)} className="shrink-0 text-[11px] font-medium text-go-teal">
              Dismiss
            </button>
          }
        />
      )}

      <div className="flex w-full gap-3.5 max-md:flex-col">
        <KpiCard label="Available" value={fleet.data ? summary.total : "…"} note="for planning on this day" />
        <KpiCard
          label="Refrigerated available"
          value={fleet.data ? `${summary.refrigerated} / ${summary.total}` : "…"}
          note="carry chilled or ambient, one per trip"
        />
        <KpiCard label="Vans available" value={fleet.data ? summary.vans : "…"} note="for van-only outlets" />
        <KpiCard
          label="Weekly fuel quota"
          value={fleet.data ? litres(summary.weeklyQuotaL) : "…"}
          valueClassName="text-go-teal"
          note="use so far waits on Execution (#12)"
        />
      </div>

      <div className="flex min-h-0 w-full flex-1 gap-[18px] max-lg:flex-col">
        <section aria-label="Fleet" className="flex min-w-0 flex-1 flex-col rounded-[24px] bg-white px-5 pt-3.5 pb-2 shadow-go-card">
          {fleet.error && <FleetError error={fleet.error} onRetry={fleet.refresh} />}
          {fleet.data ? (
            <FleetTable fleet={vehicles} onOpen={setOpenId} />
          ) : (
            !fleet.error && <p className="py-6 text-center text-[13px] text-go-secondary">Loading the fleet…</p>
          )}
        </section>

        <div className="flex min-w-[280px] flex-col gap-[18px] lg:max-w-[300px]">
          <Card label="Back in service">
            <CardHead title="Back in service" meta="Vehicles in the workshop" />
            <Pending what="The workshop list" waitingOn="a fleet status read in reference data" />
          </Card>
          <Card label="Refrigerated vehicles">
            <CardHead title="Refrigerated vehicles" meta="Available against needed, next 6 days" />
            <Pending what="Refrigerated demand" waitingOn="the forecast (#16)" />
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
