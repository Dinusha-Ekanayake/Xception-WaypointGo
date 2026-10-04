// What to do about each thing Live puts under "Needs you", and a message ready
// to send (issue #269, first slice). The playbook is data: for a scenario, the
// steps in order and a message template whose blanks are filled from the trip
// and the store at that moment. Matching is by scenario, so the same situation
// always gets the same advice; no model decides or writes anything, and the
// dispatcher edits and sends the message themselves.
//
// The playbooks live here until an administrator can edit them (the rest of
// #269, which needs the backend).

export type Scenario = "window" | "offline" | "failed" | "issue" | "proof";

export type Playbook = {
  steps: string[];
  /** Who the ready message is for, and its wording with {blanks}; null when there is nobody useful to write to. */
  message: { to: "outlet" | "driver"; template: string } | null;
};

export const PLAYBOOKS: Record<Scenario, Playbook> = {
  window: {
    steps: [
      "Tell the store the new arrival time, so it can keep someone at the dock.",
      "Open the trip and check the stops after this one: a late stop can make the next one late too.",
      "If the store cannot receive after its window, record the delivery as not made and book a make-up.",
    ],
    message: { to: "outlet", template: "{vehicle} is running late to {store} and is now expected at {expected}. Your window closes at {window close}. Can you still receive it?" },
  },
  offline: {
    steps: [
      "Check the vehicle's last position on the map and when it was seen.",
      "Write to the driver on the trip's messages: it is delivered when the phone has a signal again.",
      "Tell the next store its arrival time is not confirmed.",
    ],
    message: { to: "outlet", template: "We have not heard from {vehicle} for a while, so its arrival at {store} is not confirmed. We will tell you as soon as we know." },
  },
  failed: {
    steps: [
      "Open the trip and read why the driver could not deliver.",
      "Tell the store what happened and that a new day will be confirmed.",
      "Book a make-up delivery from the issue, so the order is offered first on the next plan.",
    ],
    message: { to: "outlet", template: "The delivery to {store} on {vehicle} could not be completed today. We are arranging it for the next run and will confirm the day." },
  },
  proof: {
    steps: [
      "Ask the driver for the photo and signature before the vehicle leaves the stop.",
      "If the driver has left, open the trip and check whether the store confirmed the receipt.",
    ],
    message: { to: "driver", template: "{vehicle}: the delivery at {store} has no proof yet. Please add the photo and the signature before you leave the stop." },
  },
  issue: {
    steps: ["Open the issue and read what was reported.", "Book the make-up delivery, or resolve the issue with a reason."],
    message: null,
  },
};

export type Facts = Partial<Record<"vehicle" | "store" | "expected" | "window close", string | null>>;

/** The template with every blank filled; null when a blank has no value, so a half-written message is never offered. */
export function fill(template: string, facts: Facts): string | null {
  let missing = false;
  const text = template.replace(/\{([^}]+)\}/g, (_, name: string) => {
    const value = facts[name as keyof Facts];
    if (!value) missing = true;
    return value ?? "";
  });
  return missing ? null : text;
}

export type Suggestion = { steps: string[]; message: { to: "outlet" | "driver"; body: string } | null };

export function suggestionFor(scenario: Scenario, facts: Facts): Suggestion {
  const playbook = PLAYBOOKS[scenario];
  const body = playbook.message ? fill(playbook.message.template, facts) : null;
  return { steps: playbook.steps, message: playbook.message && body ? { to: playbook.message.to, body } : null };
}
