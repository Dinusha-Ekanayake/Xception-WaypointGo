"use client";

import { useState } from "react";
import { newCommand } from "@shared/api/commands";
import { ApiError } from "@shared/api/problem";
import { useResource } from "@shared/api/useResource";
import {
  IssueCommandKind,
  ReceiptCommandKind,
  type DeliveryRecordView,
  type HandoverIssued,
  type HandoverView,
  type IssueType,
  type OrderView,
  type OutletView,
  type RaiseIssue,
  type ReceiptView,
} from "@shared/domain/types";
import { Icon, Notice, cx } from "@shared/ui";
import type { StoreGateway } from "../data/gateway.ts";
import { clock, temperatureLabel } from "../data/format.ts";
import { conflictMessage, type useCommands } from "../data/useCommands.ts";
import HandoverModal, { type IssuedPin } from "./HandoverModal.tsx";
import { BackButton, Badge, Button, Card, Chip, Facts, Modal, Muted, Stepper } from "../ui.tsx";

// Figma "06 Receive delivery" and "07 Delivery confirmed". The store reports what
// is wrong with each line, then submits its count. The driver's proof and this
// count are separate records and neither overwrites the other (R-RCP-04); a
// shortfall against a passing dock check is investigated, never settled here
// (R-RCP-07). Counting is per product line, as the receipt contract is (D-E): a
// line stands where Figma shows a package.
//
// Missing, damaged and wrong goods are not accepted, so they lower the count and
// the receipt is confirmed as partial. Damaged and wrong goods also raise an
// issue for the dispatcher. "Something else is wrong" disputes the whole
// delivery with a reason. The count is final when it is submitted; the answer
// then carries the one-time handover PIN of "06b" (R-RCP-09), which only shows
// that the driver handed over here and never holds anything up.

type Kind = "Missing" | "Damaged" | "Wrong item" | "Other";
const KINDS: Kind[] = ["Missing", "Damaged", "Wrong item", "Other"];
/** Other is a note on the line; it does not change what was counted. */
const LOWERS_COUNT: Record<Kind, boolean> = { Missing: true, Damaged: true, "Wrong item": true, Other: false };
const ISSUE_OF: Partial<Record<Kind, IssueType>> = { Damaged: "DAMAGED_GOODS", "Wrong item": "OTHER", Other: "OTHER" };
const TONE: Record<Kind, string> = {
  Missing: "bg-go-warning-tint text-go-warning-text",
  Damaged: "bg-go-danger-tint text-go-danger-strong",
  "Wrong item": "bg-go-danger-tint text-go-danger-strong",
  Other: "bg-go-canvas text-go-muted",
};

type Report = { productId: string; kind: Kind; units: number };
type Done = {
  queued: boolean;
  disputed: boolean;
  units: number;
  at: string;
  raised: number;
  /** As Waypoint returned it with the answer; null when none was issued or the answer is still on this device. */
  pin: IssuedPin | null;
  /** When the driver entered the PIN, once they have. */
  confirmedAt: string | null;
};

export default function Receive({
  gateway,
  orderId,
  order,
  delivery,
  outlet,
  commands,
  onBack,
  onViewIssues,
}: {
  gateway: StoreGateway;
  orderId: string;
  order: OrderView | null;
  delivery: DeliveryRecordView | null;
  outlet: OutletView | null;
  commands: ReturnType<typeof useCommands>;
  onBack: () => void;
  onViewIssues: () => void;
}): React.JSX.Element {
  const receipt = useResource((s) => gateway.receipt(orderId, s), orderId);
  const [line, setLine] = useState<string | null>(null);
  const [kind, setKind] = useState<Kind | null>(null);
  const [units, setUnits] = useState(1);
  const [reports, setReports] = useState<Report[]>([]);
  const [dispute, setDispute] = useState(false);
  const [note, setNote] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<Done | null>(null);
  const [reissued, setReissued] = useState<IssuedPin | null>(null);
  const [pinError, setPinError] = useState<string | null>(null);
  const r = receipt.data;
  // Where the PIN stands, for a receipt answered earlier. 404 means none was issued.
  const answered = r !== null && r.status !== "PENDING";
  const handover = useResource(answered && !done ? (s) => gateway.handover(orderId, s) : null, `${orderId}|${r?.status ?? ""}|${reissued?.pin ?? ""}`);

  if (!r) {
    return (
      <div className="flex flex-col gap-4">
        <BackButton onClick={onBack} />
        {receipt.error ? <Notice tone="danger" title="Could not open this delivery">{receipt.error.message}</Notice> : <Muted>Loading the delivery…</Muted>}
      </div>
    );
  }

  const closed = r.status !== "PENDING";
  const expected = r.lines.reduce((s, l) => s + l.expectedQuantity, 0);
  const lost = (productId: string) => reports.filter((x) => x.productId === productId && LOWERS_COUNT[x.kind]).reduce((s, x) => s + x.units, 0);
  const counted = r.lines.map((l) => ({ productId: l.productId, receivedQuantity: Math.max(0, l.expectedQuantity - lost(l.productId)) }));
  const received = counted.reduce((s, l) => s + l.receivedQuantity, 0);
  const selected = r.lines.find((l) => l.productId === line) ?? null;
  const room = selected ? Math.max(0, selected.expectedQuantity - lost(selected.productId)) : 0;
  const canAdd = !closed && selected !== null && kind !== null && (!LOWERS_COUNT[kind] || (units >= 1 && units <= room)) && (LOWERS_COUNT[kind] || reports.every((x) => !(x.productId === line && x.kind === kind)));

  const add = () => {
    if (!canAdd || !selected || !kind) return;
    setReports((x) => [...x, { productId: selected.productId, kind, units: LOWERS_COUNT[kind] ? units : 0 }]);
    setKind(null);
    setUnits(1);
  };

  const submit = async () => {
    setError(null);
    const lowered = reports.some((x) => LOWERS_COUNT[x.kind] && x.units > 0);
    const [command, payload] = dispute
      ? [ReceiptCommandKind.dispute, { orderId, reason: note.trim(), lines: counted }]
      : lowered
        ? [ReceiptCommandKind.confirmPartial, { orderId, lines: counted, note: note.trim() || null }]
        : [ReceiptCommandKind.confirm, { orderId }];
    const outcome = await commands.run(command, payload, r.rowVersion);
    if (!outcome.ok) {
      setError(conflictMessage(outcome.error));
      receipt.refresh();
      return;
    }
    // The receipt stands whether or not an issue reaches the dispatcher; a failed raise is said, not hidden.
    const raised = await raiseIssues(r, order, reports, commands.run);
    if (raised.failed > 0) setError(`Your count is recorded, but ${raised.failed} issue${raised.failed === 1 ? "" : "s"} could not be sent. Tell the dispatcher.`);
    const issued = outcome.queued ? null : (outcome.ack.result as HandoverIssued);
    setDone({
      queued: outcome.queued,
      disputed: dispute,
      units: received,
      at: new Date().toISOString(),
      raised: raised.sent,
      pin: issued?.handoverPin && issued.handoverExpiresAt ? { pin: issued.handoverPin, expiresAt: issued.handoverExpiresAt } : null,
      confirmedAt: null,
    });
  };

  /** A new PIN for a receipt answered earlier: the first was lost with the screen, expired or locked. Online only. */
  const newPin = async (h: HandoverView) => {
    setPinError(null);
    try {
      const ack = await gateway.send(newCommand(ReceiptCommandKind.reissueHandoverPin, { orderId }, h.rowVersion));
      const result = ack.result as { handoverPin: string; handoverExpiresAt: string };
      setReissued({ pin: result.handoverPin, expiresAt: result.handoverExpiresAt });
    } catch (failure) {
      setPinError(failure instanceof ApiError ? conflictMessage(failure) : "A new PIN needs a connection. Try again when you are back online.");
      handover.refresh();
    }
  };

  const differs = reports.length > 0 || dispute;
  return (
    <div className="flex flex-col gap-4">
      <BackButton onClick={onBack} />
      <div className="flex flex-col gap-1">
        <h1 className="text-[32px] leading-tight font-medium text-black">Receive delivery</h1>
        <Muted>
          {delivery ? `${delivery.vehicleId} is at your ${outlet?.dockType ?? ""} dock${delivery.arrivedAt ? ` · arrived ${clock(delivery.arrivedAt)}` : ""}` : order ? `${order.orderRef} · ${temperatureLabel(order.temperature)}` : "Order"}
          {r.confirmedAt ? ` · recorded ${clock(r.confirmedAt)}` : ""}
        </Muted>
      </div>
      {closed ? (
        <Notice tone="info" title={`Already recorded as ${r.status.toLowerCase().replace("_", " ")}`} />
      ) : (
        <p className="flex items-center gap-3 rounded-[16px] bg-[#d3f0e9] px-4 py-3 text-[15px] text-black">
          <span aria-hidden className="size-2.5 shrink-0 rounded-full bg-go-success" />
          Report any issues, then submit count
        </p>
      )}
      {error && <Notice tone="danger" live title={error} />}

      <div className="flex flex-col gap-5 lg:grid lg:grid-cols-[minmax(0,1fr)_380px] lg:items-start">
        <div className="flex min-w-0 flex-col gap-5">
          <Card label="Report an issue">
            <h2 className="text-[18px] font-medium text-black">Report a package issue</h2>
            <p className="text-[13px] text-go-muted">
              {order ? `${order.orderRef} · ${temperatureLabel(order.temperature)}` : "Order"} · {expected} {expected === 1 ? "unit" : "units"}
            </p>
            <ul className="grid gap-2 sm:grid-cols-2" aria-label="Items">
              {r.lines.map((l) => {
                const flagged = reports.filter((x) => x.productId === l.productId);
                return (
                  <li key={l.productId}>
                    <button
                      type="button"
                      disabled={closed}
                      aria-pressed={line === l.productId}
                      onClick={() => setLine(l.productId)}
                      className={cx(
                        "flex min-h-14 w-full flex-col rounded-[14px] px-3 py-2 text-left",
                        flagged.some((x) => x.kind === "Damaged" || x.kind === "Wrong item") ? "bg-go-danger-tint" : flagged.length ? "bg-go-warning-tint" : "bg-go-canvas",
                        line === l.productId && "outline-2 outline-[#0f766e]",
                      )}
                    >
                      <span className="text-[11px] text-go-muted">
                        {l.expectedQuantity} expected{flagged.length > 0 && ` · ${flagged.map((x) => x.kind).join(", ")}`}
                      </span>
                      <span className="truncate text-[14px] font-medium text-black">{l.productId}</span>
                    </button>
                  </li>
                );
              })}
            </ul>
            {!closed && (
              <>
                <div role="radiogroup" aria-label="Issue" className="flex flex-wrap gap-2">
                  {KINDS.map((k) => (
                    <button
                      key={k}
                      type="button"
                      role="radio"
                      aria-checked={kind === k}
                      onClick={() => setKind(k)}
                      className={cx("min-h-12 rounded-full px-4 text-[14px]", kind === k ? "bg-[#031a0c] text-white" : "bg-go-canvas text-black")}
                    >
                      {k}
                    </button>
                  ))}
                </div>
                <div className="flex items-center gap-3">
                  {kind && LOWERS_COUNT[kind] && selected && <Stepper value={units} label="Units affected" onChange={(n) => setUnits(Math.min(Math.max(1, n), Math.max(1, room)))} />}
                  <span className="flex-1" />
                  <button type="button" disabled={!canAdd} onClick={add} className="min-h-12 rounded-[22px] bg-go-mint px-6 text-[15px] font-medium text-black disabled:bg-[#d5dad9] disabled:text-white">
                    Add issue
                  </button>
                </div>
              </>
            )}
          </Card>

          {reports.length > 0 && (
            <Card label="Reported">
              <h2 className="text-[18px] font-medium text-black">Reported · {reports.length} {reports.length === 1 ? "item" : "items"}</h2>
              <ul className="flex flex-col gap-2">
                {reports.map((x, i) => (
                  <li key={`${x.productId}-${x.kind}-${i}`} className="flex items-center gap-2 text-[14px] text-black">
                    <span className="min-w-0 flex-1 truncate">
                      {x.productId}
                      {x.units > 0 && ` · ${x.units}`}
                    </span>
                    <Chip tone={x.kind === "Missing" || x.kind === "Other" ? "warn" : "danger"}>{x.kind}</Chip>
                    <button type="button" aria-label={`Remove ${x.kind} for ${x.productId}`} onClick={() => setReports((all) => all.filter((_, j) => j !== i))} className="flex size-12 items-center justify-center">
                      <Icon name="close" />
                    </button>
                  </li>
                ))}
              </ul>
            </Card>
          )}
        </div>

        <Card label="Receipt summary" className="lg:sticky lg:top-8">
          <h2 className="text-[20px] font-medium text-black">Receipt summary</h2>
          <div className="grid grid-cols-2 gap-2.5">
            <div className="flex flex-col rounded-[16px] bg-go-canvas px-3.5 py-3">
              <span className="text-[12px] text-go-muted">Units</span>
              <span className="text-[28px] leading-tight font-semibold text-black">{expected}</span>
            </div>
            <div className="flex flex-col rounded-[16px] bg-go-canvas px-3.5 py-3">
              <span className="text-[12px] text-go-muted">Received OK</span>
              <span className="text-[28px] leading-tight font-semibold text-black">{received}</span>
            </div>
          </div>
          <p className="flex justify-between text-[14px]">
            <span className="text-go-muted">Issues reported</span>
            <span className={reports.length > 0 ? "font-medium text-go-danger-strong" : "text-black"}>{reports.length}</span>
          </p>
          {differs && <p className="text-[13px] text-go-muted">Sent to the dispatcher</p>}
          {!closed && (dispute || reports.length > 0) && (
            <label className="flex flex-col gap-1 text-[13px] text-go-muted">
              {dispute ? "What is wrong? (required)" : "Note for the dispatcher (optional)"}
              <textarea value={note} onChange={(e) => setNote(e.target.value)} rows={2} className="rounded-[16px] border border-[#dfe7e6] p-3 text-[15px] text-black" />
            </label>
          )}
          {closed && !done && <HandoverCard handover={handover.data} missing={handover.error instanceof ApiError && handover.error.status === 404} error={pinError} onNewPin={(h) => void newPin(h)} />}
          {!closed && (
            <>
              <Button large disabled={commands.busy || (dispute && !note.trim())} onClick={() => void submit()}>
                {commands.busy ? "Sending…" : dispute ? "Send dispute" : "Submit count"}
              </Button>
              <Button tone="plain" onClick={() => setDispute((d) => !d)}>
                {dispute ? "Back to the count" : "Something else is wrong"}
              </Button>
            </>
          )}
        </Card>
      </div>

      {done && done.pin && !done.confirmedAt && (
        <HandoverModal
          gateway={gateway}
          orderId={orderId}
          issued={done.pin}
          onConfirmed={(h) => setDone((d) => (d ? { ...d, confirmedAt: h.confirmedAt ?? new Date().toISOString() } : d))}
          onClose={onBack}
        />
      )}
      {reissued && (
        <HandoverModal
          gateway={gateway}
          orderId={orderId}
          issued={reissued}
          onConfirmed={() => {
            setReissued(null);
            handover.refresh();
          }}
          onClose={() => {
            setReissued(null);
            handover.refresh();
          }}
        />
      )}
      {done && (!done.pin || done.confirmedAt) && (
        <Modal label="Delivery confirmed" onClose={onBack}>
          <Badge>
            <Icon name={done.queued ? "clock" : "check"} />
          </Badge>
          <div className="flex flex-col items-center gap-1 text-center">
            <h2 className="text-[26px] font-medium text-black">{done.queued ? "Saved on this phone" : done.disputed ? "Delivery disputed" : "Delivery confirmed"}</h2>
            <p className="text-[14px] text-go-muted">
              {done.queued ? "It is sent when the connection returns." : `${done.units} units received · recorded at ${clock(done.at)}`}
            </p>
          </div>
          {!done.queued && (
            <Facts
              rows={[
                { label: "Your count", value: "On record", strong: false },
                ...(done.confirmedAt ? [{ label: "Handover", value: `Confirmed with PIN · ${clock(done.confirmedAt)}`, strong: false }] : []),
                { label: "Issues sent", value: done.raised > 0 ? `${done.raised} to the dispatcher` : "None", strong: false },
              ]}
            />
          )}
          <div className="flex gap-2.5">
            {(done.raised > 0 || done.disputed) && !done.queued && (
              <Button tone="plain" large onClick={onViewIssues}>
                View issue
              </Button>
            )}
            <Button large onClick={onBack}>
              Done
            </Button>
          </div>
        </Modal>
      )}
    </div>
  );
}

/**
 * Where the handover PIN stands on a receipt answered earlier. The PIN itself is
 * never shown again (only a hash is kept), so a lost one is replaced.
 */
function HandoverCard({
  handover,
  missing,
  error,
  onNewPin,
}: {
  handover: HandoverView | null;
  missing: boolean;
  error: string | null;
  onNewPin: (h: HandoverView) => void;
}): React.JSX.Element {
  const words = !handover
    ? missing
      ? "No handover PIN was issued for this delivery."
      : "Checking the handover…"
    : handover.status === "CONFIRMED"
      ? `Confirmed with PIN${handover.confirmedAt ? ` at ${clock(handover.confirmedAt)}` : ""}.`
      : handover.status === "AWAITING"
        ? `Waiting for the driver to enter the PIN · expires ${clock(handover.expiresAt)}.`
        : handover.status === "LOCKED"
          ? "Locked after five wrong entries."
          : "The PIN expired before the driver entered it.";
  return (
    <section aria-label="Handover" className="flex flex-col gap-2 rounded-[16px] bg-go-canvas px-4 py-3">
      <h3 className="text-[15px] font-medium text-black">Handover</h3>
      <p className="text-[14px] text-black">{words}</p>
      {error && <p className="text-[13px] font-medium text-go-danger-strong">{error}</p>}
      {handover && handover.status !== "CONFIRMED" && (
        <Button tone="plain" onClick={() => onNewPin(handover)}>
          Get a new PIN
        </Button>
      )}
    </section>
  );
}

/** One issue per kind with what was reported, so a short list does not become a flood. */
async function raiseIssues(
  r: ReceiptView,
  order: OrderView | null,
  reports: Report[],
  run: ReturnType<typeof useCommands>["run"],
): Promise<{ sent: number; failed: number }> {
  const byType = new Map<IssueType, Report[]>();
  for (const x of reports) {
    const type = ISSUE_OF[x.kind];
    if (type) byType.set(type, [...(byType.get(type) ?? []), x]);
  }
  let sent = 0;
  let failed = 0;
  for (const [type, items] of byType) {
    const payload: RaiseIssue = {
      type,
      severity: type === "DAMAGED_GOODS" ? "MEDIUM" : "LOW",
      depotCode: r.depotCode,
      outletId: r.outletId,
      subjects: [
        { type: "order", id: r.orderId },
        { type: "receipt", id: r.receiptId },
      ],
      description: `${order?.orderRef ?? "Order"}: ${items.map((x) => `${x.productId}${x.units > 0 ? ` x${x.units}` : ""} (${x.kind.toLowerCase()})`).join(", ")}`,
    };
    const outcome = await run(IssueCommandKind.raise, payload, null);
    if (outcome.ok) sent++;
    else failed++;
  }
  return { sent, failed };
}
