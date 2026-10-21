import type { PlaybookView, SuggestionsView } from "../src/shared/domain/intelligence.ts";

// The server's playbooks (intelligence Playbooks, version 1) as the mocked API
// serves them, word for word, for the browser suites.

export const PLAYBOOKS: PlaybookView[] = [
  {
    situation: "WINDOW",
    steps: [
      "Tell the store the new arrival time, so it can keep someone at the dock.",
      "Open the trip and check the stops after this one: a late stop can make the next one late too.",
      "If the store cannot receive after its window, record the delivery as not made and book a make-up.",
    ],
    message: { to: "outlet", template: "{vehicle} is running late to {store} and is now expected at {expected}. Your window closes at {window close}. Can you still receive it?" },
  },
  {
    situation: "OFFLINE",
    steps: [
      "Check the vehicle's last position on the map and when it was seen.",
      "Write to the driver on the trip's messages: it is delivered when the phone has a signal again.",
      "Tell the next store its arrival time is not confirmed.",
    ],
    message: { to: "outlet", template: "We have not heard from {vehicle} for a while, so its arrival at {store} is not confirmed. We will tell you as soon as we know." },
  },
  {
    situation: "FAILED",
    steps: [
      "Open the trip and read why the driver could not deliver.",
      "Tell the store what happened and that a new day will be confirmed.",
      "Book a make-up delivery from the issue, so the order is offered first on the next plan.",
    ],
    message: { to: "outlet", template: "The delivery to {store} on {vehicle} could not be completed today. We are arranging it for the next run and will confirm the day." },
  },
  {
    situation: "LEFT",
    steps: [
      "Open the trip and check whether the store has answered since the driver left.",
      "Ask the store to check what arrived and confirm the receipt; if the driver disagreed, open the issue.",
    ],
    message: { to: "outlet", template: "{vehicle} left {store} before your report was in. Please check what arrived and confirm the receipt." },
  },
  { situation: "ISSUE", steps: ["Open the issue and read what was reported.", "Book the make-up delivery, or resolve the issue with a reason."], message: null },
  {
    situation: "STORE_CLOSED",
    steps: ["Call the store: someone may be on the way.", "If nobody comes, skip the store to the next day, or cancel if the order is no longer wanted."],
    message: { to: "outlet", template: "{vehicle} is at {store} and nobody is there to receive. Can someone come to the door?" },
  },
];

/** What /api/ml/decision-suggestions answers for a store closed with the van at the door. */
export function storeClosed(deliveryId: string): SuggestionsView {
  return {
    situation: "STORE_CLOSED", deliveryId, basis: "rules", version: 1,
    suggestions: [
      { action: "CALL_STORE", chance: 0.41, warning: null },
      { action: "SKIP_NEXT_DAY", chance: 0.35, warning: "Deferred before: this store would be skipped twice in a row" },
      { action: "WAIT", chance: 0.24, warning: null },
    ],
    playbook: PLAYBOOKS.find((p) => p.situation === "STORE_CLOSED")!,
  };
}
