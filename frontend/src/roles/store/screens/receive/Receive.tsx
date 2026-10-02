"use client";

import { useEffect, useRef, useState } from "react";
import { newCommand } from "@shared/api/commands";
import { ApiError } from "@shared/api/problem";
import { useResource } from "@shared/api/useResource";
import {
  IssueCommandKind,
  ReceiptCommandKind,
  type DeliveryRecordView,
  type HandoverIssued,
  type HandoverView,
  type OrderView,
  type OutletView,
  type RaiseIssue,
} from "@shared/domain/types";
import { Icon, Notice } from "@shared/ui";
import type { StoreGateway } from "../../data/gateway.ts";
import { clock, temperatureLabel } from "../../data/format.ts";
import { shrinkPhoto } from "../../data/photo.ts";
import { answerFor, counted, knownShortages, LOWERS_COUNT, room as roomFor, type Kind, type Report } from "../../data/receive.ts";
import { conflictMessage, type useCommands } from "../../data/useCommands.ts";
import { BackButton, Badge, Button, Facts, Modal, Muted } from "../../ui.tsx";
import HandoverModal, { type IssuedPin } from "../HandoverModal.tsx";
import HandoverCard from "./HandoverCard.tsx";
import PhotoDialog from "./PhotoDialog.tsx";
import ReportCard from "./ReportCard.tsx";
import ReportedList from "./ReportedList.tsx";
import SummaryCard from "./SummaryCard.tsx";

// Figma "06 Receive delivery", "06-1" to "06-7", "06b" and "07". The store
// reports what is wrong, item by item, with photos, then submits its count once.
// One delivery problem is one report for the dispatcher: the answer's note, from
// which Issues opens the investigation with the photos (R-RCP-07). What the
// loader kept back at the dock lowers the count without being reported twice.
// The driver's proof and this count are separate records (R-RCP-04). The answer
// carries the one-time handover PIN (R-RCP-09), evidence that never holds
// anything up.

type Photo = { id: string; blob: Blob; url: string };
type Done = { queued: boolean; disputed: boolean; units: number; at: string; sent: boolean; pin: IssuedPin | null; confirmedAt: string | null };

export default function Receive({
  gateway,
  orderId,
  order,
  delivery,
  outlet,
  siblings,
  commands,
  onSwitch,
  onBack,
  onViewIssues,
}: {
  gateway: StoreGateway;
  orderId: string;
  order: OrderView | null;
  delivery: DeliveryRecordView | null;
  outlet: OutletView | null;
  /** This vehicle's orders still waiting to be counted, this one included (06-4). */
  siblings: OrderView[];
  commands: ReturnType<typeof useCommands>;
  onSwitch: (orderId: string) => void;
  onBack: () => void;
  onViewIssues: () => void;
}): React.JSX.Element {
  const receipt = useResource((s) => gateway.receipt(orderId, s), orderId);
  // The loading check of this order: what the loader kept back. Unknown when Loading cannot say.
  const custody = useResource((s) => gateway.custody(orderId, s), `custody|${orderId}`);
  const [line, setLine] = useState<string | null>(null);
  const [kind, setKind] = useState<Kind | null>(null);
  const [units, setUnits] = useState(1);
  const [reports, setReports] = useState<Report[]>([]);
  const [photos, setPhotos] = useState<Record<string, Photo>>({});
  const [pending, setPending] = useState<string[]>([]);
  const [viewing, setViewing] = useState<string | null>(null);
  const [addError, setAddError] = useState<string | null>(null);
  const [dispute, setDispute] = useState(false);
  const [note, setNote] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<Done | null>(null);
  const [reissued, setReissued] = useState<IssuedPin | null>(null);
  const [pinError, setPinError] = useState<string | null>(null);
  const camera = useRef<HTMLInputElement>(null);
  const r = receipt.data;
  const answered = r !== null && r.status !== "PENDING";
  const handover = useResource(answered && !done ? (s) => gateway.handover(orderId, s) : null, `${orderId}|${r?.status ?? ""}|${reissued?.pin ?? ""}`);

  // Photos are local object URLs until sent; let them go with the screen.
  const urls = useRef<string[]>([]);
  useEffect(() => () => urls.current.forEach((u) => URL.revokeObjectURL(u)), []);

  if (!r) {
    return (
      <div className="flex flex-col gap-4">
        <BackButton onClick={onBack} />
        {receipt.error ? <Notice tone="danger" title="Could not open this delivery">{receipt.error.message}</Notice> : <Muted>Loading the delivery…</Muted>}
      </div>
    );
  }

  const closed = r.status !== "PENDING";
  const known = knownShortages(custody.data?.loadingCheck?.items);
  const expected = r.lines.reduce((s, l) => s + l.expectedQuantity, 0);
  const count = counted(r.lines, reports, known);
  const received = count.reduce((s, l) => s + l.receivedQuantity, 0);
  const selected = r.lines.find((l) => l.productId === line) ?? null;
  const room = selected ? roomFor(selected, reports, known) : 0;
  const answer = answerFor(r.lines, reports, known, dispute, note);
  const sends = answer.kind !== "confirm" && !(answer.kind === "partial" && answer.note === null);

  const takePhoto = () => camera.current?.click();
  const onPhoto = async (file: File | undefined) => {
    if (!file) return;
    try {
      const blob = await shrinkPhoto(file);
      const photo = { id: crypto.randomUUID(), blob, url: URL.createObjectURL(blob) };
      urls.current.push(photo.url);
      setPhotos((all) => ({ ...all, [photo.id]: photo }));
      setPending((ids) => [...ids, photo.id]);
      setViewing(photo.id);
    } catch (failure) {
      setAddError(failure instanceof Error ? failure.message : "The photo could not be used.");
    }
  };
  const retake = (id: string) => {
    setPending((ids) => ids.filter((x) => x !== id));
    setViewing(null);
    takePhoto();
  };

  const add = () => {
    if (!selected) return setAddError("Choose a package first.");
    if (!kind) return setAddError("Choose an issue type.");
    if (LOWERS_COUNT[kind] && room < 1) return setAddError("Every unit of this item is already reported.");
    setAddError(null);
    setReports((x) => [...x, { productId: selected.productId, kind, units: LOWERS_COUNT[kind] ? Math.min(units, room) : 0, photoIds: pending }]);
    setPending([]);
    setKind(null);
    setUnits(1);
  };

  const submit = async () => {
    setError(null);
    // The photos first, kept on this device and sent when they can be; the server
    // links them to the investigation whichever arrives first.
    for (const id of reports.flatMap((x) => x.photoIds)) {
      const kept = await gateway.keepPhoto({ id, orderId, receiptId: r.receiptId, blob: photos[id]!.blob });
      if (!kept.durable) return setError(`A photo could not be kept on this device: ${kept.reason ?? "storage unavailable"}. Remove it or try again.`);
    }
    void gateway.sendPhotos().catch(() => undefined);

    const outcome =
      answer.kind === "dispute"
        ? await commands.run(ReceiptCommandKind.dispute, { orderId, reason: answer.reason, lines: count }, r.rowVersion)
        : answer.kind === "partial"
          ? await commands.run(ReceiptCommandKind.confirmPartial, { orderId, lines: count, note: answer.note }, r.rowVersion)
          : await commands.run(ReceiptCommandKind.confirm, { orderId }, r.rowVersion);
    if (!outcome.ok) {
      setError(conflictMessage(outcome.error));
      receipt.refresh();
      return;
    }
    // A remark on a complete delivery has no shortage to investigate: it is one issue of its own.
    if (answer.kind === "confirm-and-raise") {
      const raise: RaiseIssue = {
        type: "OTHER",
        severity: "LOW",
        depotCode: r.depotCode,
        outletId: r.outletId,
        subjects: [
          { type: "order", id: r.orderId },
          { type: "receipt", id: r.receiptId },
        ],
        description: `${order?.orderRef ?? "Order"}: ${answer.description}`,
        attachmentIds: reports.flatMap((x) => x.photoIds),
      };
      const raised = await commands.run(IssueCommandKind.raise, raise, null);
      if (!raised.ok) setError(`Your count is recorded, but the remark could not be sent: ${raised.error.message}`);
    }
    const issued = outcome.queued ? null : (outcome.ack.result as HandoverIssued);
    setDone({
      queued: outcome.queued,
      disputed: answer.kind === "dispute",
      units: received,
      at: new Date().toISOString(),
      sent: sends,
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

  const viewed = viewing ? photos[viewing] : null;
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
      {custody.error && !closed && (
        <Notice tone="warning" title="The loading check could not be read">
          Count what arrived as usual. Anything the loader kept back cannot be marked for you.
        </Notice>
      )}
      {error && <Notice tone="danger" live title={error} />}

      <div className="flex flex-col gap-5 lg:grid lg:grid-cols-[minmax(0,1fr)_380px] lg:items-start">
        <div className="flex min-w-0 flex-col gap-5">
          <ReportCard
            orderId={orderId}
            siblings={siblings.length ? siblings : order ? [order] : []}
            onSwitch={onSwitch}
            lines={r.lines}
            known={known}
            reports={reports}
            selected={line}
            onSelect={(id) => {
              setLine(id);
              setAddError(null);
            }}
            kind={kind}
            onKind={(k) => {
              setKind(k);
              setAddError(null);
            }}
            units={units}
            room={room}
            onUnits={setUnits}
            photos={pending.map((id) => ({ id, url: photos[id]!.url }))}
            onTakePhoto={takePhoto}
            onOpenPhoto={setViewing}
            onAdd={add}
            addError={addError}
            closed={closed}
          />
          <ReportedList reports={reports} known={known} onRemove={(i) => setReports((all) => all.filter((_, j) => j !== i))} />
        </div>

        <SummaryCard
          expected={expected}
          received={received}
          problems={reports.length + known.size}
          sends={sends}
          closed={closed}
          dispute={dispute}
          note={note}
          onNote={setNote}
          busy={commands.busy}
          onSubmit={() => void submit()}
          onToggleDispute={() => setDispute((d) => !d)}
        >
          {closed && !done && (
            <HandoverCard
              handover={handover.data}
              missing={handover.error instanceof ApiError && handover.error.status === 404}
              error={pinError}
              onNewPin={(h) => void newPin(h)}
            />
          )}
        </SummaryCard>
      </div>

      <input
        ref={camera}
        type="file"
        accept="image/*"
        capture="environment"
        aria-label="Take a photo of the problem"
        className="hidden"
        onChange={(e) => {
          void onPhoto(e.target.files?.[0]);
          e.target.value = "";
        }}
      />
      {viewed && <PhotoDialog url={viewed.url} onRetake={() => retake(viewed.id)} onClose={() => setViewing(null)} />}

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
            <p className="text-[14px] text-go-muted">{done.queued ? "It is sent when the connection returns." : `${done.units} units received · recorded at ${clock(done.at)}`}</p>
          </div>
          {!done.queued && (
            <Facts
              rows={[
                { label: "Your count", value: "On record", strong: false },
                ...(done.confirmedAt ? [{ label: "Handover", value: `Confirmed with PIN · ${clock(done.confirmedAt)}`, strong: false }] : []),
                { label: "Dispatcher", value: done.sent ? "Told what is wrong" : "Nothing new to tell", strong: false },
              ]}
            />
          )}
          <div className="flex gap-2.5">
            {done.sent && !done.queued && (
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
