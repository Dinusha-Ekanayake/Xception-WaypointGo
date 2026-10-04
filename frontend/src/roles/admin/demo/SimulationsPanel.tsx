"use client";

import { useState } from "react";
import { useResource } from "@shared/api/useResource";
import { clock } from "@shared/wording/time";
import { Badge, card, field, secondary, primary } from "../access/components";
import { controlSimulations, loadSimulations, startSimulation } from "./data";

// Simulated vehicles (issue #231). Each one drives its released trip from the
// depot through its stops in straight legs, sending real position commands as
// its assigned driver, so the dispatcher, store and driver maps move. Stops are
// still recorded by people: the simulator never records a delivery.

const TONE = { running: "green", paused: "amber", stopped: "neutral", finished: "blue", failed: "red" } as const;

export default function SimulationsPanel({ run, busy }: { run: (label: string, action: () => Promise<unknown>) => Promise<void>; busy: boolean }): React.JSX.Element {
  const sims = useResource(loadSimulations, "admin-demo-simulations", 4_000);
  const [vehicle, setVehicle] = useState("");
  const list = sims.data ?? [];
  const active = list.filter((s) => s.status === "running" || s.status === "paused");

  const act = (label: string, action: (reason: string) => Promise<unknown>) =>
    void run(label, () => action(label)).then(() => sims.refresh());

  return (
    <section className={`${card} flex flex-col gap-4 p-5`}>
      <h2 className="text-lg font-medium text-go-ink">Simulated vehicles</h2>
      <p className="text-sm text-go-secondary">
        Drives released trips of the demo day from the depot through each stop, one point every 2 seconds, on straight legs. Watch them on the dispatcher&rsquo;s live map and the store&rsquo;s Track screen. The driver still records each delivery.
      </p>
      <div className="flex flex-wrap items-end gap-2">
        <button type="button" className={primary} disabled={busy} onClick={() => act("Start every released trip", (r) => startSimulation(r))}>
          Start every released trip
        </button>
        <label className="flex flex-col gap-1 text-sm text-go-secondary">
          One vehicle
          <input className={field} placeholder="Vehicle, for example V001" value={vehicle} onChange={(e) => setVehicle(e.target.value.trim())} />
        </label>
        <button type="button" className={secondary} disabled={busy || vehicle === ""} onClick={() => act(`Start ${vehicle}`, (r) => startSimulation(r, vehicle))}>
          Start this vehicle
        </button>
      </div>
      {active.length > 0 && (
        <div className="flex flex-wrap gap-2">
          <button type="button" className={secondary} disabled={busy} onClick={() => act("Pause all vehicles", (r) => controlSimulations(r, "pause"))}>Pause all</button>
          <button type="button" className={secondary} disabled={busy} onClick={() => act("Resume all vehicles", (r) => controlSimulations(r, "resume"))}>Resume all</button>
          <button type="button" className={secondary} disabled={busy} onClick={() => act("Stop all vehicles", (r) => controlSimulations(r, "stop"))}>Stop all</button>
        </div>
      )}
      {list.length === 0 ? (
        <p className="text-sm text-go-secondary">No simulated vehicles yet. Publish a plan and release a trip first.</p>
      ) : (
        <ul className="flex flex-col gap-2 text-sm">
          {list.slice(0, 15).map((s) => (
            <li key={s.id} className="flex flex-wrap items-center gap-x-3 gap-y-1 text-go-ink">
              <span className="w-20 font-medium">{s.vehicle_id}</span>
              <Badge tone={TONE[s.status]}>{s.status}</Badge>
              <span className="tabular-nums text-go-secondary">point {s.tick} · {s.waypoints - 1} stops · started {clock(s.started_at)}</span>
              {s.failure && <span className="text-[#a3261a]">{s.failure}</span>}
              {s.status === "running" && <button type="button" className={secondary} disabled={busy} onClick={() => act(`Pause ${s.vehicle_id}`, (r) => controlSimulations(r, "pause", s.id))}>Pause</button>}
              {s.status === "paused" && <button type="button" className={secondary} disabled={busy} onClick={() => act(`Resume ${s.vehicle_id}`, (r) => controlSimulations(r, "resume", s.id))}>Resume</button>}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
