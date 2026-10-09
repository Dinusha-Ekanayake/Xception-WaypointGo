"use client";

import { useEffect, useRef, useState } from "react";
import { ApiError, friendlyError } from "@shared/api/problem";
import type { VehicleView } from "@shared/domain/types";
import { Icon, Notice, Pending, Pill, PrimaryButton, SecondaryButton } from "@shared/ui";
import { capacityLabel, litres, typeLabel } from "../data/fleet.ts";
import { dayLabel } from "../data/scope.ts";
import { addDays } from "@shared/wording";
import type { SubmitState } from "../data/useSetDayStatus.ts";
import FuelWeek from "./FuelWeek.tsx";
import { clashText, workshopClash } from "../data/forecast.ts";
import { useForecast } from "../data/useForecast.ts";

// Figma "06b Vehicle details": the drawer over the fleet table. Its one write
// is Send to workshop, which is vehicle:SetDayStatus for the day being viewed.
// A decision carries a reason (architecture rule 8), so the button asks for one
// before it sends.

export default function VehicleDrawer({
  vehicle,
  serviceDate,
  online,
  submitState,
  onSendToWorkshop,
  onClose,
}: {
  vehicle: VehicleView;
  serviceDate: string;
  online: boolean;
  submitState: SubmitState;
  onSendToWorkshop: (reason: string, day: string) => void;
  onClose: () => void;
}): React.JSX.Element {
  const [confirming, setConfirming] = useState(false);
  const [reason, setReason] = useState("");
  // Today by default; a later day can be booked ahead, where the forecast can warn (T2-02).
  const [day, setDay] = useState(serviceDate);
  const closeRef = useRef<HTMLButtonElement>(null);
  const sending = submitState.phase === "sending";
  // Roadmap T2-02: read the depot's forecast only once a workshop day is being chosen.
  const forecast = useForecast(confirming ? [vehicle.depotCode] : []);
  const clash = forecast.data ? workshopClash(forecast.data, vehicle, day) : null;

  useEffect(() => {
    closeRef.current?.focus();
    const onKey = (event: KeyboardEvent) => event.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <div className="fixed inset-0 z-40 flex justify-end">
      <button type="button" aria-label="Close vehicle details" onClick={onClose} className="absolute inset-0 animate-fade-in bg-black/30" />
      <aside
        role="dialog"
        aria-modal="true"
        aria-label={`Vehicle ${vehicle.vehicleId}`}
        className="relative flex h-full w-full max-w-[460px] animate-slide-in-end flex-col gap-4 overflow-y-auto overscroll-contain rounded-l-[32px] bg-white p-7 shadow-[-10px_0_40px_0_rgba(0,0,0,0.12)]"
      >
        <div className="flex items-start gap-2.5">
          <div className="flex min-w-0 flex-1 flex-col gap-1">
            <div className="flex flex-wrap items-center gap-2">
              <h2 className="text-2xl font-medium tracking-normal text-go-ink">{vehicle.vehicleId}</h2>
              <Pill tone={vehicle.refrigerated ? "info" : "muted"} icon={vehicle.refrigerated ? "snowflake" : undefined}>
                {typeLabel(vehicle)} vehicle
              </Pill>
              <Pill tone="success">Available {dayLabel(serviceDate)}</Pill>
            </div>
            <p className="text-[13px] text-go-secondary">
              {capacityLabel(vehicle)} · {Number(vehicle.kmPerL)} km/L · {vehicle.depotCode}
            </p>
          </div>
          <button ref={closeRef} type="button" onClick={onClose} aria-label="Close" className="flex rounded-go-card bg-go-surface p-[9px]">
            <Icon name="close" />
          </button>
        </div>

        <section className="flex flex-col gap-2">
          <div className="flex items-center gap-1.5">
            <Icon name="gas-pump" />
            <h3 className="flex-1 text-[13px] font-semibold tracking-normal text-go-ink">
              Fuel quota · {litres(vehicle.weeklyFuelQuotaL)} a week
            </h3>
            <span className="text-[11px] text-go-secondary">includes the return leg (D-K)</span>
          </div>
          <FuelWeek vehicleId={vehicle.vehicleId} date={serviceDate} />
        </section>

        <div className="flex gap-2">
          <Fact label="Weight capacity" value={`${Number(vehicle.weightCapKg).toLocaleString("en-US")} kg`} />
          <Fact label="Volume capacity" value={`${Number(vehicle.volumeCapM3).toFixed(1)} m³`} />
          <Fact label="Fuel economy" value={`${Number(vehicle.kmPerL)} km/L`} />
        </div>

        <section className="flex flex-col gap-2">
          <h3 className="text-[13px] font-semibold tracking-normal text-go-ink">This week</h3>
          <Pending what="This vehicle's trips" waitingOn="Its trips are on the Plan screen for the day." />
        </section>

        {confirming && (
          <form
            className="flex flex-col gap-2 rounded-go-card-s bg-go-surface p-3"
            onSubmit={(event) => {
              event.preventDefault();
              if (reason.trim() && day) onSendToWorkshop(reason.trim(), day);
            }}
          >
            <label htmlFor="workshop-day" className="text-[13px] font-medium text-go-ink">
              Which day?
            </label>
            <input
              id="workshop-day"
              type="date"
              required
              min={serviceDate}
              max={addDays(serviceDate, 70)}
              value={day}
              onChange={(event) => setDay(event.target.value)}
              className="rounded-go-input bg-white px-3 py-2 text-sm text-go-ink outline-none"
            />
            <label htmlFor="workshop-reason" className="text-[13px] font-medium text-go-ink">
              Why is {vehicle.vehicleId} going to the workshop on {day ? dayLabel(day) : "that day"}?
            </label>
            <textarea
              id="workshop-reason"
              required
              rows={2}
              value={reason}
              onChange={(event) => setReason(event.target.value)}
              placeholder="For example: brake noise reported by the driver"
              className="rounded-go-input bg-white px-3 py-2 text-sm text-go-ink outline-none placeholder:text-go-placeholder"
            />
            {clash && (
              <Notice tone="warning" title="That week is forecast short" live>
                {clashText(vehicle.vehicleId, clash)} You can still send it; consider another day or a hired vehicle.
              </Notice>
            )}
            <p className="text-[11px] text-go-secondary">
              It leaves the next planning run for that day. Plans already made stay as they are (R-FLT-04).
            </p>
            <div className="flex gap-2">
              <PrimaryButton type="submit" disabled={!online || !reason.trim()} busy={sending}>
                {sending ? "Sending…" : `Send ${vehicle.vehicleId} to workshop`}
              </PrimaryButton>
              <SecondaryButton onClick={() => setConfirming(false)} disabled={sending}>
                Cancel
              </SecondaryButton>
            </div>
          </form>
        )}

        {submitState.phase === "failed" && <SubmitError error={submitState.error} />}

        <div className="flex-1" />
        {!online && <Notice tone="warning" title="Offline: changes are turned off until the connection returns." />}
        {!confirming && (
          <div className="flex flex-wrap gap-2.5">
            <SecondaryButton icon="wrench-outline" onClick={() => setConfirming(true)} disabled={!online}>
              Send to workshop
            </SecondaryButton>
            <PrimaryButton icon="board" disabled title="The plan board arrives with Planning (#9)">
              View in plan
            </PrimaryButton>
          </div>
        )}
      </aside>
    </div>
  );
}

function Fact({ label, value }: { label: string; value: string }): React.JSX.Element {
  return (
    <div className="flex min-w-0 flex-1 flex-col gap-px rounded-go-tile bg-go-surface px-2.5 py-2">
      <p className="text-[11px] text-go-secondary">{label}</p>
      <p className="text-[15px] font-medium text-go-ink">{value}</p>
    </div>
  );
}

function SubmitError({ error }: { error: Error }): React.JSX.Element {
  if (error instanceof ApiError) {
    const rules = error.problem.violations.map((v) => v.rule);
    return (
      <Notice tone="danger" title={error.status === 403 ? "You are not allowed to change this vehicle" : "The change was refused"} live>
        {friendlyError(error)}
        {rules.length > 0 && ` Rule ${rules.join(", ")}.`}
      </Notice>
    );
  }
  return (
    <Notice tone="danger" title="The change did not reach Waypoint" live>
      Check the connection and send again. Sending again is safe: Waypoint applies it once.
    </Notice>
  );
}
