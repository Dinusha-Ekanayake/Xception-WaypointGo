"use client";

import { useState } from "react";
import { useResource } from "@shared/api/useResource";
import { OrderCommandKind, type OrderView } from "@shared/domain/types";
import { Notice } from "@shared/ui";
import type { StoreGateway } from "../data/gateway.ts";
import { ORDER_STATUS, units, clock, dayLabel, depotToday, editable, planNote, temperatureLabel } from "../data/format.ts";
import { conflictMessage, type useCommands } from "../data/useCommands.ts";
import { Button, Chip, Sheet } from "../ui.tsx";

// One order: its lines, its status history with reasons, and the changes the
// store can still make. A stale version is shown as "this order changed;
// review", never overwritten.

export default function OrderSheet({
  gateway,
  order,
  commands,
  onAmend,
  onReceive,
  onDeferred,
  onClose,
}: {
  gateway: StoreGateway;
  order: OrderView;
  commands: ReturnType<typeof useCommands>;
  onAmend: () => void;
  onReceive: () => void;
  /** Open "09 Order deferred": where it moved and why. */
  onDeferred: () => void;
  onClose: () => void;
}): React.JSX.Element {
  const history = useResource((s) => gateway.history(order.orderId, s), `${order.orderId}:${order.rowVersion}:${order.status}`);
  const [cancelling, setCancelling] = useState(false);
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [queued, setQueued] = useState(false);
  const s = ORDER_STATUS[order.status];

  const cancel = async () => {
    const outcome = await commands.run(OrderCommandKind.cancel, { orderId: order.orderId, reason: reason.trim() }, order.rowVersion);
    if (!outcome.ok) setError(conflictMessage(outcome.error));
    else if (outcome.queued) setQueued(true);
    else onClose();
  };

  return (
    <Sheet label={order.orderRef} onClose={onClose}>
      <div className="flex items-center gap-2">
        <h2 className="flex-1 text-[24px] font-medium text-black">{order.orderRef}</h2>
        <Chip tone={s.tone}>{s.label}</Chip>
      </div>
      <p className="text-[15px] text-go-muted">
        {temperatureLabel(order.temperature)} · {units(order.itemCount)} · {order.weightKg} kg · {order.volumeM3} m³ · delivery {dayLabel(order.deliveryDate)}
        {order.dateRolled && ` (moved from ${dayLabel(order.requestedDate)}, not a delivery day)`}
      </p>
      {planNote(order) && <Notice tone="info" title={planNote(order)!}>Dispatch has put your order on a vehicle for that day.</Notice>}
      {error && <Notice tone="danger" live title={error} />}
      {queued && <Notice tone="warning" live title="Cancellation saved on this phone. It is sent when the connection returns." />}

      <ul className="flex flex-col gap-1 rounded-[20px] bg-go-canvas p-4 text-[15px]">
        {order.lines.map((l) => (
          <li key={l.productId} className="flex justify-between gap-3">
            <span>{l.productId}</span>
            <span className="font-medium">{l.quantity}</span>
          </li>
        ))}
      </ul>

      <section aria-label="History" className="flex flex-col gap-2">
        <h3 className="text-[15px] font-medium text-black">History</h3>
        <ol className="flex flex-col gap-2 border-l-2 border-go-mint pl-4">
          {(history.data ?? []).map((h, i) => (
            <li key={i} className="flex flex-col">
              <span className="text-[15px] font-medium text-black">{ORDER_STATUS[h.to].label}</span>
              <span className="text-[13px] text-go-muted">
                {dayLabel(depotToday(new Date(h.at)))} {clock(h.at)} · {h.reason}
              </span>
            </li>
          ))}
          {history.loading && <li className="text-[13px] text-go-muted">Loading…</li>}
        </ol>
      </section>

      {order.status === "DEFERRED" && (
        <Button tone="plain" onClick={onDeferred}>
          Why it moved
        </Button>
      )}
      {order.status === "DELIVERED" && <Button onClick={onReceive}>Receive this delivery</Button>}
      {/* An answered receipt: what was counted and where the handover PIN stands (R-RCP-09). */}
      {order.status === "RECEIVED" && (
        <Button tone="plain" onClick={onReceive}>
          Receipt and handover PIN
        </Button>
      )}
      {editable(order.status) && !cancelling && (
        <div className="flex gap-2.5">
          <Button tone="plain" onClick={() => setCancelling(true)}>
            Cancel order
          </Button>
          <Button onClick={onAmend}>Change order</Button>
        </div>
      )}
      {cancelling && (
        <div className="flex flex-col gap-2">
          <label className="flex flex-col gap-1 text-[13px] text-go-muted">
            Why are you cancelling? (required)
            <textarea
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              rows={2}
              className="rounded-[16px] border border-[#dfe7e6] p-3 text-[15px] text-black"
            />
          </label>
          <div className="flex gap-2.5">
            <Button tone="plain" onClick={() => setCancelling(false)}>
              Keep order
            </Button>
            <Button tone="danger" disabled={!reason.trim() || commands.busy} onClick={() => void cancel()}>
              Cancel order
            </Button>
          </div>
        </div>
      )}
    </Sheet>
  );
}
