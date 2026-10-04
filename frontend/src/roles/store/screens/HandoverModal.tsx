"use client";

import { useEffect, useRef, useState } from "react";
import { newCommand } from "@shared/api/commands";
import { ApiError } from "@shared/api/problem";
import { useResource } from "@shared/api/useResource";
import { ReceiptCommandKind, type HandoverView } from "@shared/domain/types";
import { Icon, Notice } from "@shared/ui";
import type { StoreGateway } from "../data/gateway.ts";
import { clock, minutesLabel } from "../data/format.ts";
import { conflictMessage } from "../data/useCommands.ts";
import { Badge, Button, Modal } from "../ui.tsx";

// Figma "06b Enter PIN on driver's phone". The store's count is already on
// record; the PIN is evidence that the handover happened here, never a gate
// (R-RCP-09). It is shown once, as Waypoint returned it: only a hash is kept, so
// a lost, expired or locked PIN is replaced by a new one, never shown again.
//
// A new PIN is asked for online only. Kept on the device and sent later, it
// would arrive with nobody looking at it.

export type IssuedPin = { pin: string; expiresAt: string };

export default function HandoverModal({
  gateway,
  orderId,
  issued,
  onConfirmed,
  onClose,
}: {
  gateway: StoreGateway;
  orderId: string;
  issued: IssuedPin;
  onConfirmed: (handover: HandoverView) => void;
  onClose: () => void;
}): React.JSX.Element {
  const [pin, setPin] = useState(issued);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [now, setNow] = useState(() => new Date());
  const handover = useResource((s) => gateway.handover(orderId, s), `${orderId}|${pin.pin}`, 5_000);
  const h = handover.data;

  useEffect(() => {
    const t = window.setInterval(() => setNow(new Date()), 15_000);
    return () => window.clearInterval(t);
  }, []);

  // Told once: the poll keeps answering CONFIRMED until the dialog closes.
  const told = useRef(false);
  useEffect(() => {
    if (h?.status === "CONFIRMED" && !told.current) {
      told.current = true;
      onConfirmed(h);
    }
  }, [h, onConfirmed]);

  const expiresAt = new Date(h?.expiresAt ?? pin.expiresAt);
  const spent = h ? h.status === "EXPIRED" || h.status === "LOCKED" : expiresAt <= now;

  const reissue = async () => {
    if (!h) return;
    setError(null);
    setBusy(true);
    try {
      const ack = await gateway.send(newCommand(ReceiptCommandKind.reissueHandoverPin, { orderId }, h.rowVersion));
      const result = ack.result as { handoverPin: string; handoverExpiresAt: string };
      setPin({ pin: result.handoverPin, expiresAt: result.handoverExpiresAt });
    } catch (failure) {
      // Anything but an answer from Waypoint is the connection: nothing was issued.
      setError(failure instanceof ApiError ? conflictMessage(failure) : "A new PIN needs a connection. Try again when you are back online.");
      handover.refresh();
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal label="Enter this PIN on the driver's phone" onClose={onClose}>
      <Badge>
        <Icon name="lock" />
      </Badge>
      <div className="flex flex-col gap-1">
        <h2 className="text-[26px] leading-tight font-medium text-black">Enter this PIN on the driver&rsquo;s phone</h2>
        <p className="text-[14px] text-go-muted">{spent ? "This PIN no longer works." : "Type this PIN on the driver's phone"}</p>
      </div>
      <p aria-label={`PIN ${pin.pin.split("").join(" ")}`} className="flex justify-center gap-2.5">
        {pin.pin.split("").map((digit, i) => (
          <span
            key={i}
            aria-hidden
            className={`flex h-[60px] w-[52px] items-center justify-center rounded-[14px] border-2 text-[30px] font-semibold ${spent ? "border-[#dfe7e6] text-go-muted line-through" : "border-[#0f766e] bg-go-canvas text-black"}`}
          >
            {digit}
          </span>
        ))}
      </p>
      <ul className="flex flex-col gap-2 rounded-[16px] bg-go-canvas px-4 py-3 text-[14px] text-black">
        <li className="flex items-center gap-2">
          <Icon name="check" />
          One-time PIN for this delivery
        </li>
        {spent ? (
          <li className="flex items-center gap-2 font-medium text-go-warning-text">
            {h?.status === "LOCKED" ? "Locked after five wrong entries." : "Expired."} Ask for a new PIN below.
          </li>
        ) : (
          <li className="flex items-center gap-2">
            <Icon name="clock" />
            Waiting for the driver&rsquo;s phone… · expires in {minutesLabel(expiresAt, now)} ({clock(expiresAt)})
          </li>
        )}
        {h && h.status === "AWAITING" && h.attemptsLeft < 5 && (
          <li className="text-[13px] text-go-warning-text">
            {5 - h.attemptsLeft} wrong {5 - h.attemptsLeft === 1 ? "entry" : "entries"} so far
          </li>
        )}
      </ul>
      {handover.error && !h && <Notice tone="warning" title="Can't check the PIN right now">Your count is recorded. The PIN still works on the driver's phone.</Notice>}
      {error && <Notice tone="danger" live title={error} />}
      <p className="text-[13px] text-go-muted">Your count is already on record. The PIN only shows the driver handed over here; you can close this at any time.</p>
      <div className="flex gap-2.5">
        {spent && (
          <Button large disabled={!h} busy={busy} onClick={() => void reissue()}>
            {busy ? "Issuing…" : "New PIN"}
          </Button>
        )}
        <Button tone="plain" large onClick={onClose}>
          Close
        </Button>
      </div>
      {gateway.sample && !spent && (
        <button
          type="button"
          className="min-h-12 text-[13px] text-go-warning-text underline"
          onClick={() => {
            gateway.confirmHandover?.(orderId);
            handover.refresh();
          }}
        >
          Sample data: the driver enters the PIN
        </button>
      )}
    </Modal>
  );
}
