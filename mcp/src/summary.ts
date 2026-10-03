import { z } from 'zod';
import { BackendError } from './client.ts';
import * as out from './outputs.ts';

/**
 * day_summary (issue #177): one depot and day in counts, composed from three reads the
 * caller could already make one by one (plan, loading trips, open issues). Each part is
 * authorized on its own by the backend, so a part the caller may not read, or that does
 * not exist, is named in `unavailable` with its code rather than shown as zero. Counting
 * is the only work done here; no rule is decided in the adapter.
 */
export type Get = (path: string) => Promise<unknown>;

/** Open issues are counted over at most this many pages of 50, then marked incomplete. */
export const ISSUE_PAGES = 2;

const count = (values: string[]) => {
  const counts: Record<string, number> = {};
  for (const value of values) counts[value] = (counts[value] ?? 0) + 1;
  return counts;
};
const counts = z.record(z.string().max(80), z.number().int().nonnegative());
const part = z.enum(['plan', 'loading', 'issues']);

export const daySummaryOutput = z.object({
  depotCode: z.string().max(80), serviceDate: z.iso.date(),
  plan: z.object({ state: z.enum(['published', 'draft']), planId: z.uuid(), planVersion: z.number().int().nonnegative(),
    trips: z.number().int().nonnegative(), orders: counts }).nullable(),
  loading: z.object({ trips: z.number().int().nonnegative(), byStatus: counts }).nullable(),
  issues: z.object({ open: z.number().int().nonnegative(), bySeverity: counts, complete: z.boolean() }).nullable(),
  unavailable: z.array(z.object({ part, code: z.string().max(80), status: z.number().int() })).max(3),
});
export type DaySummary = z.infer<typeof daySummaryOutput>;

const query = (path: string, values: Record<string, string | number | undefined>) => {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(values)) if (value !== undefined) params.set(key, String(value));
  return `${path}?${params}`;
};

async function attempt<T>(name: z.infer<typeof part>, unavailable: DaySummary['unavailable'], read: () => Promise<T>): Promise<T | null> {
  try {
    return await read();
  } catch (error) {
    // A refusal from the backend is reported, never swallowed: the caller sees denial, absence and outage apart.
    if (!(error instanceof BackendError)) throw error;
    if (error.status === 401 || error.status === 429) throw error;
    unavailable.push({ part: name, code: error.code, status: error.status });
    return null;
  }
}

const parsed = <T extends z.ZodType>(schema: T, raw: unknown): z.infer<T> => {
  const safe = schema.safeParse(raw);
  if (!safe.success) throw new BackendError('INVALID_BACKEND_RESPONSE', 502);
  return safe.data;
};

export async function daySummary(get: Get, args: { depot: string; date: string }): Promise<DaySummary> {
  const unavailable: DaySummary['unavailable'] = [];
  const plan = await attempt('plan', unavailable, async () => {
    let state: 'published' | 'draft' = 'published';
    let raw: unknown;
    try {
      raw = await get(query('/api/plans/published', { depot: args.depot, date: args.date }));
    } catch (error) {
      // Nothing published yet: the open draft is the next best fact, and is labelled draft.
      if (!(error instanceof BackendError) || error.status !== 404) throw error;
      state = 'draft';
      raw = await get(query('/api/plans/draft', { depot: args.depot, date: args.date }));
    }
    const view = parsed(out.planOutput, raw);
    return { state, planId: view.planId, planVersion: view.planVersion, trips: view.trips.length,
      orders: count(view.allocations.map(a => a.decision)) };
  });
  const loading = await attempt('loading', unavailable, async () => {
    const trips = parsed(z.array(out.readyTripOutput).max(1000), await get(query('/api/loading/trips', { depot: args.depot, date: args.date })));
    return { trips: trips.length, byStatus: count(trips.map(t => t.status)) };
  });
  const issues = await attempt('issues', unavailable, async () => {
    const severities: string[] = [];
    let cursor: string | undefined;
    for (let page = 0; page < ISSUE_PAGES; page++) {
      const view = parsed(out.pageOutput(out.issueOutput), await get(query('/api/issues', { depot: args.depot, after: cursor, limit: 50 })));
      severities.push(...view.items.map(i => i.severity));
      cursor = view.nextCursor ?? undefined;
      if (!cursor) break;
    }
    return { open: severities.length, bySeverity: count(severities), complete: cursor === undefined };
  });
  return { depotCode: args.depot, serviceDate: args.date, plan, loading, issues, unavailable };
}
