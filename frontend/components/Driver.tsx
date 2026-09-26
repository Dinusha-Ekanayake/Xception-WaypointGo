"use client";

import { useEffect, useState } from "react";
import {
  Badge,
  Empty,
  ProofForm,
  dockNote,
  fmtClock,
  type ProofSubmitData,
} from "./components";
import type { DriverLoaderShared, Order, QueuedCommand } from "../lib/types";
import { sortDeliveryStops } from "../lib/route-order";

const OPEN_STATES = ["delivered", "confirmed", "partial", "failed", "disputed"];

function QueueCard({
  items,
}: {
  items: QueuedCommand[];
}): React.JSX.Element | null {
  if (!items.length) return null;
  return (
    <div className="mx-3.5 mb-3 rounded-2xl border border-[var(--driver-line)] bg-[var(--driver-card)] p-4">
      <div className="flex items-center justify-between">
        <strong className="text-copy">Waiting to send</strong>
        <span className="rounded-full bg-[#ddefec] px-2.5 py-0.5 font-mono text-caption font-bold text-[#084f4a]">
          {items.length}
        </span>
      </div>
      {items.slice(0, 3).map((q) => (
        <div
          key={String(q.command.id)}
          className="border-t border-[var(--driver-line)] py-2 text-copy first:border-0"
        >
          {String(q.command.order_id)} · {String(q.command.kind)}
          <div className="font-mono text-caption text-[var(--driver-muted)]">
            {new Date(q.created).toLocaleTimeString()}
          </div>
        </div>
      ))}
      <div className="mt-1.5 text-caption text-[var(--driver-muted)]">
        Sends by itself when signal returns. Don&apos;t log out or clear the
        app.
      </div>
    </div>
  );
}

function StopAction({
  order,
  busy,
  onDepart,
  onArrive,
  onProof,
}: {
  order: Order;
  busy: boolean;
  onDepart: (o: Order) => void;
  onArrive: (o: Order) => void;
  onProof: (o: Order) => void;
}): React.JSX.Element {
  const primary =
    "min-h-14 flex-1 px-4 py-3 rounded-full border-0 bg-[#06c167] text-copy font-bold text-black disabled:opacity-40 cursor-pointer";
  if (order.status === "loaded") {
    return (
      <button
        onClick={() => onDepart(order)}
        disabled={busy}
        aria-label="Start this stop →"
        className={primary}
      >
        Start stop →
      </button>
    );
  }
  if (order.status === "departed") {
    return (
      <button
        onClick={() => onArrive(order)}
        disabled={busy}
        aria-label="I’ve arrived"
        className={primary}
      >
        I&apos;ve arrived
      </button>
    );
  }
  if (order.status === "arrived") {
    return (
      <button
        onClick={() => onProof(order)}
        disabled={busy}
        aria-label="Record delivery"
        className={primary}
      >
        Record delivery
      </button>
    );
  }
  return (
    <p className="text-copy text-[var(--driver-muted)]">Waiting for the loading check.</p>
  );
}

function mapsUrl(order: Order): string {
  const q = encodeURIComponent(`${order.district} Sri Lanka`);
  return `https://www.google.com/maps/search/?api=1&query=${q}`;
}

export default function Driver({
  state,
  orders,
  busy,
  onDetail,
  queue,
  doAction,
}: DriverLoaderShared): React.JSX.Element {
  const [proof, setProof] = useState<{
    order: Order;
    outcome: "delivered" | "partial" | "failed";
  } | null>(null);
  const [offlineSince, setOfflineSince] = useState<string | null>(null);
  const online = typeof navigator === "undefined" ? true : navigator.onLine;

  useEffect(() => {
    if (!online) setOfflineSince((s) => s || new Date().toISOString());
    else setOfflineSince(null);
  }, [online]);

  const [selectedDay, setSelectedDay] = useState(state.demo ? "2026-02-14" : new Date(state.now).toLocaleDateString("en-CA", { timeZone: "Asia/Colombo" }));
  const days = [...new Set(orders.map((o) => o.day))].sort();
  const day = days.includes(selectedDay) ? selectedDay : days.at(-1);
  const stops = sortDeliveryStops(
    orders.filter((o) => o.day === day),
    state.plans.filter((p) => p.day === day && p.published).flatMap((p) => p.routes),
  );
  const next = stops.find((o) => !OPEN_STATES.includes(o.status));
  const upcoming = stops.filter(
    (o) => o.id !== next?.id && !OPEN_STATES.includes(o.status),
  );
  const pending = queue.filter((q) => !q.error);
  const doneCount = stops.filter((o) => OPEN_STATES.includes(o.status)).length;

  function openProof(
    order: Order,
    outcome: "delivered" | "partial" | "failed" = "delivered",
  ): void {
    setProof({ order, outcome });
  }

  async function saveDelivery(data: ProofSubmitData): Promise<boolean> {
    const saved = !!proof && await doAction("deliver", proof.order, { ...data });
    if (saved) setProof(null);
    return saved;
  }

  function advance(order: Order): void {
    if (order.status === "loaded") void doAction("depart", order);
    else if (order.status === "departed") void doAction("arrive", order);
    else if (order.status === "arrived") openProof(order);
  }

  return (
    <div className="mx-auto w-full max-w-md bg-[var(--driver-base)] pb-[150px] text-[var(--driver-text)] min-h-screen md:max-w-6xl">
      <span className="sr-only">Your run, ready to go.</span>

      <div className="flex flex-wrap items-center justify-between gap-2 px-4 py-3 text-copy">
        <span className="font-mono text-[var(--driver-muted)]">
          {next ? next.route_id || "No run assigned" : "No assigned runs yet"}
        </span>
        <span className="rounded-full bg-[var(--driver-soft)] px-2.5 py-1 text-caption font-medium text-[var(--driver-accent)]">
          Last sync {fmtClock(state.updated)}
        </span>
      </div>

      {!!days.length && (
        <label className="mx-4 mb-3 block text-copy text-[var(--driver-muted)]">
          Run day
          <select
            aria-label="Driver run day"
            value={day}
            onChange={(e) => setSelectedDay(e.target.value)}
            className="mt-1 block min-h-11 w-full rounded-xl border border-[var(--driver-line)] bg-[var(--driver-base)] px-3 text-[var(--driver-text)]"
          >
            {days.map((d) => (
              <option key={d}>{d}</option>
            ))}
          </select>
        </label>
      )}
      {stops.length > 0 && !next && (
        <div className="mx-4 mb-4 rounded-2xl bg-[var(--driver-soft)] p-5">
          <h1 className="m-0 text-xl font-semibold">Run recorded</h1>
          <p className="mb-0 text-copy">
            {queue.length
              ? "Some records still need synchronization. Keep this device data until the queue is clear."
              : "Every stop has a delivery outcome. Store confirmation remains a separate step."}
          </p>
        </div>
      )}
      {/* progress */}
      {stops.length > 0 && (
        <div className="mx-3.5 mb-3 rounded-2xl border border-[var(--driver-line)] bg-[var(--driver-card)] p-4">
          <div className="flex items-center justify-between text-copy">
            <strong>
              {doneCount} of {stops.length} stops done
            </strong>
            <span className="font-mono text-[var(--driver-muted)]">
              {stops.length ? Math.round((doneCount / stops.length) * 100) : 0}%
            </span>
          </div>
          <div className="mt-2 h-2 overflow-hidden rounded-full bg-[var(--driver-base)]">
            <i
              className="block h-full rounded-full bg-[#06c167]"
              style={{
                width: `${stops.length ? (doneCount / stops.length) * 100 : 0}%`,
              }}
            />
          </div>
          <div className="mt-2 flex gap-1.5">
            {stops.slice(0, 8).map((o) => (
              <span
                key={o.id}
                title={o.id}
                className={`h-2 flex-1 rounded-full ${o.status === "failed" || o.status === "disputed" ? "bg-[#e11900]" : o.status === "partial" ? "bg-[#ffc043]" : OPEN_STATES.includes(o.status) ? "bg-[#06c167]" : "bg-[var(--driver-soft)]"}`}
              />
            ))}
          </div>
        </div>
      )}

      {!online && (
        <div className="mx-3.5 mb-3 rounded-2xl bg-[var(--driver-soft)] p-4 text-[var(--driver-muted)]">
          <strong className="text-[var(--driver-text)]">
            Offline{offlineSince ? ` since ${fmtClock(offlineSince)}` : ""} -
            work is saved here
          </strong>
          <div className="mt-1 text-copy">
            Use only while safely stopped. Your downloaded run remains
            available. New actions wait for synchronization.
          </div>
        </div>
      )}

      <QueueCard items={pending} />

      {!stops.length && (
        <div className="mx-3.5 rounded-2xl border border-[var(--driver-line)] bg-[var(--driver-card)]">
          <Empty title="No assigned runs yet">
            The published plan will appear here.
          </Empty>
        </div>
      )}

      <div className="lg:grid lg:grid-cols-[minmax(0,1fr)_380px] lg:items-start lg:gap-5 lg:px-5">
        <div>
          {next && (
            <article className="mx-3.5 mb-3 rounded-2xl border border-[var(--driver-line)] bg-[var(--driver-card)] p-5 md:mx-0">
              <div className="font-mono text-caption font-semibold tracking-widest text-[var(--driver-accent)]">
                NEXT · STOP {next.sequence || "-"} OF {stops.length}
              </div>
              <div className="mt-1.5 text-[30px] font-semibold tracking-tight leading-tight">
                {next.brand} {next.district}
              </div>
              <div className="text-copy text-[var(--driver-muted)]">
                {next.outlet_id} · {next.district}
              </div>

              <div className="mt-4 grid grid-cols-1 min-[400px]:grid-cols-2 gap-3">
                <div className="flex-1 rounded-2xl bg-[var(--driver-base)] p-3.5">
                  <div className="text-caption text-[var(--driver-muted)]">ETA</div>
                  <div className="font-mono text-[24px] font-semibold">
                    {next.eta || "-"}
                  </div>
                  <div className="font-mono text-caption text-[var(--driver-muted)]">
                    {next.vehicle_id}
                  </div>
                </div>
                <div className="flex-1 rounded-2xl bg-[var(--driver-base)] p-3.5">
                  <div className="text-caption text-[var(--driver-muted)]">Window</div>
                  <div className="font-mono text-[20px] font-semibold">
                    {next.window_open_time || ""}-{next.window_close_time || ""}
                  </div>
                  <div className="text-caption font-medium text-[var(--driver-accent)]">
                    {next.status === "arrived" ? "Arrived" : "Planned window"}
                  </div>
                </div>
              </div>

              <div className="mt-3 rounded-2xl bg-[var(--driver-base)] p-3.5 text-copy">
                <strong>
                  {next.units} cases · {next.volume.toFixed(2)} m³ ·{" "}
                  {next.weight.toLocaleString()} kg
                </strong>
                <div className="mt-1 text-caption text-[var(--driver-warning)]">
                  {dockNote(next.dock_type)}
                </div>
                <div className="mt-1 font-mono text-caption text-[var(--driver-muted)]">
                  {next.id} · {next.vehicle_id}
                </div>
              </div>
              <div className="mt-2">
                <Badge status={next.status} />
                {(next as Order & { local?: boolean }).local && (
                  <span className="ml-2 text-caption text-[var(--driver-muted)]">
                    ◷ On this device
                  </span>
                )}
              </div>

              <a
                href={mapsUrl(next)}
                target="_blank"
                rel="noreferrer"
                className="mt-3 flex min-h-12 w-full items-center justify-center rounded-full border border-[var(--driver-line)] text-copy font-semibold text-[var(--driver-text)]"
              >
                Search district in Maps →
              </a>

              <div className="mt-3 flex flex-wrap items-center gap-2">
                <StopAction
                  order={next}
                  busy={busy}
                  onDepart={(o) => void doAction("depart", o)}
                  onArrive={(o) => void doAction("arrive", o)}
                  onProof={(o) => openProof(o)}
                />
              </div>
              <button
                onClick={() => onDetail(next)}
                className="mt-2 border-0 bg-transparent p-0 text-copy font-medium text-[var(--driver-accent)] cursor-pointer"
              >
                View order & history →
              </button>
            </article>
          )}

          {next?.status === "arrived" && (
            <div className="mx-3.5 mb-3 rounded-2xl border border-[var(--driver-line)] bg-[var(--driver-card)] p-4 md:mx-0">
              <div className="font-mono text-caption tracking-widest text-[var(--driver-muted)]">
                HAND OVER · {next.units} CASES
              </div>
              <div className="mt-1 text-copy text-[var(--driver-muted)]">
                Choose the outcome that matches what happened.
              </div>
              <div className="mt-2.5 flex gap-2">
                <button
                  onClick={() => openProof(next, "partial")}
                  className="min-h-12 flex-1 rounded-full border border-[var(--driver-line)] bg-transparent text-copy font-semibold text-[var(--driver-text)] cursor-pointer"
                >
                  Part / refused
                </button>
                <button
                  onClick={() => openProof(next, "failed")}
                  className="min-h-12 flex-1 rounded-full bg-[#e11900] text-copy font-semibold text-white cursor-pointer"
                >
                  Can&apos;t deliver
                </button>
              </div>
            </div>
          )}
        </div>
        <div>
          <h2 className="mx-3.5 mt-1 mb-2 hidden text-copy font-semibold text-[var(--driver-text)] md:block">
            Route queue · {upcoming.length} remaining
          </h2>
          {upcoming.map((o) => (
            <article
              key={o.id}
              className="mx-3.5 mb-2.5 rounded-2xl border border-[var(--driver-line)] bg-[var(--driver-card)] p-4 md:mx-0"
            >
              <div className="font-mono text-caption text-[var(--driver-muted)]">
                #{o.sequence || "-"} · {o.brand} {o.district} ·{" "}
                {o.eta || o.window_close_time || ""}
              </div>
              <div className="mt-1.5 flex items-center gap-2">
                <strong className="text-copy">{o.outlet_id}</strong>
                <Badge status={o.status} />
              </div>
              <div className="mt-2.5 flex gap-2">
                {o.status === "loaded" && (
                  <button
                    disabled={busy}
                    onClick={() => void doAction("depart", o)}
                    aria-label="Start this stop →"
                    className="min-h-11 flex-1 rounded-full bg-[var(--driver-soft)] text-copy font-semibold cursor-pointer"
                  >
                    Start stop →
                  </button>
                )}
                {o.status === "departed" && (
                  <button
                    disabled={busy}
                    onClick={() => void doAction("arrive", o)}
                    aria-label="I’ve arrived"
                    className="min-h-11 flex-1 rounded-full bg-[var(--driver-soft)] text-copy font-semibold cursor-pointer"
                  >
                    I&apos;ve arrived
                  </button>
                )}
                {o.status === "arrived" && (
                  <button
                    disabled={busy}
                    onClick={() => openProof(o)}
                    aria-label="Record delivery"
                    className="min-h-11 flex-1 rounded-full bg-[#06c167] text-copy font-bold text-black cursor-pointer"
                  >
                    Record delivery
                  </button>
                )}
                <button
                  onClick={() => onDetail(o)}
                  className="min-h-11 rounded-full border border-white/15 px-4 text-copy text-[var(--driver-text)]/80 cursor-pointer"
                >
                  View
                </button>
              </div>
            </article>
          ))}
        </div>
      </div>

      <div className="fixed inset-x-0 bottom-0 bg-[var(--driver-base)] border-t border-[var(--driver-line)] pb-[env(safe-area-inset-bottom)]">
        <div className="mx-auto flex w-full max-w-md gap-2.5 px-3.5 py-3 md:max-w-2xl">
          <button
            onClick={() => next && onDetail(next)}
            disabled={!next}
            className="min-h-14 min-w-[96px] px-4 rounded-full border border-[var(--driver-line)] border border-[var(--driver-line)] bg-[var(--driver-card)] text-copy font-semibold text-[var(--driver-text)] disabled:opacity-40 cursor-pointer"
          >
            Details
          </button>
          <button
            disabled={
              busy ||
              !next ||
              !["loaded", "departed", "arrived"].includes(next.status)
            }
            onClick={() => next && advance(next)}
            className="min-h-14 flex-1 px-4 py-3 rounded-full border-0 bg-[#06c167] text-copy font-bold text-black disabled:opacity-40 cursor-pointer"
          >
            {!next
              ? "Run recorded"
              : next.status === "arrived"
                ? "Record delivery"
                : next.status === "loaded"
                  ? "Start stop →"
                  : next.status === "departed"
                    ? "I've arrived"
                    : "Waiting for loading"}
          </button>
        </div>
      </div>

      {proof && (
        <ProofForm
          key={`${proof.order.id}:${proof.order.version}`}
          draftKey={`delivery-draft:${state.user.id}:${proof.order.id}:${proof.order.version}`}
          order={proof.order}
          initialOutcome={proof.outcome}
          busy={busy}
          onClose={() => setProof(null)}
          onSubmit={saveDelivery}
        />
      )}
    </div>
  );
}
