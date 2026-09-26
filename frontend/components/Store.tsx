"use client";

import { ProofImages } from "./ProofImages";
import { useState } from "react";
import type { FormEvent } from "react";
import {
  Badge,
  Brand,
  Btn,
  Empty,
  Field,
  Modal,
  NoteForm,
  cutoffCountdown,
  fmtClock,
  fmtDayLabel,
  inputClass,
} from "./components";
import { OrderFormSchema } from "../lib/types";
import type { Order, Outlet, WorkspaceBundle } from "../lib/types";

function PhoneCard({
  className = "",
  ...props
}: React.HTMLAttributes<HTMLElement>): React.JSX.Element {
  return <article className={`wp-card w-full p-4 ${className}`} {...props} />;
}

/* ---------------- place-order form ---------------- */

function OrderModal({
  outlet,
  busy,
  onClose,
  onSubmit,
}: {
  outlet: Outlet | undefined;
  busy: boolean;
  onClose: () => void;
  onSubmit: (data: {
    temp: "ambient" | "chilled";
    units: number;
    weight: number;
    volume: number;
  }) => Promise<boolean>;
}): React.JSX.Element {
  const [error, setError] = useState("");

  async function handleSubmit(e: FormEvent<HTMLFormElement>): Promise<void> {
    e.preventDefault();
    const form = new FormData(e.currentTarget);
    const raw = {
      temp: String(form.get("temp")),
      units: Number(form.get("units")),
      weight: Number(form.get("weight")),
      volume: Number(form.get("volume")),
    };
    const parsed = OrderFormSchema.safeParse(raw);
    if (!parsed.success) {
      setError(parsed.error.issues[0]?.message ?? "Invalid quantity.");
      return;
    }
    if (parsed.data.temp === "chilled" && outlet?.brand !== "Fresh") {
      setError("Chilled orders are available for Fresh outlets.");
      return;
    }
    setError("");
    if (await onSubmit(parsed.data)) onClose();
  }

  return (
    <Modal title="Place an order" onClose={onClose}>
      <p className="m-0 text-copy text-muted">
        {outlet?.outlet_id} · {outlet?.brand} · {outlet?.district}
      </p>
      <form onSubmit={handleSubmit} className="mt-3 flex flex-col gap-3.5">
        <Field label="Goods">
          <select name="temp" className={inputClass}>
            <option value="ambient">Ambient goods</option>
            {outlet?.brand === "Fresh" && (
              <option value="chilled">Chilled goods ❄</option>
            )}
          </select>
        </Field>
        <div className="grid grid-cols-3 gap-2">
          <Field label="Cases">
            <input
              name="units"
              type="number"
              min="1"
              max="100000"
              required
              placeholder="12"
              className={inputClass}
            />
          </Field>
          <Field label="kg">
            <input
              name="weight"
              type="number"
              min="0.01"
              step="0.01"
              max="1000000"
              required
              placeholder="60"
              className={inputClass}
            />
          </Field>
          <Field label="m³">
            <input
              name="volume"
              type="number"
              min="0.001"
              step="0.001"
              max="10000"
              required
              placeholder="0.4"
              className={inputClass}
            />
          </Field>
        </div>
        {error && (
          <p
            role="alert"
            className="rounded-2xl bg-[#fdecea] p-3 text-copy font-medium text-[#e11900]"
          >
            {error}
          </p>
        )}
        <p className="m-0 text-caption text-muted">
          Server confirms the day using the 16:00 cutoff + operating calendar.
        </p>
        <Btn
          type="submit"
          variant="primary"
          size="lg"
          disabled={busy}
          aria-label="Submit order"
        >
          Submit order →
        </Btn>
      </form>
    </Modal>
  );
}

/* ---------------- orders list ---------------- */

function OnTheWayCard({
  order,
  queue,
  busy,
  onDetail,
  onReceive,
  onDispute,
  onCheckReceipt,
}: {
  order: Order;
  queue: WorkspaceBundle["queue"];
  busy: boolean;
  onDetail: (o: Order) => void;
  onReceive: (o: Order) => void;
  onDispute: (o: Order) => void;
  onCheckReceipt: (o: Order) => void;
}): React.JSX.Element {
  const queued = queue.some((q) => (q.command.order_id as string) === order.id);
  const receivable = ["delivered", "partial"].includes(order.status) && !queued;

  return (
    <PhoneCard>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="rounded-full bg-[#e6f9ed] px-2.5 py-1 text-caption font-semibold text-[#068b4a]">
          {order.status === "departed"
            ? "On the way"
            : order.status === "arrived"
              ? "At your outlet"
              : [
                    "delivered",
                    "partial",
                    "confirmed",
                    "disputed",
                    "failed",
                  ].includes(order.status)
                ? "Delivery record"
                : "Awaiting dispatch"}
        </span>
        <span className="font-mono text-caption text-muted">{order.id}</span>
      </div>
      <div className="mt-2 text-caption text-muted">Planned receiving time</div>
      <div className="font-mono text-[28px] font-semibold tracking-tight">
        {order.eta || "Not allocated yet"}
      </div>
      <div className="text-copy text-muted">
        Stop {order.sequence || "-"}
        {order.vehicle_id ? ` · ${order.vehicle_id}` : ""}
      </div>
      <div className="mt-2">
        <Brand order={order} />
      </div>
      <div className="mt-1.5 text-copy">
        {order.units} cases · {order.volume.toFixed(2)} m³ ·{" "}
        {order.weight.toLocaleString()} kg
      </div>

      {order.shortfall && (
        <div className="mt-2.5 rounded-2xl bg-[#fff3d6] p-3 text-copy">
          <strong>{order.shortfall.count} cases short</strong>
          <div className="text-muted">At loading: {order.shortfall.note}</div>
        </div>
      )}

      <div className="mt-2">
        <Badge status={order.status} />
      </div>
      {order.deferral && (
        <div className="mt-2.5 rounded-2xl bg-[#efe8fb] p-3.5 text-copy">
          <strong>Moved to a later run</strong>
          <p className="m-0 mt-1 text-muted">{order.deferral.reason}</p>
        </div>
      )}

      <div className="mt-2.5 flex flex-wrap gap-2">
        <button
          onClick={() => onDetail(order)}
          className="min-h-10 rounded-lg px-2 text-left text-copy font-semibold text-[#0a6b63] hover:bg-[#edf3f1] cursor-pointer"
        >
          View receipt →
        </button>
        {receivable && (
          <>
            <Btn
              variant="primary"
              size="sm"
              disabled={busy}
              onClick={() => onReceive(order)}
              aria-label="Confirm receipt"
            >
              Confirm receipt
            </Btn>
            <Btn size="sm" onClick={() => onDispute(order)}>
              Report issue
            </Btn>
          </>
        )}
        {order.status === "delivered" && (
          <Btn size="sm" onClick={() => onCheckReceipt(order)}>
            Check arrival →
          </Btn>
        )}
      </div>
      {order.status === "confirmed" && (
        <p className="mt-1.5 text-caption font-medium text-[#068b4a]">
          Thank you - confirmation received.
        </p>
      )}
      {queued && (
        <p className="mt-1.5 text-caption text-muted">◷ Waiting to sync</p>
      )}
    </PhoneCard>
  );
}

function DeferredSummary({
  order,
  onOpen,
}: {
  order: Order;
  onOpen: (o: Order) => void;
}): React.JSX.Element {
  const next = order.deferral?.next_date
    ? fmtDayLabel(order.deferral.next_date)
    : "a later run";
  return (
    <PhoneCard>
      <div className="flex flex-wrap justify-between gap-2">
        <span className="rounded-full bg-[#efe8fb] px-2.5 py-1 text-caption font-semibold text-[#5b2eb5]">
          Deferred to {next}
        </span>
        <span className="font-mono text-caption text-muted">{order.id}</span>
      </div>
      <h2 className="my-2 text-copy font-semibold tracking-tight">
        {order.brand} top-up · {order.volume.toFixed(1)} m³
      </h2>
      <p className="m-0 text-copy text-muted">
        {order.deferral?.reason || "Moved for capacity reasons."}
      </p>
      <button
        onClick={() => onOpen(order)}
        className="mt-1.5 min-h-10 rounded-lg px-2 text-left text-copy font-semibold text-[#0a6b63] hover:bg-[#edf3f1] cursor-pointer"
      >
        See reason and options →
      </button>
    </PhoneCard>
  );
}

/* ---------------- deferral detail ---------------- */

function DeferralView({
  order,
  onBack,
  onDetail,
  onOrder,
  clock,
}: {
  order: Order;
  onBack: () => void;
  onDetail: (o: Order) => void;
  onOrder: () => void;
  clock: string;
}): React.JSX.Element {
  const next = order.deferral?.next_date
    ? fmtDayLabel(order.deferral.next_date)
    : "the next run";
  const lastServed = order.days_since_last_served;
  return (
    <div className="mx-auto w-full max-w-md bg-[#f6f6f6] pb-10 min-h-screen md:max-w-3xl">
      <div className="flex items-center justify-between bg-white px-4 py-3 text-copy border-b border-[#e2e2e2]">
        <button
          onClick={onBack}
          className="min-h-10 rounded-lg px-2 text-copy font-semibold text-[#0a6b63] hover:bg-[#edf3f1] cursor-pointer"
        >
          ‹ Orders
        </button>
        <span className="font-mono">{clock}</span>
      </div>

      <div className="mx-4 mt-4">
        <span className="rounded-full bg-[#efe8fb] px-2.5 py-1 text-caption font-semibold text-[#5b2eb5]">
          Deferred
        </span>
        <h1 className="my-2 text-[28px] font-semibold tracking-tight leading-tight">
          Queued for {next}
        </h1>
        <p className="m-0 text-copy text-muted">
          {order.id} · {order.volume.toFixed(1)} m³ · {order.units} cases
        </p>
      </div>

      <div className="wp-card mx-4 mt-3 p-4">
        <p className="m-0 font-mono text-caption font-semibold tracking-widest text-muted">
          WHY
        </p>
        <p className="mt-1 text-copy">
          {order.deferral?.reason || "Moved for capacity reasons."}
        </p>
        {order.deferral?.justification && (
          <p className="text-copy text-muted">
            Dispatcher note: {order.deferral.justification}
          </p>
        )}
        <p className="mt-3 font-mono text-caption font-semibold tracking-widest text-muted">
          NEXT ELIGIBLE RUN
        </p>
        <p className="m-0 mt-1 text-copy">
          <strong>{next}, subject to allocation</strong>
        </p>
        <p className="m-0 text-copy text-muted">
          This order stays in the queue with repeat-deferral priority. A
          confirmed time appears after allocation.
        </p>
        <p className="mt-3 font-mono text-caption font-semibold tracking-widest text-muted">
          YOUR HISTORY
        </p>
        <p className="m-0 mt-1 text-copy text-muted">
          {lastServed != null
            ? `Last served ${lastServed} day${lastServed === 1 ? "" : "s"} ago`
            : "First order at this outlet"}
          {order.skips > 0
            ? ` · skipped ${order.skips} time${order.skips === 1 ? "" : "s"}`
            : ""}
        </p>
      </div>

      <div className="flex flex-col gap-2 px-4 pt-3">
        <Btn variant="primary" size="lg" onClick={onBack}>
          Keep for {next}
        </Btn>
        <div className="flex gap-2">
          <Btn onClick={onOrder} className="h-[52px] flex-1 !rounded-full">
            Place separate order
          </Btn>
          <Btn
            onClick={() => onDetail(order)}
            className="h-[52px] flex-1 !rounded-full"
          >
            Receipt
          </Btn>
        </div>
      </div>
    </div>
  );
}

/* ---------------- receipt check ---------------- */

const ISSUE_CHOICES = ["Damaged · 1", "Missing", "Wrong item"];

function ReceiptView({
  accountId,
  order,
  queue,
  busy,
  clock,
  onBack,
  onReceive,
  onDispute,
}: {
  order: Order;
  queue: WorkspaceBundle["queue"];
  busy: boolean;
  clock: string;
  onBack: () => void;
  onReceive: (o: Order) => void;
  onDispute: (o: Order) => void;
  accountId: string;
}): React.JSX.Element {
  const [dry, setDry] = useState("good");
  const [issue, setIssue] = useState(ISSUE_CHOICES[0]!);
  const queued = queue.some((q) => (q.command.order_id as string) === order.id);
  const receivable = ["delivered", "partial"].includes(order.status) && !queued;

  const toggle = (on: boolean, activeTone: string): string =>
    `min-h-12 flex-1 rounded-full text-copy font-semibold cursor-pointer ${on ? activeTone : "bg-[#f6f6f6] text-black"}`;

  return (
    <div className="mx-auto w-full max-w-md bg-[#f6f6f6] pb-[120px] min-h-screen md:max-w-3xl">
      <div className="flex items-center justify-between bg-white px-4 py-3 text-copy border-b border-[#e2e2e2]">
        <button
          onClick={onBack}
          className="min-h-10 rounded-lg px-2 text-copy font-semibold text-[#0a6b63] hover:bg-[#edf3f1] cursor-pointer"
        >
          ‹ Orders
        </button>
        <span className="font-mono">{clock}</span>
      </div>

      <h1 className="mx-4 mt-4 text-[24px] font-semibold tracking-tight">
        Check what arrived
      </h1>
      <p className="mx-4 mb-3 text-copy text-muted">
        {order.proof
          ? `Delivered ${order.proof.count} of ${order.units} by ${order.vehicle_id || "the driver"}${order.proof.receiver ? ` · ${order.proof.receiver}` : ""}`
          : `Expected ${order.units} cases · ${order.vehicle_id || "vehicle to be confirmed"}`}
      </p>

      {order.shortfall && (
        <div className="mx-4 mb-2.5 rounded-2xl bg-[#fff3d6] p-4 text-copy">
          <strong>{order.shortfall.count} cases short</strong>
          <div className="text-muted">At loading: {order.shortfall.note}</div>
          {order.resolution && (
            <div className="mt-1 font-medium text-[#068b4a]">
              Fixed: {order.resolution.note}
            </div>
          )}
        </div>
      )}

      <article className="wp-card mx-4 p-4">
        <strong className="text-copy">{order.units} cases</strong>
        <span className="font-mono text-caption text-muted"> {order.id}</span>
        <div className="mt-2.5 flex gap-2">
          <button
            onClick={() => setDry("good")}
            className={toggle(dry === "good", "bg-[#0a6b63] text-white")}
          >
            All good
          </button>
          <button
            onClick={() => setDry("problem")}
            className={toggle(dry !== "good", "bg-[#e11900] text-white")}
          >
            Problem
          </button>
        </div>
        {dry !== "good" && (
          <div className="mt-2 flex flex-wrap gap-2">
            {ISSUE_CHOICES.map((c) => (
              <button
                key={c}
                onClick={() => setIssue(c)}
                className={`min-h-10 rounded-full px-4 text-copy font-semibold ${issue === c ? "bg-[#0a6b63] text-white" : "bg-[#f6f6f6]"}`}
              >
                {c}
              </button>
            ))}
          </div>
        )}
        <div className="mt-2.5">
          <Badge status={order.status} />
        </div>
        {order.proof && (
          <p className="text-copy">
            {order.proof.count} cases delivered
            {order.proof.receiver && ` to ${order.proof.receiver}`}.
          </p>
        )}
        {order.proof && <ProofImages accountId={accountId} order={order} />}
        {receivable && (
          <div className="mt-2 flex gap-2">
            <Btn
              variant="primary"
              size="sm"
              disabled={busy}
              onClick={() => onReceive(order)}
              aria-label="Confirm receipt"
              className="flex-1"
            >
              Confirm receipt
            </Btn>
            <Btn size="sm" onClick={() => onDispute(order)} className="flex-1">
              Report issue
            </Btn>
          </div>
        )}
      </article>

      <div className="fixed inset-x-0 bottom-0 border-t border-[#e2e2e2] bg-white">
        <div className="mx-auto w-full max-w-md px-4 py-3 md:max-w-2xl">
          <Btn
            variant="primary"
            size="lg"
            disabled={busy || !receivable}
            onClick={() =>
              dry === "good" ? onReceive(order) : onDispute(order)
            }
            aria-label={dry === "good" ? "Confirm receipt" : "Report issue"}
          >
            {dry === "good" ? "Confirm receipt ✓" : "Report selected issue"}
          </Btn>
        </div>
      </div>
    </div>
  );
}

/* ---------------- main component ---------------- */

export default function Store({
  state,
  act,
  busy,
  onDetail,
  queue,
}: WorkspaceBundle): React.JSX.Element {
  const [creating, setCreating] = useState(false);
  const [dispute, setDispute] = useState<Order | null>(null);
  const [view, setView] = useState<
    { name: "orders" } | { name: "deferral" | "receipt"; order: Order }
  >({ name: "orders" });

  const outlet = state.outlets[0];
  const deferred = state.orders.filter((o) => o.status === "deferred");
  const active = state.orders
    .filter((o) => o.status !== "deferred")
    .sort((a, b) => b.day.localeCompare(a.day));
  const focus =
    view.name === "orders"
      ? undefined
      : state.orders.find((o) => o.id === view.order.id) || view.order;
  const clock = fmtClock(state.now);

  async function placeOrder(data: {
    temp: "ambient" | "chilled";
    units: number;
    weight: number;
    volume: number;
  }): Promise<boolean> {
    return act("order", data);
  }

  async function receive(order: Order): Promise<void> {
    await act("receive", { order_id: order.id, version: order.version });
  }

  async function report(order: Order, data: { note: string }): Promise<void> {
    if (
      await act("dispute", {
        order_id: order.id,
        version: order.version,
        ...data,
      })
    )
      setDispute(null);
  }

  const toOrders = (): void => setView({ name: "orders" });

  if (view.name === "deferral" && focus) {
    return (
      <>
        <DeferralView
          order={focus}
          clock={clock}
          onBack={toOrders}
          onDetail={onDetail}
          onOrder={() => setCreating(true)}
        />
        {creating && (
          <OrderModal
            outlet={outlet}
            busy={busy}
            onClose={() => setCreating(false)}
            onSubmit={placeOrder}
          />
        )}
      </>
    );
  }

  if (view.name === "receipt" && focus) {
    return (
      <>
        <ReceiptView
          accountId={state.user.id}
          order={focus}
          queue={queue}
          busy={busy}
          clock={clock}
          onBack={toOrders}
          onReceive={(o) => void receive(o)}
          onDispute={(o) => setDispute(o)}
        />
        {dispute && (
          <NoteForm
            title="Report issue"
            label="What differs from the delivery record?"
            busy={busy}
            onClose={() => setDispute(null)}
            onSubmit={(data) => void report(dispute, data)}
          />
        )}
      </>
    );
  }

  return (
    <div className="mx-auto w-full max-w-md bg-[#f6f6f6] pb-[140px] min-h-screen md:max-w-6xl">
      <div className="flex items-center justify-between bg-white px-4 py-3 text-copy border-b border-[#e2e2e2]">
        <span className="font-mono font-semibold">{clock}</span>
        <span>
          <strong>
            {outlet?.brand} {outlet?.district}
          </strong>{" "}
          <span className="text-muted">· {outlet?.outlet_id}</span>
        </span>
      </div>

      <div className="md:grid md:grid-cols-[minmax(0,1fr)_340px] md:items-center md:gap-4 md:px-4">
        <h1 className="mx-4 mt-4 text-[28px] font-semibold tracking-tight leading-tight md:mx-0">
          Your orders and receipts
        </h1>

        <div className="mx-4 mt-2 rounded-2xl bg-[#0a6b63] p-4 text-white text-copy md:mx-0">
          <strong>Next-day cutoff 16:00.</strong>{" "}
          <span className="text-white/80">
            After cutoff moves to the next run.
          </span>
        </div>
      </div>

      <div className="mt-2.5 grid gap-2.5 px-4 md:grid-cols-2 xl:grid-cols-3">
        {active.map((o) => (
          <OnTheWayCard
            key={o.id}
            order={o}
            queue={queue}
            busy={busy}
            onDetail={onDetail}
            onReceive={(ord) => void receive(ord)}
            onDispute={(ord) => setDispute(ord)}
            onCheckReceipt={(ord) => setView({ name: "receipt", order: ord })}
          />
        ))}
      </div>

      <div className="mt-2.5 grid gap-2.5 px-4 md:grid-cols-2">
        {deferred.map((o) => (
          <DeferredSummary
            key={`d${o.id}`}
            order={o}
            onOpen={(ord) => setView({ name: "deferral", order: ord })}
          />
        ))}
      </div>

      {!state.orders.length && (
        <div className="mx-4 wp-card">
          <Empty title="Your first order starts here">
            Place an order to get a confirmation.
          </Empty>
        </div>
      )}

      <div className="fixed inset-x-0 bottom-0 border-t border-[#e2e2e2] bg-white">
        <div className="mx-auto flex w-full max-w-md flex-col gap-2 px-4 py-3 md:max-w-2xl">
          <div className="flex w-full items-center justify-between text-copy">
            <span className="font-medium">Orders close at 16:00</span>
            <span className="rounded-full bg-[#efefef] px-2.5 py-1 font-mono text-caption font-semibold">
              {cutoffCountdown(state.now)}
            </span>
          </div>
          <Btn
            variant="primary"
            size="lg"
            onClick={() => setCreating(true)}
            aria-label="＋ Place an order"
          >
            Place order +
          </Btn>
        </div>
      </div>

      {creating && (
        <OrderModal
          outlet={outlet}
          busy={busy}
          onClose={() => setCreating(false)}
          onSubmit={placeOrder}
        />
      )}
      {dispute && (
        <NoteForm
          title="Report issue"
          label="What differs from the delivery record?"
          busy={busy}
          onClose={() => setDispute(null)}
          onSubmit={(data) => void report(dispute, data)}
        />
      )}
    </div>
  );
}
