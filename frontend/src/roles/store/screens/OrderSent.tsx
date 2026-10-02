import { useState } from "react";
import type { PlacedOrder } from "@shared/domain/types";
import { Icon, Notice } from "@shared/ui";
import { cases, clock, dayLabel, temperatureLabel } from "../data/format.ts";
import { Button, Sheet } from "../ui.tsx";

// Figma "04 Order sent". Totals are the warehouse's, returned with the order,
// never summed from product lines here. An order kept on the phone is said to
// be exactly that, not sent.

export type Sent = { orders: PlacedOrder[]; queued: boolean; requestedDate: string };

export default function OrderSent({
  sent,
  onDone,
  onFixRest,
  onAccept,
}: {
  sent: Sent;
  onDone: () => void;
  onFixRest?: () => void;
  /** Take what a partial reservation locked; answers an error message or null. */
  onAccept?: (order: PlacedOrder) => Promise<string | null>;
}): React.JSX.Element {
  const { orders, queued } = sent;
  const unknown = orders.some((o) => o.status === "STOCK_UNKNOWN");
  const partial = orders.some((o) => o.shortfall);
  const title =
    orders.length === 0 && queued ? "Saved on this phone" : partial ? "Only part could be reserved" : unknown ? "Order kept, stock not checked" : "Order sent";
  const rolled = orders.find((o) => o.dateRolled);

  return (
    <Sheet label={title} onClose={onDone}>
      <div className="flex items-start gap-4">
        <span className="flex size-14 shrink-0 items-center justify-center rounded-full bg-go-canvas">
          <Icon name={queued && orders.length === 0 ? "clock" : "check"} />
        </span>
        <div className="flex flex-col gap-1">
          <h2 className="text-[22px] font-medium text-black">{title}</h2>
          <p className="text-[15px] text-go-muted">
            {orders.length === 0 && queued
              ? "You are offline. It is sent the moment the connection returns; keep this app open or come back to it."
              : unknown
                ? "The warehouse is not answering. Waypoint confirms it once stock is checked."
                : `Waypoint has your order for ${dayLabel(orders[0]?.deliveryDate ?? sent.requestedDate)}.`}
          </p>
        </div>
      </div>
      {orders.length > 0 && (
        <dl className="flex flex-col gap-3 rounded-[20px] bg-go-canvas p-[18px] text-[15px]">
          {orders.map((o) => (
            <div key={o.orderId} className="flex flex-col">
              <dt className="text-go-muted">
                {o.orderRef}
                {o.temperature ? ` · ${temperatureLabel(o.temperature)}` : ""}
              </dt>
              <dd className="font-semibold text-black">
                {o.itemCount === null || o.weightKg === null || o.volumeM3 === null
                  ? "Totals follow once stock is checked"
                  : `${cases(o.itemCount)} · ${o.weightKg} kg · ${o.volumeM3} m³`}
              </dd>
            </div>
          ))}
          <div className="flex justify-between gap-3">
            <dt className="text-go-muted">Delivery day</dt>
            <dd className="text-right font-semibold text-black">{dayLabel(orders[0]!.deliveryDate)}</dd>
          </div>
          {rolled && <p className="text-[13px] text-go-warning-text">Moved from {dayLabel(rolled.requestedDate)}, which is not a delivery day.</p>}
          <div className="flex justify-between gap-3">
            <dt className="text-go-muted">You can change it until</dt>
            <dd className="text-right font-semibold text-black">4:00 PM the day before</dd>
          </div>
        </dl>
      )}
      {onAccept && orders.filter((o) => o.shortfall).map((o) => <ShortfallNotice key={o.orderId} order={o} onAccept={onAccept} />)}
      {queued && orders.length > 0 && <p className="text-[14px] text-go-warning-text">Part of this order is saved on this phone and sent when the connection returns.</p>}
      <div className="flex gap-2.5">
        {onFixRest && (
          <Button tone="plain" large onClick={onFixRest}>
            Fix the rest
          </Button>
        )}
        <Button large onClick={onDone}>
          Done
        </Button>
      </div>
    </Sheet>
  );
}

// STK-13: the warehouse locked what it had until `expiresAt`. The store takes it
// as it is or cancels the order from Orders; nothing is split or guessed here.
function ShortfallNotice({ order, onAccept }: { order: PlacedOrder; onAccept: (order: PlacedOrder) => Promise<string | null> }): React.JSX.Element {
  const [state, setState] = useState<{ busy: boolean; done: boolean; error: string | null }>({ busy: false, done: false, error: null });
  const short = order.shortfall!;
  const accept = async () => {
    setState({ busy: true, done: false, error: null });
    const error = await onAccept(order);
    setState({ busy: false, done: error === null, error });
  };
  if (state.done) return <Notice tone="info" live title={`${order.orderRef}: the reserved quantities are confirmed.`} />;
  return (
    <Notice
      tone="warning"
      live
      title={`${order.orderRef}: held until ${clock(short.expiresAt)}`}
      action={
        <Button large onClick={() => void accept()}>
          {state.busy ? "Accepting…" : "Accept reserved"}
        </Button>
      }
    >
      <ul className="flex flex-col gap-0.5">
        {short.lines
          .filter((l) => l.reserved < l.requested)
          .map((l) => (
            <li key={l.productId}>
              {l.productId}: {l.reserved} of {l.requested}
            </li>
          ))}
      </ul>
      {state.error && <p className="mt-1 font-medium text-go-danger-strong">{state.error}</p>}
      <p className="mt-1">Accept before then, or cancel the order from Orders.</p>
    </Notice>
  );
}
