"use client";
import { useState } from "react";
import {
  Badge,
  Btn,
  Empty,
  NoteForm,
  fmtMinutes,
  inputClass,
} from "./components";
import type { DriverLoaderShared, Order } from "../lib/types";
const loadedStates = [
  "loaded",
  "departed",
  "arrived",
  "delivered",
  "partial",
  "failed",
  "confirmed",
  "disputed",
];
export default function Loader({
  state,
  orders,
  routes,
  busy,
  onDetail,
  queue,
  doAction,
}: DriverLoaderShared) {
  const [selected, setSelected] = useState("all");
  const [flag, setFlag] = useState<Order | null>(null);
  const sorted = [...routes].sort(
    (a, b) =>
      b.day.localeCompare(a.day) ||
      a.start - b.start ||
      a.vehicle_id.localeCompare(b.vehicle_id),
  );
  const shown = sorted.filter(
    (r) => selected === "all" || `${r.id}|${r.day}` === selected,
  );
  return (
    <main className="mx-auto max-w-5xl p-4 pb-12">
      <header className="mb-5">
        <p className="m-0 text-copy text-muted">{state.user.scope} dock</p>
        <h1 className="mb-1 mt-2 text-3xl font-semibold">
          Check the load. Clear the departure.
        </h1>
        <p className="m-0 text-copy text-muted">
          Last stop goes deepest. Every order must be checked before the driver
          can leave.
        </p>
      </header>
      <label className="mb-5 block text-copy font-medium">
        Show trip
        <select
          value={selected}
          onChange={(e) => setSelected(e.target.value)}
          className={`${inputClass} mt-2 w-full`}
        >
          <option value="all">All assigned trips</option>
          {sorted.map((r) => (
            <option key={`${r.id}|${r.day}`} value={`${r.id}|${r.day}`}>
              {r.day} · {r.id} · {r.vehicle_id}
            </option>
          ))}
        </select>
      </label>
      {!shown.length && (
        <Empty title="No manifests yet">
          Published trips for your depot will appear here.
        </Empty>
      )}
      <div className="space-y-4">
        {shown.map((r) => {
          const stops = r.order_ids
            .map((id) => orders.find((o) => o.id === id && o.day === r.day))
            .filter((o): o is Order => !!o)
            .reverse();
          const loaded = stops.filter((o) =>
            loadedStates.includes(o.status),
          ).length;
          const pending = queue.some((q) =>
            r.order_ids.includes(String(q.command.order_id)),
          );
          const ready = loaded === stops.length && stops.length > 0 && !pending;
          return (
            <section
              key={`${r.id}|${r.day}`}
              className="overflow-hidden rounded-2xl border border-line bg-white"
            >
              <div className="flex flex-wrap items-center justify-between gap-3 border-b border-line bg-accentbg p-4">
                <div>
                  <h2 className="m-0 font-mono text-lg font-semibold">
                    {r.vehicle_id}
                  </h2>
                  <p className="mb-0 mt-1 text-caption text-muted">
                    {r.day} · {r.id} · Departure {fmtMinutes(r.start)}
                  </p>
                </div>
                <span
                  className={`rounded-full px-3 py-2 text-caption font-semibold ${ready ? "bg-accent text-white" : "bg-white text-ink"}`}
                >
                  {ready
                    ? "Ready for departure"
                    : pending
                      ? "Checks saved on this device"
                      : `${loaded}/${stops.length} checked`}
                </span>
              </div>
              <ol className="m-0 list-none p-4">
                {stops.map((o, i) => {
                  const local = queue.some((q) => q.command.order_id === o.id);
                  return (
                    <li
                      key={o.id}
                      data-testid={`load-${o.id}`}
                      className="border-b border-line py-4 first:pt-0 last:border-0 last:pb-0"
                    >
                      <div className="flex items-start justify-between gap-2">
                        <div>
                          <p className="m-0 text-caption font-medium text-muted">
                            {i === 0 ? "Load first" : "Then load"} · Delivery
                            stop {o.sequence}
                          </p>
                          <h3 className="mb-1 mt-1 text-copy font-semibold">
                            {o.outlet_id} · {o.brand} {o.district}
                          </h3>
                          <p className="m-0 font-mono text-caption text-muted">
                            {o.id}
                          </p>
                        </div>
                        <Badge status={o.status} />
                      </div>
                      <p className="my-3 text-copy">
                        <strong>{o.units} cases</strong> · {o.weight} kg ·{" "}
                        {o.volume} m³ · {o.temp}
                      </p>
                      {o.status === "shortfall" && (
                        <p className="rounded-xl bg-warnbg p-3 text-copy text-warn">
                          Departure blocked: {o.shortfall?.count} cases missing
                          or damaged. {o.shortfall?.note}
                        </p>
                      )}
                      {o.status === "planned" && o.resolution && (
                        <p className="rounded-xl bg-accentbg p-3 text-copy">
                          Replacement recorded: {o.resolution.note}. Check the
                          complete load again.
                        </p>
                      )}
                      {local && (
                        <p className="text-caption text-warn">
                          Saved locally. The driver cannot rely on this check
                          until it synchronizes.
                        </p>
                      )}
                      <div className="flex flex-wrap gap-2">
                        {o.status === "planned" && !local && (
                          <Btn
                            variant="primary"
                            disabled={busy}
                            onClick={() => doAction("load", o)}
                            aria-label={`Mark loaded ${o.id}`}
                          >
                            Mark loaded
                          </Btn>
                        )}
                        {["planned", "loaded"].includes(o.status) && !local && (
                          <Btn
                            disabled={busy}
                            onClick={() => setFlag(o)}
                            aria-label={`Flag shortfall ${o.id}`}
                          >
                            Flag shortfall
                          </Btn>
                        )}
                        <Btn size="sm" onClick={() => onDetail(o)}>
                          History
                        </Btn>
                      </div>
                    </li>
                  );
                })}
              </ol>
            </section>
          );
        })}
      </div>
      {flag && (
        <NoteForm
          title="Flag loading shortfall"
          label="What is missing or damaged? Departure stays blocked until replacement and recheck."
          count
          max={flag.units}
          busy={busy}
          onClose={() => setFlag(null)}
          onSubmit={async (d) => {
            if (
              await doAction("shortfall", flag, {
                note: d.note,
                count: d.count,
              })
            )
              setFlag(null);
          }}
        />
      )}
    </main>
  );
}
