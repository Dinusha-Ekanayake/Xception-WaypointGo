"use client";

import { useMemo, useState } from "react";
import { ApiError } from "@shared/api/problem";
import { useResource } from "@shared/api/useResource";
import { OrderCommandKind, type LineAvailability, type OrderView, type OutletView, type Temperature } from "@shared/domain/types";
import { Icon, Notice, cx } from "@shared/ui";
import type { StoreGateway } from "../data/gateway.ts";
import { addDays, cutoffLabel, dayLabel, depotToday, hhmm, longDay, untilCutoff } from "../data/format.ts";
import { conflictMessage, type useCommands } from "../data/useCommands.ts";
import { BackButton, Button, Muted, Stepper } from "../ui.tsx";
import OrderSent, { type Sent } from "./OrderSent.tsx";

// Figma "03 Place order" and "03b Place order: chilled". Chilled and ambient
// are separate orders (R-ORD-06), so one submit places one order per class.
// The warehouse decides stock: a short line rejects the placement with
// per-line availability (D-F), shown here so the manager can adjust and send
// again. A delivery date that falls on a non-operating day rolls forward, and
// the rolled date is shown before the order is sent (R-ORD-08, D-I).

type Commands = ReturnType<typeof useCommands>;

export default function PlaceOrder({
  gateway,
  outlet,
  orders,
  amend,
  warehouseDown,
  commands,
  onDone,
  onBack,
}: {
  gateway: StoreGateway;
  outlet: OutletView | null;
  orders: OrderView[];
  amend: OrderView | null;
  warehouseDown: boolean;
  commands: Commands;
  onDone: () => void;
  onBack: () => void;
}): React.JSX.Element {
  const today = depotToday();
  const left = untilCutoff();
  const first = addDays(today, left > 0 ? 1 : 2);
  const [date, setDate] = useState(amend?.requestedDate ?? first);
  const [temp, setTemp] = useState<Temperature>(amend?.temperature ?? "ambient");
  const [qty, setQty] = useState<Record<string, number>>(() => Object.fromEntries((amend?.lines ?? []).map((l) => [l.productId, l.quantity])));
  const [search, setSearch] = useState("");
  const [short, setShort] = useState<LineAvailability[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [sent, setSent] = useState<Sent | null>(null);

  const brand = outlet?.brandCode ?? "";
  const catalogue = useResource(brand ? (s) => gateway.catalogue(brand, s) : null, brand);
  const day = useResource((s) => gateway.calendar(date, s), date);

  // The last quantity this outlet ordered of each product, as a guide.
  const usual = useMemo(() => {
    const m = new Map<string, number>();
    for (const o of [...orders].sort((a, b) => a.placedAt.localeCompare(b.placedAt))) for (const l of o.lines) m.set(l.productId, l.quantity);
    return m;
  }, [orders]);

  const products = catalogue.data ?? [];
  const tempOf = (id: string) => products.find((p) => p.productId === id)?.temperature ?? "ambient";
  const count = (t: Temperature) => Object.entries(qty).filter(([id, n]) => n > 0 && tempOf(id) === t);
  const shown = products.filter((p) => (p.temperature ?? "ambient") === temp && p.productId.toLowerCase().includes(search.trim().toLowerCase()));
  const classes: Temperature[] = amend ? [amend.temperature] : ["ambient", "chilled"];
  const cases = classes.reduce((s, t) => s + count(t).reduce((a, [, n]) => a + n, 0), 0);
  const rolled = day.data && !day.data.operating ? day.data.nextOperatingDay : null;

  const submit = async () => {
    setError(null);
    setShort([]);
    const placed: OrderView[] = [];
    let queued = false;
    for (const t of classes) {
      const lines = count(t).map(([productId, quantity]) => ({ productId, quantity }));
      if (lines.length === 0) continue;
      const outcome = amend
        ? await commands.run(OrderCommandKind.amend, { orderId: amend.orderId, lines }, amend.rowVersion)
        : await commands.run(OrderCommandKind.place, { outletId: outlet?.outletId ?? "", requestedDate: date, lines }, null);
      if (!outcome.ok) {
        const e = outcome.error;
        const availability = e instanceof ApiError ? (e.problem.extensions.availability as LineAvailability[] | undefined) : undefined;
        if (availability?.length) {
          setShort(availability);
          setTemp(t);
          setError(`The warehouse cannot supply ${availability.length === 1 ? "one line" : `${availability.length} lines`}. Nothing was saved for the ${t} order; adjust and send again.`);
        } else setError(conflictMessage(e));
        // What went through already stays placed; take it out of the draft.
        if (placed.length || queued) setSent({ orders: placed, queued, requestedDate: date });
        return;
      }
      if (outcome.queued) queued = true;
      else placed.push(outcome.ack.result as OrderView);
      setQty((q) => Object.fromEntries(Object.entries(q).filter(([id]) => tempOf(id) !== t)));
    }
    setSent({ orders: placed, queued, requestedDate: date });
  };

  const setLine = (id: string, n: number) => {
    setQty((q) => ({ ...q, [id]: n }));
    setShort((s) => s.filter((x) => x.productId !== id || n > x.available));
  };

  return (
    <div className="flex flex-col gap-4 pb-44 lg:pb-0">
      <BackButton onClick={onBack} />
      <div className="flex flex-col gap-1">
        <h1 className="text-[32px] leading-tight font-medium text-black">{amend ? `Change ${amend.orderRef}` : "Place order"}</h1>
        <Muted>
          {longDay(today)} · orders close 4:00 PM · {cutoffLabel(left)}
        </Muted>
      </div>

      {/* Desktop: the list on the left and the summary card on the right, as in "03 Place order". */}
      <div className="flex flex-col gap-4 lg:grid lg:grid-cols-[minmax(0,1fr)_380px] lg:items-start lg:gap-6">
      <div className="flex min-w-0 flex-col gap-4">
      {!amend && (
        <fieldset className="flex flex-col gap-2">
          <legend className="mb-2 text-[13px] text-go-muted">Delivery day</legend>
          <div className="flex gap-2">
            {[0, 1, 2].map((i) => {
              const d = addDays(first, i);
              return (
                <button
                  key={d}
                  type="button"
                  aria-pressed={d === date}
                  onClick={() => setDate(d)}
                  className={cx("min-h-12 flex-1 rounded-[18px] px-2 text-[15px] font-medium", d === date ? "bg-[#031a0c] text-white" : "bg-white text-black")}
                >
                  {dayLabel(d)}
                </button>
              );
            })}
          </div>
        </fieldset>
      )}
      {rolled && (
        <Notice tone="warning" live title={`${dayLabel(date)} is not a delivery day. This order will arrive ${dayLabel(rolled)}.`}>
          Depots are closed that day{day.data?.day && "festival" in day.data.day && day.data.day.festival ? ` (${day.data.day.festival})` : ""}. You can still order now; the date moves to the next operating day.
        </Notice>
      )}
      {warehouseDown && (
        <Notice tone="warning" title="Stock can't be checked right now">
          The order is kept as &ldquo;stock not checked&rdquo; until the warehouse answers. It is not confirmed yet.
        </Notice>
      )}

      {!amend && (
        <div className="flex gap-2 rounded-full bg-white/60 p-1" role="tablist" aria-label="Temperature">
          {(["ambient", "chilled"] as const).map((t) => (
            <button
              key={t}
              type="button"
              role="tab"
              aria-selected={t === temp}
              onClick={() => setTemp(t)}
              className={cx("flex min-h-12 flex-1 items-center justify-center gap-2 rounded-full px-3 text-[16px]", t === temp ? "bg-white font-medium text-black shadow-[0_5px_20px_rgba(0,0,0,0.09)]" : "text-go-muted")}
            >
              <Icon name={t === "chilled" ? "chilled" : "box"} />
              {t === "chilled" ? "Chilled" : "Ambient"} · {count(t).length} {count(t).length === 1 ? "item" : "items"}
            </button>
          ))}
        </div>
      )}

      <label className="flex min-h-12 items-center gap-2 rounded-[18px] px-4 text-[15px] text-go-muted">
        <Icon name="search" />
        <input value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Find an item" className="min-w-0 flex-1 bg-transparent text-black outline-none placeholder:text-go-muted" />
      </label>

      {error && <Notice tone="danger" live title={error} />}
      {catalogue.error && <Notice tone="danger" title="Could not load the catalogue">{catalogue.error.message}</Notice>}

      <ul className="flex flex-col gap-2.5">
        {shown.map((p) => {
          const n = qty[p.productId] ?? 0;
          const s = short.find((x) => x.productId === p.productId);
          const u = usual.get(p.productId);
          return (
            <li key={p.productId} className={cx("flex items-center gap-2 rounded-[16px] bg-white py-2.5 pr-2.5 pl-3.5", s && "outline-2 outline-go-danger")}>
              <div className="flex min-w-0 flex-1 flex-col">
                <span className="truncate text-[16px] font-medium text-black">{p.productId}</span>
                <span className="text-[13px] text-go-muted">
                  {!p.verifiedRealSku && <span title="Reconstructed from order totals, not a confirmed product">inferred · </span>}
                  {u !== undefined ? `usual ${u}` : "not ordered before"}
                </span>
                {s && (
                  <span className="text-[13px] font-medium text-go-danger-strong">
                    Only {s.available} available ·{" "}
                    <button type="button" className="underline" onClick={() => setLine(p.productId, s.available)}>
                      use {s.available}
                    </button>
                  </span>
                )}
              </div>
              <Stepper value={n} label={p.productId} highlight={u !== undefined && n !== u && n > 0} onChange={(v) => setLine(p.productId, v)} />
            </li>
          );
        })}
        {catalogue.loading && <Muted>Loading the catalogue…</Muted>}
      </ul>
      </div>

      <div className="fixed inset-x-0 bottom-0 z-30 flex justify-center lg:sticky lg:top-8 lg:z-auto">
        <div className="flex w-full max-w-[720px] flex-col gap-3 rounded-t-[32px] bg-white px-6 pt-5 pb-7 shadow-[0_-5px_20px_rgba(0,0,0,0.06)] lg:rounded-[26px] lg:shadow-[0_5px_20px_rgba(0,0,0,0.09)]">
          <h2 className="hidden text-[20px] font-medium text-black lg:block">Order summary</h2>
          <Muted>
            Delivery {dayLabel(rolled ?? date)}
            {outlet ? ` · ${hhmm(outlet.windowOpen)}-${hhmm(outlet.windowClose)}` : ""}
          </Muted>
          <p className="text-[28px] leading-tight font-semibold text-black">
            {cases === 1 ? "1 case" : `${cases} cases`}
            {!amend && ` · ${classes.filter((t) => count(t).length > 0).length || 0} orders`}
          </p>
          <div className="flex gap-2.5">
            <Button tone="plain" large onClick={onBack}>
              Cancel
            </Button>
            <Button large disabled={cases === 0 || commands.busy || !outlet} onClick={() => void submit()}>
              {commands.busy ? "Sending…" : amend ? "Save change" : "Submit order"}
            </Button>
          </div>
        </div>
      </div>
      </div>

      {sent && (
        <OrderSent sent={sent} onDone={onDone} onFixRest={error ? () => setSent(null) : undefined} />
      )}
    </div>
  );
}
