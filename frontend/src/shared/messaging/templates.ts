import type { DecisionView } from "../domain/issues.ts";
import type { RunSheetStopView } from "../domain/execution.ts";
import type { MessageAudience } from "../domain/messaging.ts";
import { clock } from "../wording/time.ts";

// Ready replies for the dispatcher (roadmap FF-08, first version). Which ones
// are offered is decided by code from the trip's state, and every name, time
// and number is filled by code from the run sheet and the stores' answers: no
// model writes a number or a name. The dispatcher picks one, may edit it, and
// sends; nothing is sent by itself. A reply to the stores' channel never names
// a store or an order (R-MSG-08).

export const TEMPLATE_VERSION = 1;

export type Reply = {
  id: string;
  version: number;
  /** What the chip says. */
  label: string;
  to: MessageAudience;
  outletId: string | null;
  body: string;
};

export type TripState = {
  stops: RunSheetStopView[];
  /** The day's questions to stores on this trip. */
  decisions: DecisionView[];
  /** The stores' channel is open: the driver has started the trip. */
  channelOpen: boolean;
  serviceDate: string;
};

const DELAY_WORTH_SAYING = 10;

function delayOf(stop: RunSheetStopView, serviceDate: string): number {
  if (!stop.expectedArrival) return 0;
  const planned = new Date(`${serviceDate}T${stop.plannedArrival.slice(0, 5)}:00+05:30`).getTime();
  return Math.max(0, Math.round((new Date(stop.expectedArrival).getTime() - planned) / 60_000));
}

const pending = (s: RunSheetStopView) => s.outcome === "PENDING" || s.outcome === "ARRIVED";

/** The replies that fit the trip now, most useful first, at most four. */
export function replies(state: TripState): Reply[] {
  const out: Reply[] = [];
  const next = [...state.stops].filter(pending).sort((a, b) => a.sequence - b.sequence)[0] ?? null;
  const yes = (kind: DecisionView["kind"]) =>
    state.decisions.filter((d) => d.kind === kind && d.answer === "yes").sort((a, b) => (b.answeredAt ?? "").localeCompare(a.answeredAt ?? ""))[0];

  const receive = yes("receive_late");
  if (receive?.receiveAt) {
    out.push({ id: "driver.store_will_receive", version: TEMPLATE_VERSION, label: `Tell the driver ${receive.outletId} will receive`, to: "driver", outletId: null,
      body: `${receive.outletId} will receive at about ${clock(receive.receiveAt)}. Keep the stop order.` });
  }
  const dock = yes("dock_help");
  if (dock?.aboutOutletId) {
    out.push({ id: "driver.dock_help", version: TEMPLATE_VERSION, label: `Tell the driver ${dock.outletId} will help`, to: "driver", outletId: null,
      body: `${dock.outletId} agreed: unload the goods for ${dock.aboutOutletId} at their dock to reach theirs, then reload them.` });
  }
  const failed = state.stops.find((s) => s.outcome === "FAILED");
  if (failed) {
    out.push({ id: "driver.bring_back", version: TEMPLATE_VERSION, label: `Bring ${failed.outletId}'s goods back`, to: "driver", outletId: null,
      body: `Keep the goods for ${failed.outletId} on the vehicle and bring them back to the depot.` });
    out.push({ id: "store.redelivery", version: TEMPLATE_VERSION, label: `Tell ${failed.outletId} about the next run`, to: "outlet", outletId: failed.outletId,
      body: "Your order could not be delivered today. It goes first on the next run." });
  }
  if (next) {
    const late = delayOf(next, state.serviceDate);
    const at = clock(next.expectedArrival ?? `${state.serviceDate}T${next.plannedArrival.slice(0, 5)}:00+05:30`);
    out.push(late >= DELAY_WORTH_SAYING
      ? { id: "store.running_late", version: TEMPLATE_VERSION, label: `Tell ${next.outletId} the new time`, to: "outlet", outletId: next.outletId,
          body: `Running ${late} min late. Now expected at ${at}.` }
      : { id: "store.on_the_way", version: TEMPLATE_VERSION, label: `Tell ${next.outletId} it is on the way`, to: "outlet", outletId: next.outletId,
          body: `On the way. Expected at ${at}.` });
    if (late >= DELAY_WORTH_SAYING && state.channelOpen) {
      out.push({ id: "channel.running_late", version: TEMPLATE_VERSION, label: "Tell every store waiting", to: "channel", outletId: null,
        body: `The vehicle is running about ${late} min late. Each store will see its new time on its own screen.` });
    }
  }
  out.push({ id: "driver.noted", version: TEMPLATE_VERSION, label: "Noted, carry on", to: "driver", outletId: null, body: "Noted. Carry on with the next stop." });
  return out.slice(0, 4);
}
