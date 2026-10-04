"use client";

import { useRef, useState } from "react";
import { IssueCommandKind, type OrderView, type RaiseIssue } from "@shared/domain/types";
import { friendlyError } from "@shared/api/problem";
import { Icon, Notice, cx } from "@shared/ui";
import type { StoreGateway } from "../../data/gateway.ts";
import { units as unitsText, dayLabel, temperatureLabel } from "../../data/format.ts";
import { REPORT_KINDS, type ReportKind } from "../../data/issues.ts";
import { shrinkPhoto } from "../../data/photo.ts";
import { LOWERS_COUNT, noteOf } from "../../data/receive.ts";
import type { useCommands } from "../../data/useCommands.ts";
import { Badge, Button, Field, Modal, Stepper } from "../../ui.tsx";
import PhotoDialog from "../receive/PhotoDialog.tsx";

// "08b Report an issue" and "08c Issue sent": a problem found after unpacking.
// The report names its order and item so the dispatcher can act on it; photos
// go through the same offline queue as the count's, and the issue links them
// whichever reaches the server first.

type Photo = { id: string; blob: Blob; url: string };

export default function ReportIssue({
  gateway,
  orders,
  preset,
  commands,
  onClose,
  onSent,
}: {
  gateway: StoreGateway;
  /** Deliveries of the last 48 hours, newest first. */
  orders: OrderView[];
  preset: ReportKind | null;
  commands: ReturnType<typeof useCommands>;
  onClose: () => void;
  onSent: () => void;
}): React.JSX.Element {
  // The orders can arrive after the dialog opens, so an unset choice falls back to the first.
  const [pickedOrder, setOrderId] = useState(orders[0]?.orderId ?? "");
  const order = orders.find((o) => o.orderId === pickedOrder) ?? orders[0] ?? null;
  const orderId = order?.orderId ?? "";
  const [pickedLine, setProductId] = useState(order?.lines[0]?.productId ?? "");
  const line = order?.lines.find((l) => l.productId === pickedLine) ?? order?.lines[0] ?? null;
  const productId = line?.productId ?? "";
  const [kind, setKind] = useState<ReportKind | null>(preset);
  const [units, setUnits] = useState(1);
  const [comment, setComment] = useState("");
  const [photos, setPhotos] = useState<Photo[]>([]);
  const [viewing, setViewing] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [orderError, setOrderError] = useState<string | null>(null);
  const [itemError, setItemError] = useState<string | null>(null);
  const [sent, setSent] = useState<"sent" | "queued" | null>(null);
  const camera = useRef<HTMLInputElement>(null);

  const takePhoto = () => camera.current?.click();
  const onPhoto = async (file: File | undefined) => {
    if (!file) return;
    try {
      const blob = await shrinkPhoto(file);
      const photo = { id: crypto.randomUUID(), blob, url: URL.createObjectURL(blob) };
      setPhotos((all) => [...all, photo]);
      setViewing(photo.id);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "The photo could not be used.");
    }
  };

  const send = async () => {
    setOrderError(null);
    setItemError(null);
    if (!order) return setOrderError("Choose the order.");
    if (!line) return setItemError("Choose the item.");
    if (!kind) return setError("Choose what is wrong.");
    setError(null);
    for (const p of photos) {
      const kept = await gateway.keepPhoto({ id: p.id, orderId: order.orderId, receiptId: null, blob: p.blob });
      if (!kept.durable) return setError(`A photo could not be kept on this device: ${kept.reason ?? "storage unavailable"}.`);
    }
    void gateway.sendPhotos().catch(() => undefined);
    const lowers = LOWERS_COUNT[kind.kind];
    const words = noteOf([{ productId: line.productId, kind: kind.kind, units: lowers ? units : 0, photoIds: photos.map((p) => p.id) }], comment);
    const raise: RaiseIssue = {
      type: kind.type,
      severity: kind.type === "DAMAGED_GOODS" ? "MEDIUM" : "LOW",
      depotCode: order.depotCode,
      outletId: order.outletId,
      subjects: [{ type: "order", id: order.orderId }],
      description: `${order.orderRef}: ${words}`,
      attachmentIds: photos.map((p) => p.id),
    };
    const outcome = await commands.run(IssueCommandKind.raise, raise, null);
    if (!outcome.ok) return setError(friendlyError(outcome.error));
    setSent(outcome.queued ? "queued" : "sent");
  };

  if (sent) {
    return (
      <Modal label="Issue sent" onClose={onSent}>
        <Badge>
          <Icon name={sent === "queued" ? "clock" : "check"} />
        </Badge>
        <div className="flex flex-col items-center gap-1 text-center">
          <h2 className="text-[26px] font-medium text-black">{sent === "queued" ? "Saved on this phone" : "Issue sent"}</h2>
          <p className="text-[14px] text-go-muted">
            {sent === "queued" ? "It goes to the dispatcher when the connection returns." : "The dispatcher has it, and it is in your issues list."}
          </p>
        </div>
        <Button large onClick={onSent}>
          Done
        </Button>
      </Modal>
    );
  }

  const viewed = photos.find((p) => p.id === viewing) ?? null;
  return (
    <Modal label="Report an issue" onClose={onClose}>
      <div className="flex items-start gap-2">
        <div className="flex flex-1 flex-col">
          <h2 className="text-[24px] font-medium text-black">Report an issue</h2>
          <p className="text-[13px] text-go-muted">Orders from the last 48 hours.</p>
        </div>
        <button type="button" aria-label="Close" onClick={onClose} className="flex size-12 items-center justify-center rounded-full bg-go-canvas">
          <Icon name="close" />
        </button>
      </div>

      {orders.length === 0 ? (
        <Notice tone="info" title="No delivery in the last 48 hours to report on." />
      ) : (
        <>
          <Field label="Order" error={orderError ?? undefined} errorId="report-issue-order-error">
            <select
              value={orderId}
              aria-invalid={orderError ? true : undefined}
              aria-describedby={orderError ? "report-issue-order-error" : undefined}
              onChange={(e) => {
                setOrderId(e.target.value);
                setProductId(orders.find((o) => o.orderId === e.target.value)?.lines[0]?.productId ?? "");
                setUnits(1);
                setOrderError(null);
              }}
              className="min-h-14 rounded-[16px] border border-[#dfe7e6] bg-go-canvas px-4 text-[15px] font-medium text-black"
            >
              {orders.map((o) => (
                <option key={o.orderId} value={o.orderId}>
                  {o.orderRef} · {temperatureLabel(o.temperature)} · {dayLabel(o.deliveryDate)} · {unitsText(o.itemCount)}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Product line" error={itemError ?? undefined} errorId="report-issue-item-error">
            <select
              value={productId}
              aria-invalid={itemError ? true : undefined}
              aria-describedby={itemError ? "report-issue-item-error" : undefined}
              onChange={(e) => {
                setProductId(e.target.value);
                setUnits(1);
                setItemError(null);
              }}
              className="min-h-14 rounded-[16px] border border-[#dfe7e6] bg-go-canvas px-4 text-[15px] font-medium text-black"
            >
              {(order?.lines ?? []).map((l) => (
                <option key={l.productId} value={l.productId}>
                  {l.productId} · {unitsText(l.quantity)}
                </option>
              ))}
            </select>
          </Field>
          <p className="text-[13px] text-go-muted">What&rsquo;s wrong?</p>
          <div role="radiogroup" aria-label="What's wrong?" className="flex flex-wrap gap-2">
            {REPORT_KINDS.map((k) => (
              <button
                key={k.label}
                type="button"
                role="radio"
                aria-checked={kind?.label === k.label}
                onClick={() => setKind(k)}
                className={cx("min-h-12 rounded-full px-4 text-[14px]", kind?.label === k.label ? "bg-[#031a0c] text-white" : "bg-go-canvas text-black")}
              >
                {k.label}
              </button>
            ))}
          </div>
          {kind && LOWERS_COUNT[kind.kind] && line && (
            <div className="flex items-center gap-3 text-[14px] text-go-muted">
              How many units?
              <Stepper value={units} label="Units affected" onChange={(n) => setUnits(Math.min(Math.max(1, n), line.quantity))} />
            </div>
          )}
          <label className="flex flex-col gap-1.5 text-[13px] text-go-muted">
            Comment (optional)
            <textarea value={comment} onChange={(e) => setComment(e.target.value)} rows={2} className="rounded-[16px] border border-[#dfe7e6] p-3 text-[15px] text-black" />
          </label>
          <div className="flex flex-wrap items-center gap-2">
            {photos.map((p, i) => (
              <button key={p.id} type="button" aria-label={`Photo ${i + 1}`} onClick={() => setViewing(p.id)} className="size-14 overflow-hidden rounded-[12px]">
                <img src={p.url} alt="" className="size-full object-cover" />
              </button>
            ))}
            <button type="button" onClick={takePhoto} className="min-h-12 rounded-[14px] border border-dashed border-[#b9c6c4] px-4 text-[14px] text-go-muted">
              + Add photo (optional)
            </button>
          </div>
          {error && <Notice tone="danger" live title={error} />}
          <div className="flex gap-2.5">
            <Button tone="plain" large onClick={onClose}>
              Cancel
            </Button>
            <Button tone="danger" large busy={commands.busy} onClick={() => void send()}>
              {commands.busy ? "Sending…" : "Send to dispatcher"}
            </Button>
          </div>
        </>
      )}

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
      {viewed && (
        <PhotoDialog
          url={viewed.url}
          onRetake={() => {
            setPhotos((all) => all.filter((p) => p.id !== viewed.id));
            setViewing(null);
            takePhoto();
          }}
          onClose={() => setViewing(null)}
        />
      )}
    </Modal>
  );
}
