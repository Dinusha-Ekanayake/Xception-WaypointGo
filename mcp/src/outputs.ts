import { z } from 'zod';

// Explicit fields only. Unknown backend fields are discarded before model context.
const text = z.string().max(4000);
const id = z.uuid();
const optionalId = id.nullish();
const number = z.number().finite();
const version = z.number().int().nonnegative();
const instant = z.iso.datetime({ offset: true });
const date = z.iso.date();
const time = z.string().regex(/^\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?$/);
const array = <T extends z.ZodType>(schema: T) => z.array(schema).max(1000);
export const contextOutput = z.object({ userId: id, roles: array(text), scope: array(text), readActions: array(text),
  // Issue #177. Defaults keep an adapter working against a backend that predates them: no writes, no personal fields.
  grantedScopes: array(text).default(['waypoint.read']), writeTools: array(text).default([]), personalFields: z.boolean().default(false) });
export const orderOutput = z.object({
  orderId: id, orderRef: text, outletId: text, depotCode: text, brandCode: text, districtName: text,
  requestedDate: date, deliveryDate: date, dateRolled: z.boolean(), temperature: text,
  itemCount: version, weightKg: number, volumeM3: number, status: text,
  deferralCount: version, placedAt: instant, rowVersion: version,
  lines: array(z.object({ productId: text, quantity: version })),
});
const stop = z.object({ sequence: version, orderId: id, outletId: text, plannedArrival: time,
  windowOpen: time, windowClose: time, serviceMinutes: number });
const check = z.object({ ruleId: text, passed: z.boolean(), reason: text, slack: number.nullish() });
// Issue #177: a plan of any size is read as a summary, then allocations in pages.
export const planSummaryOutput = z.object({
  planId: id, depotCode: text, serviceDate: date, planVersion: version, status: text,
  referenceVersionId: id, ruleSetVersionId: id, priorityPolicyVersionId: id,
  supersedes: optionalId, publishedAt: instant.nullish(), rowVersion: version,
  served: version, deferred: version, unservable: version,
  trips: array(z.object({ tripId: id, vehicleId: text, tripNumber: version, brandCode: text,
    districtName: text, temperature: text, weightKg: number, volumeM3: number,
    plannedMinutes: number, plannedDeparture: time, stopCount: version })),
});
export const allocationPageOutput = z.object({
  planId: id, planVersion: version, nextCursor: z.string().max(2048).nullish(),
  items: z.array(z.object({ orderId: id, decision: text, tripId: optionalId,
    stopSequence: version.nullish(), plannedArrival: time.nullish(),
    bindingRule: text.nullish(), reason: text, checks: array(check) })).max(100),
});
export const manifestOutput = z.object({
  tripId: id, planId: id, planVersion: version, depotCode: text, serviceDate: date,
  vehicleId: text, tripNumber: version, tripsForVehicle: version, brandCode: text,
  districtName: text, temperature: text, plannedDeparture: time, dockCode: text,
  weightCapKg: number, volumeCapM3: number, status: text, releasedAt: instant.nullish(), rowVersion: version,
  lines: array(z.object({ loadSequence: version, stopSequence: version, orderId: id,
    orderRef: text, outletId: text, districtName: text, temperature: text, itemCount: version,
    weightKg: number, volumeM3: number, status: text, loadedUnits: version, attempt: version,
    items: array(z.object({ lineNo: version, productId: text, units: version,
      status: text, loadedUnits: version, attempt: version })) })),
});
export const deliveryOutput = z.object({
  deliveryId: id, orderId: id, tripId: id, outletId: text, vehicleId: text, serviceDate: date,
  outcome: text, arrivedAt: instant.nullish(), serviceStartedAt: instant.nullish(),
  completedAt: instant.nullish(), waitMinutes: version.nullish(), lateMinutes: version.nullish(),
  timingUncertain: z.boolean(), deliveredUnits: version.nullish(), lowEvidence: z.boolean(),
  serverRecordedAt: instant, rowVersion: version,
});
export const receiptOutput = z.object({
  receiptId: id, orderId: id, deliveryId: id, outletId: text, status: text,
  confirmedAt: instant.nullish(), rowVersion: version, tripId: optionalId,
  depotCode: text, deliveredAt: instant, autoClosesAt: instant, late: z.boolean(),
  lines: array(z.object({ productId: text, expectedQuantity: version, receivedQuantity: version.nullish() })),
  // Personal (R-IAM-36): returned only with the mcp:ReadPersonal grant.
  note: text.nullish(), confirmedBy: optionalId,
});
export const issueOutput = z.object({ issueId: id, type: text, severity: text, status: text,
  depotCode: text, outletId: text.nullish(), subjects: array(z.object({ type: text, id: text })),
  resolutionAction: text.nullish(), raisedAt: instant, resolvedAt: instant.nullish(), rowVersion: version,
  // Personal (R-IAM-36): returned only with the mcp:ReadPersonal grant.
  description: text.nullish(), assignee: optionalId, resolutionNote: text.nullish(), raisedBy: optionalId });
export const auditOutput = z.object({ auditId: version, occurredAt: instant, actorId: optionalId,
  action: text, resource: text.nullish(), decision: z.enum(['ALLOW', 'DENY']), reason: text.nullish(),
  correlationId: text.nullish(), commandId: optionalId, targetType: text.nullish(),
  targetId: text.nullish(), policyGeneration: version.nullish() });
export const commandDecisionOutput = z.object({ commandId: id, auditRows: array(auditOutput),
  receipts: array(z.object({ actorId: optionalId, kind: text, status: version, recordedAt: instant })),
  policyGenerationAtDecision: version.nullish(), currentPolicyGeneration: version.nullish(),
  policyUnchangedSince: z.boolean().nullish() });
export const policyOutput = z.object({ policyId: id, name: text, defaultVersion: version, rowVersion: version });
export const readyTripOutput = z.object({
  tripId: id, vehicleId: text, tripNumber: version, tripsForVehicle: version,
  plannedDeparture: time, status: text, brandCode: text, districtName: text,
  temperature: text, dockCode: text, stopCount: version, orderCount: version,
  weightKg: number, volumeM3: number, rowVersion: version,
});
export const runSheetStopOutput = z.object({
  deliveryId: id, tripId: id, sequence: version, orderId: id, outletId: text,
  itemCount: version, plannedArrival: time, windowOpen: time, windowClose: time,
  outcome: text, proofCaptured: z.boolean(),
});
export const runSheetOutput = z.object({
  vehicleId: text, serviceDate: date, stops: array(runSheetStopOutput),
});
export const pendingReceiptOutput = z.object({
  orderId: id, deliveryId: id, outletId: text, deliveredAt: instant,
});
export const custodyOutput = z.object({
  orderId: id, receipt: receiptOutput,
  delivery: z.object({ deliveryId: id, tripId: optionalId, completedAt: instant,
    deliveredUnits: version.nullish() }),
  loadingCheck: z.object({ orderId: id, outletId: text, status: text,
    loadedUnits: version, attempt: version }).nullish(),
  proof: deliveryOutput.nullish(),
  unavailable: array(text),
});
export const pageOutput = <T extends z.ZodType>(item: T) => z.object({
  items: z.array(item).max(50), nextCursor: z.string().max(2048).nullish(),
});

/**
 * Field classes (issue #177, R-IAM-36). Every output field is operational unless
 * it is named here. Internal fields are versions and audit plumbing an assistant
 * may quote but should not reason about; they are always returned. Personal
 * fields name or describe people and are removed unless the person holds the
 * mcp:ReadPersonal grant, and the result then says so in `withheld`.
 */
export const fieldClasses = {
  personal: ['description', 'assignee', 'resolutionNote', 'raisedBy', 'note', 'confirmedBy'],
  internal: ['rowVersion', 'correlationId', 'policyGeneration', 'policyGenerationAtDecision', 'currentPolicyGeneration',
    'referenceVersionId', 'ruleSetVersionId', 'priorityPolicyVersionId'],
} as const;

const personal = new Set<string>(fieldClasses.personal);

/** Removes personal fields at any depth. Returns whether anything was removed. */
export function withholdPersonal(value: unknown): { value: unknown; withheld: boolean } {
  let withheld = false;
  const walk = (node: unknown): unknown => {
    if (Array.isArray(node)) return node.map(walk);
    if (node === null || typeof node !== 'object') return node;
    const out: Record<string, unknown> = {};
    for (const [key, child] of Object.entries(node)) {
      if (personal.has(key)) { if (child !== undefined && child !== null) withheld = true; continue; }
      out[key] = walk(child);
    }
    return out;
  };
  return { value: walk(value), withheld };
}
