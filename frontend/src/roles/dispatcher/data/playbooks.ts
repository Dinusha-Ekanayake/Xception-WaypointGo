import type { PlaybookView } from "../../../shared/domain/intelligence.ts";

// What to do about each thing Live puts under "Needs you", and a message ready
// to send (issue #269). The playbooks are the server's (intelligence
// `Playbooks`), one definition for "Needs you" and the trip's chat: for a
// situation, the steps in order and a message template whose blanks are filled
// from the trip and the store at that moment. Matching is by situation, so the
// same situation always gets the same advice; no model decides or writes
// anything, and the dispatcher edits and sends the message themselves.
// First written here by @Dinusha-Ekanayake; the wording moved to the server unchanged.

export type Scenario = "window" | "offline" | "failed" | "issue" | "left";

/** The playbook of a "Needs you" scenario in the catalogue; undefined while it is read or when it cannot be. */
export function playbookOf(catalogue: PlaybookView[] | null, scenario: Scenario): PlaybookView | undefined {
  return catalogue?.find((p) => p.situation === scenario.toUpperCase());
}

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

export function suggestionFor(playbook: PlaybookView, facts: Facts): Suggestion {
  const body = playbook.message ? fill(playbook.message.template, facts) : null;
  const to = playbook.message?.to === "driver" ? "driver" : "outlet";
  return { steps: playbook.steps, message: playbook.message && body ? { to, body } : null };
}
