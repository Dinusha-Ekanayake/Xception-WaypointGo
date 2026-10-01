"use client";

import { useEffect, useState } from "react";
import { useResource } from "@shared/api/useResource";
import { ReceiptCommandKind, type OrderView } from "@shared/domain/types";
import { Icon, Notice } from "@shared/ui";
import type { StoreGateway } from "../data/gateway.ts";
import { clock, temperatureLabel } from "../data/format.ts";
import { conflictMessage, type useCommands } from "../data/useCommands.ts";
import { BackButton, Button, Card, Muted, Stepper } from "../ui.tsx";

// Figma "06 Receive delivery" and "07 Delivery confirmed". The store counts what
// arrived and confirms it, confirms part of it, or disputes it. The driver's
// proof and this count are separate records and neither overwrites the other
// (R-RCP-04); a shortfall against a passing dock check is investigated, never
// settled here (R-RCP-07).

type Mode = "count" | "partial" | "dispute";

export default function Receive({
  gateway,
  orderId,
  order,
  commands,
  onBack,
}: {
  gateway: StoreGateway;
  orderId: string;
  order: OrderView | null;
  commands: ReturnType<typeof useCommands>;
  onBack: () => void;
}): React.JSX.Element {
  const receipt = useResource((s) => gateway.receipt(orderId, s), orderId);
  const [counted, setCounted] = useState<Record<string, number>>({});
  const [mode, setMode] = useState<Mode>("count");
  const [note, setNote] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<{ queued: boolean; mode: Mode } | null>(null);
  const r = receipt.data;

  useEffect(() => {
    if (r) setCounted(Object.fromEntries(r.lines.map((l) => [l.productId, l.receivedQuantity ?? l.expectedQuantity])));
  }, [r]);

  if (done) {
    return (
      <div className="flex flex-col items-center gap-4 pt-10 text-center">
        <span className="flex size-16 items-center justify-center rounded-full bg-go-success">
          <Icon name="check-white" />
        </span>
        <h1 className="text-[28px] font-medium text-black">{done.queued ? "Saved on this phone" : "Delivery recorded"}</h1>
        <p className="max-w-[320px] text-[15px] text-go-muted">
          {done.queued
            ? "It is sent when the connection returns."
            : done.mode === "count"
              ? "Thank you. Everything is on record as received."
              : "Your count is on record. The dispatcher sees the difference and follows it up with the depot."}
        </p>
        <Button onClick={onBack}>Back to home</Button>
      </div>
    );
  }

  if (!r) {
    return (
      <div className="flex flex-col gap-4">
        <BackButton onClick={onBack} />
        {receipt.error ? <Notice tone="danger" title="Could not open this delivery">{receipt.error.message}</Notice> : <Muted>Loading the delivery…</Muted>}
      </div>
    );
  }

  const lines = r.lines.map((l) => ({ ...l, got: counted[l.productId] ?? l.expectedQuantity }));
  const differs = lines.filter((l) => l.got !== l.expectedQuantity);
  const closed = r.status !== "PENDING";
  // Nothing differs: a plain confirm. Something differs: accept it or dispute it.
  const m: Mode = differs.length === 0 ? "count" : mode === "count" ? "partial" : mode;

  const submit = async () => {
    setError(null);
    const received = lines.map((l) => ({ productId: l.productId, receivedQuantity: l.got }));
    const [kind, payload] =
      m === "count"
        ? [ReceiptCommandKind.confirm, { orderId }]
        : m === "partial"
          ? [ReceiptCommandKind.confirmPartial, { orderId, lines: received, note: note.trim() || null }]
          : [ReceiptCommandKind.dispute, { orderId, reason: note.trim(), lines: received }];
    const outcome = await commands.run(kind, payload, r.rowVersion);
    if (!outcome.ok) {
      setError(conflictMessage(outcome.error));
      receipt.refresh();
    } else setDone({ queued: outcome.queued, mode: m });
  };

  return (
    <div className="flex flex-col gap-4">
      <BackButton onClick={onBack} />
      <div className="flex flex-col gap-1">
        <h1 className="text-[32px] leading-tight font-medium text-black">Receive delivery</h1>
        <Muted>
          {order ? `${order.orderRef} · ${temperatureLabel(order.temperature)}` : "Order"}
          {r.confirmedAt ? ` · recorded ${clock(r.confirmedAt)}` : ""}
        </Muted>
      </div>
      {closed ? (
        <Notice tone="info" title={`Already recorded as ${r.status.toLowerCase().replace("_", " ")}`} />
      ) : (
        <p className="flex items-center gap-3 rounded-[16px] bg-[#d3f0e9] px-4 py-3 text-[15px] text-black">
          <span aria-hidden className="size-2.5 shrink-0 rounded-full bg-go-success" />
          Count each line, change any that differ, then confirm.
        </p>
      )}
      {error && <Notice tone="danger" live title={error} />}

      <Card label="What arrived">
        <h2 className="text-[18px] font-medium text-black">What arrived</h2>
        <ul className="flex flex-col gap-2">
          {lines.map((l) => (
            <li key={l.productId} className="flex items-center gap-2 rounded-[16px] bg-go-canvas py-2 pr-2 pl-3.5">
              <div className="flex min-w-0 flex-1 flex-col">
                <span className="truncate text-[15px] font-medium text-black">{l.productId}</span>
                <span className={l.got === l.expectedQuantity ? "text-[13px] text-go-muted" : "text-[13px] font-medium text-go-danger-strong"}>
                  expected {l.expectedQuantity}
                  {l.got !== l.expectedQuantity && ` · ${l.got < l.expectedQuantity ? `${l.expectedQuantity - l.got} short` : `${l.got - l.expectedQuantity} extra`}`}
                </span>
              </div>
              {closed ? (
                <span className="px-3 text-[17px] font-semibold">{l.got}</span>
              ) : (
                <Stepper value={l.got} label={l.productId} onChange={(n) => setCounted((c) => ({ ...c, [l.productId]: n }))} />
              )}
            </li>
          ))}
        </ul>
      </Card>

      {!closed && differs.length > 0 && (
        <Card label="Something is different">
          <h2 className="text-[18px] font-medium text-black">
            {differs.length} {differs.length === 1 ? "line differs" : "lines differ"}
          </h2>
          <div className="flex gap-2" role="radiogroup" aria-label="What to record">
            {(["partial", "dispute"] as const).map((x) => (
              <button
                key={x}
                type="button"
                role="radio"
                aria-checked={m === x}
                onClick={() => setMode(x)}
                className={`min-h-12 flex-1 rounded-[18px] px-3 text-[15px] font-medium ${m === x ? "bg-[#031a0c] text-white" : "bg-go-canvas text-black"}`}
              >
                {x === "partial" ? "Accept what came" : "Dispute"}
              </button>
            ))}
          </div>
          <label className="flex flex-col gap-1 text-[13px] text-go-muted">
            {m === "dispute" ? "What is wrong? (required)" : "Note for the dispatcher (optional)"}
            <textarea value={note} onChange={(e) => setNote(e.target.value)} rows={2} className="rounded-[16px] border border-[#dfe7e6] p-3 text-[15px] text-black" />
          </label>
        </Card>
      )}

      {!closed && (
        <Button
          large
          disabled={commands.busy || (m === "dispute" && !note.trim())}
          onClick={() => void submit()}
        >
          {commands.busy ? "Sending…" : differs.length === 0 ? "Everything arrived · confirm" : m === "dispute" ? "Send dispute" : "Confirm what arrived"}
        </Button>
      )}
    </div>
  );
}
