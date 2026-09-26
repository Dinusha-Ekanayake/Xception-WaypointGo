"use client";
import { useEffect, useState } from "react";
import { api } from "./storage";
import { Btn, Modal } from "./components";
import type { ActFn, AssignmentOption, Order, Plan } from "../lib/types";

export default function AssignmentReview({
  order,
  plan,
  act,
  busy,
  onClose,
}: {
  order: Order;
  plan: Plan;
  act: ActFn;
  busy: boolean;
  onClose: () => void;
}) {
  const [options, setOptions] = useState<AssignmentOption[] | null>(null);
  const [error, setError] = useState("");
  useEffect(() => {
    let active = true;
    api<AssignmentOption[]>(
      `assignments?day=${encodeURIComponent(plan.day)}&order_id=${encodeURIComponent(order.id)}`,
    )
      .then((rows) => {
        if (active) setOptions(rows);
      })
      .catch((e) => {
        if (active) setError(e.message);
      });
    return () => {
      active = false;
    };
  }, [plan.day, plan.revision, order.id]);
  const feasible = options?.filter((o) => o.feasible) || [];
  const rejected = options?.filter((o) => !o.feasible) || [];
  return (
    <Modal title="Review assignment" onClose={onClose}>
      <p className="mt-0 text-copy text-muted">
        {order.id} · {order.brand} {order.district} · {order.weight} kg ·{" "}
        {order.volume} m³
      </p>
      <p className="rounded-xl bg-accentbg p-3 text-copy">
        Every alternative checks temperature, depot, access, load limits,
        receiving windows, both trips and weekly fuel. Nothing changes until you
        apply.
      </p>
      {error && (
        <p role="alert" className="text-bad">
          {error}
        </p>
      )}
      {!options && !error && (
        <p role="status">Checking possible assignments…</p>
      )}
      {options && (
        <h3 className="text-lg font-semibold">
          {feasible.length
            ? `${feasible.length} feasible alternatives`
            : "No feasible alternative in this plan"}
        </h3>
      )}
      {feasible.map((o) => (
        <article
          key={o.route_id}
          className="mb-3 rounded-xl border border-line p-4"
        >
          <div className="flex justify-between gap-3">
            <strong className="font-mono">{o.vehicle_id}</strong>
            <span className="text-copy text-ok">All checks pass</span>
          </div>
          <p className="my-2 text-copy">
            {o.route_id.startsWith("NEW-") ? "Additional trip" : o.route_id} ·
            Arrival {o.arrival}
          </p>
          <dl className="mb-3 grid grid-cols-2 gap-2 text-copy">
            <div>
              <dt className="text-muted">Fuel change</dt>
              <dd className="m-0 font-mono">
                {o.added_fuel >= 0 ? "+" : ""}
                {o.added_fuel.toFixed(1)} L
              </dd>
            </div>
            <div>
              <dt className="text-muted">Distance change</dt>
              <dd className="m-0 font-mono">
                {o.added_distance >= 0 ? "+" : ""}
                {o.added_distance.toFixed(1)} km
              </dd>
            </div>
            <div>
              <dt className="text-muted">Weight remaining</dt>
              <dd className="m-0">{o.remaining_weight?.toFixed(0)} kg</dd>
            </div>
            <div>
              <dt className="text-muted">Volume remaining</dt>
              <dd className="m-0">{o.remaining_volume?.toFixed(2)} m³</dd>
            </div>
          </dl>
          <Btn
            disabled={busy}
            variant="primary"
            onClick={async () => {
              if (
                await act("move", {
                  day: plan.day,
                  revision: plan.revision,
                  order_id: order.id,
                  route_id: o.route_id,
                })
              )
                onClose();
            }}
          >
            Apply {o.vehicle_id}
          </Btn>
        </article>
      ))}
      {options && !feasible.length && (
        <p className="text-copy text-muted">
          Keep the order in the next-run queue and record a concrete follow-up.
          No feasible alternative here does not prove that a different overall
          plan is impossible.
        </p>
      )}
      {!!rejected.length && (
        <details className="mt-4">
          <summary className="cursor-pointer font-semibold">
            Why {rejected.length} alternatives fail
          </summary>
          <ul className="list-none p-0 text-copy">
            {rejected.map((o) => (
              <li key={o.route_id} className="border-b border-line py-3">
                <strong>
                  {o.vehicle_id} ·{" "}
                  {o.route_id.startsWith("NEW-") ? "New trip" : o.route_id}
                </strong>
                <p className="my-1 text-muted">
                  {o.reason.replace("Invalid assignment: ", "")}
                </p>
              </li>
            ))}
          </ul>
        </details>
      )}
    </Modal>
  );
}
