"use client";

import { useEffect, useMemo, useState } from "react";
import { ApiError } from "@shared/api/problem";
import { useResource } from "@shared/api/useResource";
import { OrderCommandKind, type LineAvailability, type OrderView, type PlacedOrder, type OutletView, type Temperature } from "@shared/domain/types";
import { Notice, cx } from "@shared/ui";
import type { StoreGateway } from "../../data/gateway.ts";
import { addDays, clock, cutoffLabel, dayLabel, depotToday, hhmm, longDay, untilCutoff, units } from "../../data/format.ts";
import { clearDraft, loadDraft, saveDraft } from "../../data/draft.ts";
import { classOf, rowsOf, usualOf } from "../../data/lines.ts";
import { conflictMessage, type useCommands } from "../../data/useCommands.ts";
import { BackButton, Muted, Toast } from "../../ui.tsx";
import OrderLines from "./OrderLines.tsx";
import OrderSent, { type Sent } from "./OrderSent.tsx";
import OrderSummary from "./OrderSummary.tsx";
import DateStrip from "./DateStrip.tsx";
import RideAlongHint from "./RideAlongHint.tsx";

// Figma "03 Place order", "03b chilled", "03c add item", "03d item added" and
// "03e draft saved". Chilled and ambient are separate orders (R-ORD-06), so one
// submit places one order per class. The warehouse decides stock: a short line
// rejects the placement with per-line availability (D-F), shown here so the
// manager can adjust and send again. A delivery date that falls on a
// non-operating day rolls forward, and the rolled date is shown before the
// order is sent (R-ORD-08, D-I).

type Commands = ReturnType<typeof useCommands>;
type Note = { title: string; detail?: string; tone?: "ok" | "danger" };

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
  const [added, setAdded] = useState<string[]>([]);
  const [fresh, setFresh] = useState<string | null>(null);
  const [short, setShort] = useState<LineAvailability[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [sent, setSent] = useState<Sent | null>(null);
  const [restored, setRestored] = useState<string | null>(null);
  const [note, setNote] = useState<Note | null>(null);
  const [shared, setShared] = useState<string[]>([]);

  useEffect(() => {
    if (!note) return;
    const timer = setTimeout(() => setNote(null), 6000);
    return () => clearTimeout(timer);
  }, [note]);

  const brand = outlet?.brandCode ?? "";
  const catalogue = useResource(brand ? (s) => gateway.catalogue(brand, s) : null, brand);
  const day = useResource((s) => gateway.calendar(date, s), date);
  // The server's rule decides the day (cutoff, closures, calendar); the calendar
  // alone is the fallback when that read fails, and only names the festival.
  const outletId = outlet?.outletId ?? "";
  const when = useResource(!amend && outletId ? (s) => gateway.deliveryDate(outletId, date, s) : null, `${outletId}|${date}`);

  const usual = useMemo(() => usualOf(orders), [orders]);

  // A saved draft comes back when a new order is opened, never into a change to a placed one.
  const outletKey = outlet?.outletId ?? "";
  useEffect(() => {
    if (amend || !outletKey) return;
    const draft = loadDraft(outletKey);
    if (!draft) return;
    setQty(draft.quantities);
    setRestored(`Draft from ${clock(draft.savedAt)} restored`);
  }, [amend, outletKey]);

  const saveAsDraft = () => {
    setNote(
      saveDraft(outletKey, qty)
        ? { title: `Draft saved · ${clock(new Date().toISOString())}`, detail: left > 0 ? "Submit before 16:00 to send it" : "Nothing is sent until you submit" }
        : { title: "This device cannot keep a draft", detail: "Submit the order to keep it", tone: "danger" },
    );
  };

  const products = catalogue.data ?? [];
  const tempOf = (id: string) => classOf(id, products, usual);
  const count = (t: Temperature) => Object.entries(qty).filter(([id, n]) => n > 0 && tempOf(id) === t);
  const lines = (t: Temperature) => ({ items: count(t).length, units: count(t).reduce((s, [, n]) => s + n, 0) });
  const classes: Temperature[] = amend ? [amend.temperature] : ["ambient", "chilled"];
  const rows = rowsOf(temp, products, usual, qty, added);
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
      const send = count(t).map(([productId, quantity]) => ({ productId, quantity }));
      if (send.length === 0) continue;
      const outcome = amend
        ? await commands.run(OrderCommandKind.amend, { orderId: amend.orderId, lines: send }, amend.rowVersion)
        : await commands.run(OrderCommandKind.place, { outletId: outlet?.outletId ?? "", requestedDate: date, lines: send }, null);
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
    setRestored(null);
    setSent({ orders: placed, queued, requestedDate: date });
  };

  const setLine = (id: string, n: number) => {
    setQty((q) => ({ ...q, [id]: n }));
    setShort((s) => s.filter((x) => x.productId !== id || n > x.available));
  };

  // "03d Item added": the row lands at the foot of the list with one case, tinted.
  const add = (id: string) => {
    const n = Math.max(qty[id] ?? 0, 1);
    setAdded((a) => (a.includes(id) ? a : [...a, id]));
    setLine(id, n);
    setFresh(id);
    setNote({ title: `${id} added · ${units(n)}`, detail: "Use − and + to change the amount" });
  };

  return (
    <div className="flex flex-col gap-4 pb-44 lg:pb-0">
      <BackButton onClick={onBack} />
      <div className="flex flex-col gap-1">
        <h1 className="text-[32px] leading-tight font-medium text-black">{amend ? `Change ${amend.orderRef}` : "Place order"}</h1>
        <Muted>
          {longDay(today)} · orders close 16:00 · {cutoffLabel(left)}
        </Muted>
        {restored && (
          <p role="status" className="text-[13px] text-go-teal">
            {restored}
          </p>
        )}
      </div>

      {/* Desktop: the list on the left and the summary card on the right, as in "03 Place order". */}
      <div className="flex flex-col gap-4 lg:grid lg:grid-cols-[minmax(0,1fr)_380px] lg:items-start lg:gap-6">
        <div className="flex min-w-0 flex-col gap-4">
          {!amend && <DateStrip gateway={gateway} outletId={outletId} first={first} date={date} shared={shared} onPick={setDate} />}
          {rolled && !amend && (
            <Notice tone="warning" live title={`This order will arrive ${dayLabel(rolled)}, not ${dayLabel(date)}.`}>
              {reasons.includes("cutoff") && "Orders for that day closed at 16:00. "}
              {reasons.includes("closed") && "The depot has closed ordering for that day. "}
              {reasons.includes("non_operating") && `Depots are closed that day${festival ? ` (${festival})` : ""}. `}
              You can still order now; it goes on the next run.
            </Notice>
          )}
          {!amend && <RideAlongHint gateway={gateway} outletId={outletId} brandCode={brand} date={date} onPick={setDate} onDays={setShared} />}
          {warehouseDown && (
            <Notice tone="warning" title="Stock can't be checked right now">
              The order is kept as &ldquo;stock not checked&rdquo; until the warehouse answers. It is not confirmed yet.
            </Notice>
          )}
          {error && <Notice tone="danger" live title={error} />}
          {catalogue.error && (
            <Notice tone="warning" title="Could not load the catalogue">
              Your usual items are listed; new items can be added once it loads. {catalogue.error.message}
            </Notice>
          )}

          <OrderLines
            classes={classes}
            temperature={temp}
            onTemperature={setTemp}
            itemCount={(t) => count(t).length}
            rows={rows}
            products={products}
            usual={usual}
            quantities={qty}
            short={short}
            fresh={fresh}
            loading={catalogue.loading}
            onLine={setLine}
            onAdd={add}
          />
        </div>

        <OrderSummary
          delivery={`${dayLabel(rolled ?? date)}${outlet ? ` · ${hhmm(outlet.windowOpen)}-${hhmm(outlet.windowClose)}` : ""}`}
          classes={classes}
          lines={lines}
          amend={amend !== null}
          busy={commands.busy}
          canSubmit={outlet !== null}
          onCancel={onBack}
          onSaveDraft={saveAsDraft}
          onSubmit={() => void submit()}
        />
      </div>

      <Toast note={note} />
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
