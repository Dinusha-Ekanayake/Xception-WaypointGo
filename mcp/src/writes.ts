import { z } from 'zod';

/**
 * Confirmed safe writes (issue #177, R-IAM-35). A write tool never changes
 * anything: it asks Waypoint to prepare the exact command and returns a preview
 * with a confirmation. Only `confirm_write`, called after the person agrees,
 * submits it, and Waypoint runs it through the command bus under the person's
 * own policy and scope. The only writes are the ones that add or reassign;
 * nothing here cancels, publishes or replaces anyone's work.
 */
const code = z.string().regex(/^[A-Za-z0-9_-]{1,80}$/);
const id = z.uuid();

export type WriteToolDefinition = {
  name: string; scope: string; description: string; input: z.ZodType;
  /** The body of POST /api/mcp/writes for validated arguments. */
  request: (args: Record<string, unknown>) => { tool: string; expectedVersion: number | null; payload: Record<string, unknown> };
};

const confirmFirst = ' Nothing changes yet: this returns a preview and a confirmation. Show the preview to the person and call confirm_write only after they agree. The confirmation lasts two minutes and works once.';

export const writeCatalogue: WriteToolDefinition[] = [
  { name: 'raise_issue', scope: 'issues.write',
    description: 'Prepare a new operational issue (a problem report) for a depot, optionally at one outlet, about one or more records. Which types you may raise is your own policy.' + confirmFirst + ' Example: "Report damaged goods on order X at my store."',
    input: z.strictObject({
      type: z.enum(['LOADING_SHORTFALL', 'DAMAGED_GOODS', 'FAILED_DELIVERY', 'LATE_DELIVERY', 'VEHICLE_FAULT', 'ROAD_DISRUPTION', 'RECEIPT_DISPUTE', 'STOCK_DISCREPANCY', 'OTHER']),
      severity: z.enum(['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']),
      depotCode: code, outletId: code.optional(),
      subjects: z.array(z.strictObject({ type: z.enum(['order', 'trip', 'delivery', 'receipt', 'shortfall', 'vehicle']), id: z.string().min(1).max(80) })).min(1).max(10),
      description: z.string().trim().min(1).max(2000),
    }),
    request: args => ({ tool: 'raise_issue', expectedVersion: null, payload: args }) },
  { name: 'assign_issue', scope: 'issues.write',
    description: 'Prepare assigning an open issue to a person who works its depot. Use the issue ID and rowVersion from get_issue as expectedVersion.' + confirmFirst + ' Example: "Assign this issue to the depot supervisor."',
    input: z.strictObject({ issueId: id, assigneeUserId: id, expectedVersion: z.number().int().min(1) }),
    request: args => ({ tool: 'assign_issue', expectedVersion: args.expectedVersion as number,
      payload: { issueId: args.issueId, assigneeUserId: args.assigneeUserId } }) },
];

export const confirmInput = z.strictObject({ confirmation: z.string().regex(/^mcpw\.[A-Za-z0-9_-]{16,200}$/) });

export const preparedOutput = z.object({
  confirmation: z.string().max(256), expiresAt: z.iso.datetime({ offset: true }), tool: z.string().max(80),
  kind: z.string().max(80), commandId: id, expectedVersion: z.number().int().nullish(),
  payload: z.record(z.string(), z.unknown()),
});

/** Only these result fields reach the assistant; anything else the command returns is dropped. */
export const confirmedOutput = z.object({
  commandId: id, tool: z.string().max(80), kind: z.string().max(80), replayed: z.boolean(),
  result: z.object({ issueId: id.optional(), status: z.string().max(80).optional(), rowVersion: z.number().int().optional() }),
});
