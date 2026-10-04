import { useState } from "react";
import type { OutletView, PlacedOrder } from "@shared/domain/types";
import { Icon, Notice } from "@shared/ui";
import { units, changeDeadline, clock, dayLabel, depotToday, hhmm, temperatureLabel } from "../../data/format.ts";
import { Badge, Button, Facts, Modal } from "../../ui.tsx";
import { useT } from "../../i18n.tsx";

// Figma "04 Order sent". Totals are the warehouse's, returned with the order,
// never summed from product lines here. An order kept on the phone is said to
// be exactly that, not sent.

export type Sent = { orders: PlacedOrder[]; queued: boolean; requestedDate: string };

export default function OrderSent({
  sent,
  outlet,
  onDone,
  onEdit,
  onFixRest,
  onAccept,
}: {
  sent: Sent;
  outlet: OutletView | null;
  onDone: () => void;
  /** Open the first placed order for a change. */
  onEdit?: (order: PlacedOrder) => void;
  onFixRest?: () => void;
  /** Take what a partial reservation locked; answers an error message or null. */
  onAccept?: (order: PlacedOrder) => Promise<string | null>;
}): React.JSX.Element {
  const t = useT();
  const { orders, queued } = sent;
  const unknown = orders.some((o) => o.status === "STOCK_UNKNOWN");
  const partial = orders.some((o) => o.shortfall);
  const offline = orders.length === 0 && queued;
  const title = t(offline ? "Saved on this phone" : partial ? "Only part could be reserved" : unknown ? "Order kept, stock not checked" : "Order sent");
  const rolled = orders.find((o) => o.dateRolled);
  const deliveryDate = orders[0]?.deliveryDate ?? sent.requestedDate;

  return (
    <Modal label={title} onClose={onDone}>
      <Badge>
        <Icon name={offline ? "clock" : "check"} />
      </Badge>
      <div className="flex flex-col items-center gap-1 text-center">
        <h2 className="text-[26px] font-medium text-black">{title}</h2>
        <p className="text-[14px] text-go-muted">
          {offline
            ? t("You are offline. It is sent the moment the connection returns; keep this app open or come back to it.")
            : unknown
              ? t("The warehouse is not answering. Waypoint confirms it once stock is checked.")
              : t("Waypoint has your order for {day}.", { day: dayLabel(deliveryDate) })}
        </p>
      </div>
      {orders.length > 0 && (
        <Facts
          rows={[
            ...orders.map((o) => ({
              label: `${o.orderRef}${o.temperature ? ` · ${t(temperatureLabel(o.temperature))}` : ""}`,
              value: o.itemCount === null ? t("Totals follow once stock is checked") : units(o.itemCount),
            })),
            {
              label: t("Delivery window"),
              value: `${dayLabel(deliveryDate)}${outlet ? ` · ${hhmm(outlet.windowOpen)}-${hhmm(outlet.windowClose)}` : ""}`,
            },
            { label: t("You can change it until"), value: changeDeadline(deliveryDate, depotToday()) },
          ]}
        />
      )}
      {rolled && <p className="text-[13px] text-go-warning-text">{t("Moved from {day}, which is not a delivery day.", { day: dayLabel(rolled.requestedDate) })}</p>}
      {onAccept && orders.filter((o) => o.shortfall).map((o) => <ShortfallNotice key={o.orderId} order={o} onAccept={onAccept} />)}
      {queued && orders.length > 0 && <p className="text-[14px] text-go-warning-text">{t("Part of this order is saved on this phone and sent when the connection returns.")}</p>}
      <div className="flex gap-2.5">
        {onFixRest ? (
          <Button tone="plain" large onClick={onFixRest}>
            {t("Fix the rest")}
          </Button>
        ) : (
          onEdit &&
          orders.length > 0 && (
            <Button tone="plain" large onClick={() => onEdit(orders[0]!)}>
              {t("Edit order")}
            </Button>
          )
        )}
        <Button large onClick={onDone}>
          {t("Back to home")}
        </Button>
      </div>
    </Modal>
  );
}

// STK-13: the warehouse locked what it had until `expiresAt`. The store takes it
// as it is or cancels the order from Orders; nothing is split or guessed here.
function ShortfallNotice({ order, onAccept }: { order: PlacedOrder; onAccept: (order: PlacedOrder) => Promise<string | null> }): React.JSX.Element {
  const [state, setState] = useState<{ busy: boolean; done: boolean; error: string | null }>({ busy: false, done: false, error: null });
  const t = useT();
  const short = order.shortfall!;
  const accept = async () => {
    setState({ busy: true, done: false, error: null });
    const error = await onAccept(order);
    setState({ busy: false, done: error === null, error });
  };
  if (state.done) return <Notice tone="info" live title={t("{ref}: the reserved quantities are confirmed.", { ref: order.orderRef })} />;
  return (
    <Notice
      tone="warning"
      live
      title={t("{ref}: held until {time}", { ref: order.orderRef, time: clock(short.expiresAt) })}
      action={
        <Button large busy={state.busy} onClick={() => void accept()}>
          {t(state.busy ? "Accepting…" : "Accept reserved")}
        </Button>
      }
    >
      <ul className="flex flex-col gap-0.5">
        {short.lines
          .filter((l) => l.reserved < l.requested)
          .map((l) => (
            <li key={l.productId}>
              {l.productId}: {t("{n} of {total}", { n: l.reserved, total: l.requested })}
            </li>
          ))}
      </ul>
      {state.error && <p className="mt-1 font-medium text-go-danger-strong">{state.error}</p>}
      <p className="mt-1">{t("Accept before then, or cancel the order from Orders.")}</p>
    </Notice>
  );
}
