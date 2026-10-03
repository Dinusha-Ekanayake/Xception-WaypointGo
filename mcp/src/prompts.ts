import { z } from 'zod';

/**
 * Ready-made prompts for the common jobs (issue #177). A prompt is only a starting
 * message telling the assistant which tools to call; it grants nothing, and every tool
 * it names is still authorized per call. A prompt is offered only when the caller holds
 * every read action its tools need.
 */
const code = z.string().regex(/^[A-Za-z0-9_-]{1,80}$/);
const date = z.iso.date();

export type PromptDefinition = {
  name: string; title: string; description: string; actions: string[];
  args: { name: string; description: string; schema: z.ZodType }[];
  text: (args: Record<string, string>) => string;
};

export const prompts: PromptDefinition[] = [
  { name: 'morning_briefing', title: 'Morning briefing', actions: ['plan:Read'],
    description: 'A dispatcher\'s start-of-day picture for one depot: plan, loading and open issues.',
    args: [{ name: 'depot', description: 'Depot code, for example PEL', schema: code },
      { name: 'date', description: 'Service day, YYYY-MM-DD', schema: date }],
    text: a => `Give me the morning briefing for depot ${a.depot} on ${a.date}. Call day_summary first. `
      + 'If orders were deferred, call list_plan_allocations with the plan ID and quote each recorded rule and reason for the deferred ones. '
      + 'List anything named in unavailable as not readable, never as zero. Keep it short.' },
  { name: 'what_to_load_next', title: 'What to load next', actions: ['loading:Read'],
    description: 'A loader\'s next trip and its manifest in loading order.',
    args: [{ name: 'depot', description: 'Depot code, for example PEL', schema: code },
      { name: 'date', description: 'Service day, YYYY-MM-DD', schema: date }],
    text: a => `What should I load next at depot ${a.depot} on ${a.date}? Call list_ready_trips, `
      + 'take the earliest planned departure that is not loaded, then call get_manifest for it and list the lines in loading order with units.' },
  { name: 'pending_receipts', title: 'Pending receipts', actions: ['receipt:Read'],
    description: 'A store manager\'s deliveries still waiting for a receipt answer.',
    args: [{ name: 'outlet', description: 'Outlet code, for example OUT001', schema: code }],
    text: a => `Which deliveries at outlet ${a.outlet} still need my receipt? Call list_pending_receipts, `
      + 'then get_receipt for each, oldest delivery first, and say when each one closes on its own.' },
];

export function promptArgs(prompt: PromptDefinition, raw: Record<string, string> | undefined): Record<string, string> | null {
  const values: Record<string, string> = {};
  for (const arg of prompt.args) {
    const value = raw?.[arg.name];
    if (!arg.schema.safeParse(value).success) return null;
    values[arg.name] = value as string;
  }
  return values;
}
