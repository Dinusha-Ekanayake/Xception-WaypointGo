import type { DecisionView } from "../domain/issues.ts";
import type { DeliveryRecordView, RunSheetStopView } from "../domain/execution.ts";
import type { MessageAudience, MessageView } from "../domain/messaging.ts";
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
  /** AI-J5: this list had its second and third swapped on purpose, to measure the pull of place. */
  explored?: boolean;
};

/** AI-J5: each reply's learned pull, from GET /api/ml/reply-ranking; empty while the order is the rules'. */
export type LearnedOrder = { trained: boolean; scores: Record<string, number> };

/** How often the second and third replies swap places, so the pull of place can be told apart from the reply's. */
export const EXPLORE_RATE = 0.1;

/**
 * The rule order, with the replies the ranker knows reordered by their learned
 * pull among the places they already hold; a reply it has never seen keeps its
 * place. Then, one time in ten, the second and third swap. Which replies are
 * offered is never the ranker's to decide, only their order.
 */
export function rankReplies(list: Reply[], order: LearnedOrder | null, roll: number): Reply[] {
  let out = [...list];
  if (order?.trained) {
    const known = out.map((r, i) => (r.id in order.scores ? i : -1)).filter((i) => i >= 0);
    const sorted = known.map((i) => out[i]!).sort((a, b) => order.scores[b.id]! - order.scores[a.id]!);
    known.forEach((slot, k) => {
      out[slot] = sorted[k]!;
    });
  }
  if (out.length >= 3 && roll < EXPLORE_RATE) {
    out = [out[0]!, out[2]!, out[1]!, ...out.slice(3)].map((r) => ({ ...r, explored: true }));
  }
  return out;
}

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

/**
 * Ready replies for the store manager (decision suggestions, part 4): what a
 * store usually answers in its delivery's situation, to the dispatcher or to its
 * own driver (R-MSG-16). Chosen by code from the store's delivery; the store may
 * edit before sending, and nothing is sent by itself. Never a time or a promise
 * the store did not choose: the words stay the store's.
 */
export function storeReplies(
  delivery: DeliveryRecordView | null,
  tr: (english: string) => string = (s) => s,
  latest: Pick<MessageView, "mine" | "authorRole" | "body"> | null = null,
): Reply[] {
  if (!delivery) return [];
  const open = delivery.outcome === "PENDING" || delivery.outcome === "ARRIVED";
  const out: Reply[] = [];
  const reply = (id: string, label: string, to: MessageAudience, body: string) =>
    out.push({ id: `storereply.${id}`, version: TEMPLATE_VERSION, label: tr(label), to, outletId: null, body: tr(body) });
  // The newest word from someone else decides the answer first: a delay notice asks whether the store can still receive.
  const heard = latest && !latest.mine ? latest : null;
  if (open && heard && isDelayNotice(heard.body)) {
    reply("later_today", "We can receive later today", "dispatch", "We can still receive later today. Please come when you can.");
    reply("next_day", "Deliver on the next day", "dispatch", "We cannot receive today. Please deliver on the next delivery day.");
  } else if (open && delivery.reportedProblem === "STORE_CLOSED") {
    reply("open_now", "We are open now", "dispatch", "We are open now and can receive. Please send the driver back.");
    reply("next_day", "Deliver on the next day", "dispatch", "We cannot receive today. Please deliver on the next delivery day.");
  } else if (open && delivery.reportedProblem === "CANNOT_UNLOAD") {
    reply("space_clear", "Space is clear now", "dispatch", "The unloading area is clear now. The driver can unload.");
    reply("next_day", "Deliver on the next day", "dispatch", "We cannot receive today. Please deliver on the next delivery day.");
  } else if (delivery.outcome === "ARRIVED") {
    reply("coming", "Coming to the door", "driver", "Coming to the door now.");
  } else if (delivery.outcome === "PENDING" && delivery.startedAt) {
    reply("ready", "We are ready", "driver", "We are ready to receive.");
  } else if (delivery.outcome === "SKIPPED") {
    reply("understood", "Understood", "dispatch", "Understood, thank you.");
  } else if (delivery.outcome === "FAILED") {
    reply("next_run", "Send it on the next run", "dispatch", "Please send our order on the next run.");
  }
  // Anything else the dispatcher wrote gets at least a plain acknowledgement.
  if (out.length === 0 && heard?.authorRole === "dispatcher") {
    reply("understood", "Understood", "dispatch", "Understood, thank you.");
  }
  return out;
}

/** The trip room's notice that the vehicle is held up (R-MSG-17), worded once on the server in `MessagePolicy.delayNotice`. */
export function isDelayNotice(body: string): boolean {
  return / is delayed: /.test(body);
}

/**
 * The dispatcher's word to every store still waiting on the trip (decision
 * suggestions, part 4): one message per store, each with its own expected time,
 * never one line for all. The stops not reached yet, in trip order; a store
 * whose time is not known gets none.
 */
export function eachStoreMessages(state: Pick<TripState, "stops" | "serviceDate">): Array<{ outletId: string; body: string }> {
  return [...state.stops]
    .filter((s) => s.outcome === "PENDING")
    .sort((a, b) => a.sequence - b.sequence)
    .map((s) => {
      const late = delayOf(s, state.serviceDate);
      const at = clock(s.expectedArrival ?? `${state.serviceDate}T${s.plannedArrival.slice(0, 5)}:00+05:30`);
      return {
        outletId: s.outletId,
        body: late >= DELAY_WORTH_SAYING ? `Running ${late} min late. Now expected at ${at}.` : `On the way. Expected at ${at}.`,
      };
    });
}
