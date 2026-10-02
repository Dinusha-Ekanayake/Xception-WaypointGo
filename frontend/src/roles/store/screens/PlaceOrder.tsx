"use client";

import { useEffect, useMemo, useState } from "react";
import { ApiError } from "@shared/api/problem";
import { useResource } from "@shared/api/useResource";
import { OrderCommandKind, type LineAvailability, type OrderView, type PlacedOrder, type OutletView, type Temperature } from "@shared/domain/types";
import { Icon, Notice, cx } from "@shared/ui";
import type { StoreGateway } from "../data/gateway.ts";
import { addDays, clock, cutoffLabel, dayLabel, depotToday, hhmm, longDay, untilCutoff } from "../data/format.ts";
import { clearDraft, loadDraft, saveDraft } from "../data/draft.ts";
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
  onEdit,
  onBack,
}: {
  gateway: StoreGateway;
  outlet: OutletView | null;
  orders: OrderView[];
  amend: OrderView | null;
  warehouseDown: boolean;
  commands: Commands;
  onDone: () => void;
  /** Open a just-placed order for a change. */
  onEdit: (orderId: string) => void;
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
  const [draftNote, setDraftNote] = useState<string | null>(null);

  const brand = outlet?.brandCode ?? "";
  const catalogue = useResource(brand ? (s) => gateway.catalogue(brand, s) : null, brand);
  const day = useResource((s) => gateway.calendar(date, s), date);
  // The server's rule decides the day (cutoff, closures, calendar); the calendar
  // alone is the fallback when that read fails, and only names the festival.
  const outletId = outlet?.outletId ?? "";
  const when = useResource(!amend && outletId ? (s) => gateway.deliveryDate(outletId, date, s) : null, `${outletId}|${date}`);

  // The last quantity this outlet ordered of each product, as a guide.
  const usual = useMemo(() => {
    const m = new Map<string, number>();
    for (const o of [...orders].sort((a, b) => a.placedAt.localeCompare(b.placedAt))) for (const l of o.lines) m.set(l.productId, l.quantity);
    return m;
  }, [orders]);

  // A saved draft comes back when a new order is opened, never into a change to a placed one.
  const outletKey = outlet?.outletId ?? "";
  useEffect(() => {
    if (amend || !outletKey) return;
    const draft = loadDraft(outletKey);
    if (!draft) return;
    setQty(draft.quantities);
    setDraftNote(`Draft from ${clock(draft.savedAt)} restored`);
  }, [amend, outletKey]);

  const saveAsDraft = () => {
    setDraftNote(saveDraft(outletKey, qty) ? "Draft saved on this device" : "This device cannot keep a draft");
  };

  const products = catalogue.data ?? [];
  const tempOf = (id: string) => products.find((p) => p.productId === id)?.temperature ?? "ambient";
  const count = (t: Temperature) => Object.entries(qty).filter(([id, n]) => n > 0 && tempOf(id) === t);
  const shown = products.filter((p) => (p.temperature ?? "ambient") === temp && p.productId.toLowerCase().includes(search.trim().toLowerCase()));
  const classes: Temperature[] = amend ? [amend.temperature] : ["ambient", "chilled"];
  const cases = classes.reduce((s, t) => s + count(t).reduce((a, [, n]) => a + n, 0), 0);
  const rolled = amend
    ? amend.dateRolled
      ? amend.deliveryDate
      : null
    : when.data
      ? when.data.delivery !== date
        ? when.data.delivery
        : null
      : day.data && !day.data.operating
        ? day.data.nextOperatingDay
        : null;
  const reasons = when.data?.reasons ?? (rolled ? ["non_operating"] : []);
  const festival = day.data?.day && "festival" in day.data.day ? day.data.day.festival : null;

  const acceptShortfall = async (o: PlacedOrder): Promise<string | null> => {
    const outcome = await commands.run(OrderCommandKind.acceptShortfall, { orderId: o.orderId }, o.rowVersion);
    return outcome.ok ? null : conflictMessage(outcome.error);
  };

  const submit = async () => {
    setError(null);
    setShort([]);
    const placed: PlacedOrder[] = [];
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
      else placed.push(outcome.ack.result as PlacedOrder);
      setQty((q) => Object.fromEntries(Object.entries(q).filter(([id]) => tempOf(id) !== t)));
    }
    clearDraft(outletKey);
    setDraftNote(null);
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
        {draftNote && (
          <p role="status" className="text-[13px] text-go-teal">
            {draftNote}
          </p>
        )}
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
      {rolled && !amend && (
        <Notice tone="warning" live title={`This order will arrive ${dayLabel(rolled)}, not ${dayLabel(date)}.`}>
          {reasons.includes("cutoff") && "Orders for that day closed at 4:00 PM. "}
          {reasons.includes("closed") && "The depot has closed ordering for that day. "}
          {reasons.includes("non_operating") && `Depots are closed that day${festival ? ` (${festival})` : ""}. `}
          You can still order now; it goes on the next run.
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
        <input value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Add an item - search by name or SKU" className="min-w-0 flex-1 bg-transparent text-black outline-none placeholder:text-go-muted" />
      </label>

      {error && <Notice tone="danger" live title={error} />}
      {catalogue.error && <Notice tone="danger" title="Could not load the catalogue">{catalogue.error.message}</Notice>}

      {/* Desktop column heads, as in "03 Place order". */}
      <div aria-hidden className="hidden grid-cols-[minmax(0,1fr)_72px_168px] gap-2 px-4 text-[13px] text-go-muted lg:grid">
        <span>Item</span>
        <span>Usual</span>
        <span className="text-center">Order</span>
      </div>
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
                  {!p.verifiedRealSku && <span title="Reconstructed from order totals, not a confirmed product">inferred</span>}
                  <span className="lg:hidden">
                    {!p.verifiedRealSku && " · "}
                    {u !== undefined ? `usual ${u}` : "not ordered before"}
                  </span>
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
              <span className="hidden w-[72px] shrink-0 text-[15px] text-go-muted lg:block">{u ?? "-"}</span>
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
          <div className="flex flex-col rounded-[16px] bg-go-mint/70 px-4 py-3">
            <span className="text-[12px] text-go-muted">Delivery</span>
            <span className="text-[16px] font-medium text-black">
              {dayLabel(rolled ?? date)}
              {outlet ? ` · ${hhmm(outlet.windowOpen)}-${hhmm(outlet.windowClose)}` : ""}
            </span>
          </div>
          {/* Desktop: one line per class with its own count, as in "03 Place order". */}
          <ul className="hidden flex-col gap-2 text-[14px] lg:flex">
            {classes.map((t) => {
              const lines = count(t);
              return (
                <li key={t} className="flex justify-between gap-3">
                  <span className="text-black">{t === "chilled" ? "Chilled order" : "Ambient order"}</span>
                  <span className="text-go-muted">
                    {lines.length} {lines.length === 1 ? "item" : "items"} · {lines.reduce((s, [, n]) => s + n, 0)} cases
                  </span>
                </li>
              );
            })}
          </ul>
          <p className="flex items-baseline justify-between gap-3 border-t border-[#dfe7e6] pt-3 text-[28px] leading-tight font-semibold text-black lg:text-[18px]">
            <span className="hidden text-[15px] font-medium lg:inline">Total</span>
            <span>
              {cases === 1 ? "1 case" : `${cases} cases`}
              <span className="lg:hidden">{!amend && ` · ${classes.filter((t) => count(t).length > 0).length || 0} orders`}</span>
            </span>
          </p>
          <div className="flex gap-2.5 lg:flex-col-reverse">
            <Button tone="plain" large onClick={onBack}>
              Cancel
            </Button>
            {!amend && (
              <Button tone="plain" large disabled={cases === 0} onClick={saveAsDraft}>
                Save draft
              </Button>
            )}
            <Button large disabled={cases === 0 || commands.busy || !outlet} onClick={() => void submit()}>
              {commands.busy ? "Sending…" : amend ? "Save change" : "Submit order"}
            </Button>
          </div>
        </div>
      </div>
      </div>

      {sent && (
        <OrderSent
          sent={sent}
          outlet={outlet}
          onDone={onDone}
          onEdit={(o) => onEdit(o.orderId)}
          onFixRest={error ? () => setSent(null) : undefined}
          onAccept={acceptShortfall}
        />
      )}
    </div>
  );
}
