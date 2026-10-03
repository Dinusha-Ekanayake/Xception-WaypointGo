import type { NotificationView } from "@shared/domain/types";
import type { Translate } from "./strings.ts";

// The loader's notifications in the loader's language (issue #118). The server
// writes each message in English from a template and keeps the facts it used;
// here the same templates are filled in Sinhala or Tamil from those facts.
// These must match the loader's rows of notification.routing_rules (routing
// version 2). Free text such as a revision's reason stays as written. With no
// facts (older notifications) or a fact missing, the English text is shown.

const TEMPLATES: Record<string, { title: string; body: string }> = {
  "plan.published": {
    title: "Plan published for {serviceDate}",
    body: "Version {planVersion} with {tripCount} trips is ready to load.",
  },
  "plan.revised": {
    title: "Plan revised for {serviceDate}",
    body: "Version {planVersion} with {tripCount} trips replaces the earlier plan: {reason}",
  },
  "trip.released": {
    title: "Trip released · {vehicleId}",
    body: "{vehicleId} left for {serviceDate} with {stopCount} stops.",
  },
};

const names = (template: string) => [...template.matchAll(/\{(\w+)\}/g)].map((m) => m[1]!);

export function localized(n: Pick<NotificationView, "eventType" | "title" | "body" | "facts">, tr: Translate): { title: string; body: string } {
  const template = TEMPLATES[n.eventType];
  const facts = n.facts;
  if (!template || !facts) return { title: n.title, body: n.body };
  const complete = [...names(template.title), ...names(template.body)].every((name) => facts[name] !== undefined);
  if (!complete) return { title: n.title, body: n.body };
  return { title: tr(template.title, facts), body: tr(template.body, facts) };
}

/** Every template, for the dictionary test. */
export const MESSAGE_TEMPLATES = Object.values(TEMPLATES).flatMap((t) => [t.title, t.body]);
