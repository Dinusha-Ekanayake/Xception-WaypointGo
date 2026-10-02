import type { DeliveryRecordView, OrderView, OutletView } from "@shared/domain/types";
import { cases, clock, expectedAt, hhmm, minutesLabel } from "../data/format.ts";

// The driver, the stop and the time of "02 Home" and "05 Delivery tracking". The
// time is the plan's, moved by the delay Execution has observed (R-EXE-15), and
// the screen says which. Without a stop record (Execution not answering, or the
// trip not released yet) it falls back to the outlet's own window, which is all
// that is known. A driver the system cannot name is said so, not left blank.

export default function NextStop({
  stop,
  order,
  outlet,
}: {
  stop: DeliveryRecordView | null;
  order: OrderView;
  outlet: OutletView | null;
}): React.JSX.Element {
  const window = outlet ? `${hhmm(outlet.windowOpen)}-${hhmm(outlet.windowClose)}` : "-";
  if (!stop) {
    return (
      <div className="flex flex-col items-center rounded-[20px] bg-go-canvas px-4 pt-3 pb-3.5">
        <span className="text-[15px] text-black">Expected in your window</span>
        <span className="text-[40px] leading-tight font-semibold text-black">{window}</span>
        <span className="text-[13px] text-go-muted">
          {order.orderRef} · {cases(order.itemCount)}
        </span>
      </div>
    );
  }

  const arrived = stop.arrivedAt;
  const eta = expectedAt(stop);
  const moved = stop.expectedArrival !== null;
  return (
    <div className="flex flex-col gap-3 rounded-[20px] bg-go-canvas p-4 sm:flex-row sm:items-center">
      <div className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className="text-[13px] text-go-muted">
          Driver{stop.driver?.employeeCode ? ` · ${stop.driver.employeeCode}` : ""}
        </span>
        <span className="truncate text-[26px] leading-tight font-medium text-black">{stop.driver?.displayName ?? "Name not available"}</span>
        <span className="text-[13px] text-go-muted">
          {stop.vehicleId}
          {stop.tripStopCount !== null ? ` · stop ${stop.stopSequence} of ${stop.tripStopCount}` : ` · stop ${stop.stopSequence}`}
          {` · ${order.orderRef} · ${cases(order.itemCount)}`}
        </span>
      </div>
      <div className="flex flex-col items-center rounded-[16px] bg-white px-6 py-3 text-center">
        <span className="text-[13px] text-go-muted">{arrived ? "Arrived" : moved ? "ETA (running late)" : "ETA"}</span>
        <span className="text-[34px] leading-tight font-semibold text-black">{clock(arrived ?? eta)}</span>
        {!arrived && <span className="text-[13px] text-go-muted">in {minutesLabel(eta)}</span>}
        <span className="text-[12px] text-go-muted">window {window}</span>
      </div>
    </div>
  );
}
