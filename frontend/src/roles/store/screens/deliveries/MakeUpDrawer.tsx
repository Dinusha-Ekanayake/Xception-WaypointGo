"use client";

import { useResource } from "@shared/api/useResource";
import type { DeliveryRecordView, IssueView, OrderView, OutletView } from "@shared/domain/types";
import { Icon, cx } from "@shared/ui";
import type { StoreGateway } from "../../data/gateway.ts";
import { units, clock, depotToday, expectedAt, hhmm, temperatureLabel } from "../../data/format.ts";
import { makeUpSteps } from "../../data/runs.ts";
import { Button, Drawer } from "../../ui.tsx";

// Figma "05b make-up delivery": what a redelivery carries and how far it has
// got. A redelivery resends the whole original order (A-24), so the lines are
// its own. Calls are not built, so there is no call button.

export default function MakeUpDrawer({
  gateway,
  order,
  issue,
  record,
  outlet,
  onClose,
}: {
  gateway: StoreGateway;
  order: OrderView;
  /** The problem on the original order that the make-up answers. */
  issue: IssueView | null;
  /** Its stop today, once the vehicle has left the depot. */
  record: DeliveryRecordView | null;
  outlet: OutletView | null;
  onClose: () => void;
}): React.JSX.Element {
  const timeline = useResource((s) => gateway.history(order.orderId, s), order.orderId);
  const window = outlet ? `${hhmm(outlet.windowOpen)}-${hhmm(outlet.windowClose)}` : null;
  const steps = makeUpSteps({ order, issue, timeline: timeline.data ?? [], record, dock: outlet?.dockType ?? null, window, today: depotToday() });
  const title = record ? `${record.vehicleId} · make-up delivery` : "Make-up delivery";
  const about = [
    order.temperature === "chilled" ? "Refrigerated vehicle" : null,
    record ? `Expected ${clock(expectedAt(record))}` : window ? `Window ${window}` : null,
  ].filter(Boolean);

  return (
    <Drawer label={title} onClose={onClose}>
      <div className="flex items-start gap-3">
        <div className="flex min-w-0 flex-1 flex-col gap-0.5">
          <h2 className="text-[22px] font-medium text-black">{title}</h2>
          {about.length > 0 && <p className="text-[13px] text-go-muted">{about.join(" · ")}</p>}
        </div>
        <button type="button" aria-label="Close" onClick={onClose} className="flex size-12 shrink-0 items-center justify-center rounded-full bg-go-canvas">
          <Icon name="close" />
        </button>
      </div>

      <section aria-label="What it carries" className="flex flex-col gap-2 rounded-[16px] bg-go-mint/40 px-4 py-3">
        <span className="text-[15px] font-medium text-black">{order.orderRef}</span>
        <ul className="flex flex-col gap-1.5">
          {order.lines.map((l) => (
            <li key={l.productId} className="flex flex-col">
              <span className="text-[15px] text-black">{l.productId}</span>
              <span className="text-[13px] text-go-muted">
                {units(l.quantity)} · {temperatureLabel(order.temperature)} · <span title="Reconstructed from order totals, not a confirmed product">inferred</span>
              </span>
            </li>
          ))}
        </ul>
      </section>

      <section aria-label="Timeline" className="flex flex-col gap-3">
        <h3 className="text-[15px] font-medium text-black">Timeline</h3>
        <ol className="flex flex-col gap-3">
          {steps.map((s) => (
            <li key={s.title} className="flex gap-3">
              <span aria-hidden className={cx("mt-1 size-3 shrink-0 rounded-full", s.done ? "bg-go-success" : "border-2 border-[#a9b5b3]")} />
              <span className="flex min-w-0 flex-col">
                <span className={cx("text-[15px]", s.done ? "font-medium text-black" : "text-go-muted")}>
                  {s.title}
                  <span className="sr-only">{s.done ? " (done)" : " (to come)"}</span>
                </span>
                <span className="text-[13px] text-go-muted">{s.detail}</span>
              </span>
            </li>
          ))}
        </ol>
        {timeline.error && <p className="text-[13px] text-go-muted">The loading time could not be read; the steps show what is known.</p>}
      </section>

      <div className="mt-auto pt-2">
        <Button onClick={onClose}>Close</Button>
      </div>
    </Drawer>
  );
}
