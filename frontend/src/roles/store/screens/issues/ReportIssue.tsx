"use client";

import { useRef, useState } from "react";
import { IssueCommandKind, type OrderView, type RaiseIssue } from "@shared/domain/types";
import { friendlyError } from "@shared/api/problem";
import { Icon, Notice, cx } from "@shared/ui";
import type { StoreGateway } from "../../data/gateway.ts";
import { dayLabel, temperatureLabel } from "../../data/format.ts";
import { REPORT_KINDS, type ReportKind } from "../../data/issues.ts";
import { useTakenPhotos } from "@shared/media/useTakenPhotos";
import { LOWERS_COUNT, noteOf } from "../../data/receive.ts";
import type { useCommands } from "../../data/useCommands.ts";
import { Badge, Button, Field, Modal, Stepper } from "../../ui.tsx";
import PhotoDialog from "../receive/PhotoDialog.tsx";
import { useT, useCounts } from "../../i18n.tsx";

// "08b Report an issue" and "08c Issue sent": a problem found after unpacking.
// The report names its order and item so the dispatcher can act on it; photos
// go through the same offline queue as the count's, and the issue links them
// whichever reaches the server first.

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
  const { units: unitsText } = useCounts();
  const t = useT();
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
  // FF-01: the shared photo flow (M-02 encoding, blur and darkness check, previews released on close).
  const taken = useTakenPhotos();
  const photos = taken.photos;
  const [viewing, setViewing] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [orderError, setOrderError] = useState<string | null>(null);
  const [itemError, setItemError] = useState<string | null>(null);
  const [sent, setSent] = useState<"sent" | "queued" | null>(null);
  const camera = useRef<HTMLInputElement>(null);

  const takePhoto = () => camera.current?.click();
  const onPhoto = async (file: File | undefined) => {
    // M-02: a blurred or dark photo is pointed out on the phone, before it is sent; it can still be kept.
    const photo = await taken.add(file);
    if (photo) setViewing(photo.id);
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
      if (!kept.durable) return setError(t("A photo could not be kept on this device: {reason}.", { reason: kept.reason ?? t("storage unavailable") }));
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
      <Modal label={t("Issue sent")} onClose={onSent}>
        <Badge>
          <Icon name={sent === "queued" ? "clock" : "check"} />
        </Badge>
        <div className="flex flex-col items-center gap-1 text-center">
          <h2 className="text-[26px] font-medium text-black">{t(sent === "queued" ? "Saved on this phone" : "Issue sent")}</h2>
          <p className="text-[14px] text-go-muted">
            {t(sent === "queued" ? "It goes to the dispatcher when the connection returns." : "The dispatcher has it, and it is in your issues list.")}
          </p>
        </div>
        <Button large onClick={onSent}>
          {t("Done")}
        </Button>
      </Modal>
    );
  }

  const viewed = photos.find((p) => p.id === viewing) ?? null;
  return (
    <Modal label={t("Report an issue")} onClose={onClose}>
      <div className="flex items-start gap-2">
        <div className="flex flex-1 flex-col">
          <h2 className="text-[24px] font-medium text-black">{t("Report an issue")}</h2>
          <p className="text-[13px] text-go-muted">{t("Orders from the last 48 hours.")}</p>
        </div>
        <button type="button" aria-label={t("Close")} onClick={onClose} className="flex size-12 items-center justify-center rounded-full bg-go-canvas">
          <Icon name="close" />
        </button>
      </div>

      {orders.length === 0 ? (
        <Notice tone="info" title={t("No delivery in the last 48 hours to report on.")} />
      ) : (
        <>
          <Field label={t("Order")} error={orderError ?? undefined} errorId="report-issue-order-error">
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
                  {o.orderRef} · {t(temperatureLabel(o.temperature))} · {dayLabel(o.deliveryDate)} · {unitsText(o.itemCount)}
                </option>
              ))}
            </select>
          </Field>
          <Field label={t("Product line")} error={itemError ?? undefined} errorId="report-issue-item-error">
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
          <p className="text-[13px] text-go-muted">{t("What’s wrong?")}</p>
          <div role="radiogroup" aria-label={t("What's wrong?")} className="flex flex-wrap gap-2">
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
              {t("How many units?")}
              <Stepper value={units} label={t("Units affected")} onChange={(n) => setUnits(Math.min(Math.max(1, n), line.quantity))} />
            </div>
          )}
          <label className="flex flex-col gap-1.5 text-[13px] text-go-muted">
            {t("Comment (optional)")}
            <textarea value={comment} onChange={(e) => setComment(e.target.value)} rows={2} className="rounded-[16px] border border-[#dfe7e6] p-3 text-[15px] text-black" />
          </label>
          <div className="flex flex-wrap items-center gap-2">
            {photos.map((p, i) => (
              <button key={p.id} type="button" aria-label={`Photo ${i + 1}`} onClick={() => setViewing(p.id)} className="size-14 overflow-hidden rounded-[12px]">
                <img src={p.url} alt="" loading="lazy" decoding="async" className="size-full object-cover" />
              </button>
            ))}
            <button type="button" onClick={takePhoto} className="min-h-12 rounded-[14px] border border-dashed border-[#b9c6c4] px-4 text-[14px] text-go-muted">
              + Add photo (optional)
            </button>
          </div>
          {(error ?? taken.error) && <Notice tone="danger" live title={error ?? t(taken.error!)} />}
          <div className="flex gap-2.5">
            <Button tone="plain" large onClick={onClose}>
              {t("Cancel")}
            </Button>
            <Button tone="danger" large busy={commands.busy} onClick={() => void send()}>
              {t(commands.busy ? "Sending…" : "Send to dispatcher")}
            </Button>
          </div>
        </>
      )}

      <input
        ref={camera}
        type="file"
        accept="image/*"
        capture="environment"
        aria-label={t("Take a photo of the problem")}
        className="hidden"
        onChange={(e) => {
          void onPhoto(e.target.files?.[0]);
          e.target.value = "";
        }}
      />
      {viewed && (
        <PhotoDialog
          url={viewed.url}
          hint={viewed.hint}
          onRetake={() => {
            taken.remove(viewed.id);
            setViewing(null);
            takePhoto();
          }}
          onClose={() => setViewing(null)}
        />
      )}
    </Modal>
  );
}
