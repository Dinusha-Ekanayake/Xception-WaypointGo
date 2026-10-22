import type { DecisionView } from "../domain/issues.ts";
import type { DeliveryRecordView, RunSheetStopView } from "../domain/execution.ts";
import type { MessageAudience, MessageView } from "../domain/messaging.ts";
import { clock } from "../wording/time.ts";
import { wordsAct } from "./words.ts";

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
  // A store's Yes counts only while it is that store's newest answer: a No recorded after it replaces it.
  const newest = (d: DecisionView) => !state.decisions.some(
    (o) => o.outletId === d.outletId && o.kind === d.kind && o.answer !== null && (o.answeredAt ?? "") > (d.answeredAt ?? ""));
  const yes = (kind: DecisionView["kind"]) =>
    state.decisions.filter((d) => d.kind === kind && d.answer === "yes" && newest(d))
      .sort((a, b) => (b.answeredAt ?? "").localeCompare(a.answeredAt ?? ""))[0];

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
 * The dispatcher's answer to the newest word from a store (decision suggestions
 * part 3): thanks back to that store, or its words passed to the driver. Nothing
 * when the newest word is the dispatcher's own, or the store is not known.
 */
export function dispatcherAnswers(
  latest: Pick<MessageView, "mine" | "authorRole" | "body" | "aboutOutletId" | "understood"> | null,
  stateReplies: Reply[] = [],
): Reply[] {
  if (!latest || latest.mine) return [];
  // Jev's sure reading first, else the rules' own reading of a few plain words.
  const act = latest.understood ?? wordsAct(latest.body);
  // The off-topic guard: unrelated or abusive words, and thanks, ask for no reply.
  if (act === "OFF_TOPIC" || act === "THANKS") return [];
  const answer = (id: string, label: string, to: MessageAudience, outletId: string | null, body: string): Reply =>
    ({ id: `answer.${id}`, version: TEMPLATE_VERSION, label, to, outletId, body });
  if (latest.authorRole === "driver") {
    if (act === "BREAKDOWN" || act === "ROAD_BLOCKED") return [answer("how_long", "Ask how long it takes", "driver", null, "Noted. How long until you can go on?")];
    if (act === "STORE_CLOSED" || act === "CANNOT_UNLOAD") return [answer("wait_there", "Wait there, asking the store", "driver", null, "Wait there a few minutes, I am asking the store.")];
    return [];
  }
  if (latest.authorRole !== "store_manager" || !latest.aboutOutletId) return [];
  const store = latest.aboutOutletId;
  const said = latest.body.trim();
  const thanks = answer("thanks", `Thanks, noted (${store})`, "outlet", store, "Thank you, noted.");
  const tell = said ? [answer("tell_driver", `Tell the driver what ${store} said`, "driver", null, `${store}: ${said}`)] : [];
  switch (act) {
    case "SAYS_NO":
    case "DELIVER_NEXT_DAY":
      return [
        answer("next_day", `Next delivery day (${store})`, "outlet", store, "Understood. Your delivery goes on the next delivery day, first on the trip."),
        answer("driver_skip", `Tell the driver to skip ${store}`, "driver", null, `${store} cannot receive today. Skip it; it goes on the next delivery day.`),
      ];
    case "STORE_OPEN_NOW":
    case "DOCK_CLEAR":
      return [...tell, thanks];
    case "ASKS_WHEN": {
      // The state's own reply already carries that store's expected time.
      const time = stateReplies.find((r) => r.to === "outlet" && r.outletId === store);
      return time ? [time] : [answer("soon", `We will tell you the time (${store})`, "outlet", store, "We will tell you the time as soon as we know.")];
    }
    case "ASKS_CAN_WAIT":
      return [answer("can_wait", `Yes, please wait (${store})`, "outlet", store, "Yes, please wait. The driver is on the way.")];
    default:
      // A yes, a later time, a problem, or words not read: thanks, and the driver told.
      return [thanks, ...tell];
  }
}

/**
 * The message the chips answer: the newest one not read as off topic. Spam,
 * abuse or an unrelated line changes nothing, so the chips keep answering the
 * last real word in the conversation (R-ML-43).
 */
export function newestToAnswer<M extends Pick<MessageView, "understood">>(messages: M[]): M | null {
  for (let i = messages.length - 1; i >= 0; i--) if (messages[i]!.understood !== "OFF_TOPIC") return messages[i]!;
  return null;
}

/**
 * The dispatcher's chips: the answer to the newest word first, then the trip's
 * own replies, at most four and none twice. Once a store says it cannot receive,
 * a trip reply about that store (an earlier "it will receive") is out of date
 * and steps aside, so the chips never say skip it and keep it at once.
 */
export function dispatcherChips(answers: Reply[], stateReplies: Reply[], aboutOutletId: string | null, alsoRefused: string[] = []): Reply[] {
  const refused = new Set(alsoRefused);
  if (aboutOutletId && answers.some((a) => a.id === "answer.driver_skip")) refused.add(aboutOutletId);
  const about = (r: Reply) => [...refused].some((o) => r.outletId === o || r.label.includes(o));
  const rest = stateReplies.filter((r) => !answers.some((a) => a.id === r.id) && !about(r));
  return [...answers, ...rest].slice(0, 4);
}

/**
 * The stores whose words since their last recorded Yes say No: their newest
 * message that reads as a yes or a no (Jev's sure reading, else the plain
 * words) came after the Yes and refuses. A Yes tapped after those words is
 * newer and stands. Their "will receive" chips are out of date (R-ML-45).
 */
export function refusedSinceYes(
  messages: Pick<MessageView, "mine" | "authorRole" | "aboutOutletId" | "understood" | "body" | "createdAt">[],
  decisions: Pick<DecisionView, "outletId" | "answer" | "answeredAt">[],
): string[] {
  const out: string[] = [];
  for (const d of decisions) {
    if (d.answer !== "yes" || !d.answeredAt || out.includes(d.outletId)) continue;
    const said = messages
      .filter((m) => !m.mine && m.authorRole === "store_manager" && m.aboutOutletId === d.outletId && m.createdAt > d.answeredAt!)
      .map((m) => m.understood ?? wordsAct(m.body))
      .filter((act) => act === "SAYS_YES" || act === "CAN_RECEIVE_LATER" || act === "SAYS_NO" || act === "DELIVER_NEXT_DAY");
    const last = said[said.length - 1];
    if (last === "SAYS_NO" || last === "DELIVER_NEXT_DAY") out.push(d.outletId);
  }
  return out;
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
  latest: Pick<MessageView, "mine" | "authorRole" | "body" | "audience" | "understood"> | null = null,
): Reply[] {
  if (!delivery) return [];
  const open = delivery.outcome === "PENDING" || delivery.outcome === "ARRIVED";
  const out: Reply[] = [];
  const reply = (id: string, label: string, to: MessageAudience, body: string) =>
    out.push({ id: `storereply.${id}`, version: TEMPLATE_VERSION, label: tr(label), to, outletId: null, body: tr(body) });
  const heard = latest && !latest.mine ? latest : null;
  // An answer goes back the way the question came: to the dispatcher, or to the dispatcher and the
  // stores still waiting when that is where it was asked. Never to someone the store did not answer.
  const back: MessageAudience = heard?.audience === "channel" ? "channel" : "dispatch";
  // At the door the driver's report decides: the store answers what stops the delivery.
  if (open && delivery.reportedProblem === "STORE_CLOSED") {
    reply("open_now", "We are open now", "dispatch", "We are open now and can receive. Please send the driver back.");
    reply("next_day", "Deliver on the next day", "dispatch", "We cannot receive today. Please deliver on the next delivery day.");
    return out;
  }
  if (open && delivery.reportedProblem === "CANNOT_UNLOAD") {
    reply("space_clear", "Space is clear now", "dispatch", "The unloading area is clear now. The driver can unload.");
    reply("next_day", "Deliver on the next day", "dispatch", "We cannot receive today. Please deliver on the next delivery day.");
    return out;
  }
  const act = heard ? (heard.understood ?? wordsAct(heard.body)) : null;
  // The off-topic guard: unrelated or abusive words, and thanks, ask for no reply.
  if (heard && (act === "OFF_TOPIC" || act === "THANKS")) return out;
  if (heard?.authorRole === "dispatcher" && open && act) {
    // What the dispatcher's words do decides (R-ML-43); the rules below are the fallback.
    if (act === "ASKS_CAN_WAIT" || act === "ASKS_WHEN") {
      reply("yes_wait", "Yes, we can wait", back, "Yes, we can wait.");
      reply("no", "No, deliver tomorrow", back, "No, we can't. Please deliver on the next delivery day.");
      return out;
    }
    if (act === "BREAKDOWN" || act === "ROAD_BLOCKED" || act === "RUNNING_LATE") {
      reply("later_today", "We can receive later today", back, "We can still receive later today. Please come when you can.");
      reply("next_day", "Deliver on the next day", back, "We cannot receive today. Please deliver on the next delivery day.");
      return out;
    }
    if (act === "SAYS_YES" || act === "SAYS_NO" || act === "DELIVER_NEXT_DAY" || act === "CAN_RECEIVE_LATER") {
      reply("understood", "Understood", back, "Understood, thank you.");
      return out;
    }
  }
  if (heard?.authorRole === "dispatcher" && open) {
    // Then the newest word from the dispatcher is what the store answers.
    if (isDelayNotice(heard.body)) {
      reply("later_today", "We can receive later today", back, "We can still receive later today. Please come when you can.");
      reply("next_day", "Deliver on the next day", back, "We cannot receive today. Please deliver on the next delivery day.");
    } else {
      reply("yes", "Yes, that's fine", back, "Yes, that's fine for us.");
      reply("no", "No, we can't", back, "No, we can't. Please deliver on the next delivery day.");
    }
    return out;
  }
  if (heard?.authorRole === "dispatcher") {
    reply("understood", "Understood", back, "Understood, thank you.");
    return out;
  }
  if (delivery.outcome === "ARRIVED") {
    reply("coming", "Coming to the door", "driver", "Coming to the door now.");
  } else if (delivery.outcome === "PENDING" && delivery.startedAt) {
    reply("ready", "We are ready", "driver", "We are ready to receive.");
  } else if (delivery.outcome === "SKIPPED") {
    reply("understood", "Understood", "dispatch", "Understood, thank you.");
  } else if (delivery.outcome === "FAILED") {
    reply("next_run", "Send it on the next run", "dispatch", "Please send our order on the next run.");
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
