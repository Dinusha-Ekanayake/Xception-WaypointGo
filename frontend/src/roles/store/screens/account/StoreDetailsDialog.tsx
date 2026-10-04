"use client";

import { useEffect, useState } from "react";
import { useResource } from "@shared/api/useResource";
import { OutletCommandKind, type OutletView, type UpdateOutletDetails } from "@shared/domain/types";
import { Icon, Notice } from "@shared/ui";
import type { StoreGateway } from "../../data/gateway.ts";
import { dockLabel, hhmm } from "../../data/format.ts";
import { conflictMessage, type useCommands } from "../../data/useCommands.ts";
import { Button, Modal } from "../../ui.tsx";

// What the store says about itself (R-REF-01): its delivery window and dock,
// which the next plan uses, and its contacts for dispatch and drivers. A mall bay
// belongs to the building, so a mall store keeps it, and its window must still
// overlap the mall's own hours (R-PLN-29). The server checks both; this says so first.

const field = "min-h-12 rounded-[16px] border border-go-rule bg-go-surface px-4 text-[15px] text-black";

export default function StoreDetailsDialog({
  gateway,
  outlet,
  commands,
  onSaved,
  onClose,
}: {
  gateway: StoreGateway;
  outlet: OutletView;
  commands: ReturnType<typeof useCommands>;
  /** Saved; whether it is only on this device so far. */
  onSaved: (queued: boolean) => void;
  onClose: () => void;
}): React.JSX.Element {
  const details = useResource((s) => gateway.outletDetails(outlet.outletId, s), outlet.outletId);
  const mall = outlet.dockType === "mall_bay";
  const [open, setOpen] = useState(hhmm(outlet.windowOpen));
  const [close, setClose] = useState(hhmm(outlet.windowClose));
  const [dock, setDock] = useState(outlet.dockType);
  const [contactName, setContactName] = useState("");
  const [contactPhone, setContactPhone] = useState("");
  const [notes, setNotes] = useState("");
  const [windowError, setWindowError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!details.data) return;
    setContactName(details.data.contactName ?? "");
    setContactPhone(details.data.contactPhone ?? "");
    setNotes(details.data.receivingNotes ?? "");
  }, [details.data]);

  const changedFromDepot = details.data !== null && (details.data.windowOpen !== null || details.data.dockType !== null);

  const send = async (useDepots: boolean) => {
    if (!details.data) return;
    setWindowError(null);
    if (!useDepots && (!open || !close)) return setWindowError("Give both ends of the delivery window.");
    if (!useDepots && open >= close) return setWindowError("The window has to open before it closes.");
    setError(null);
    const payload: UpdateOutletDetails = {
      outletId: outlet.outletId,
      windowOpen: useDepots ? null : open,
      windowClose: useDepots ? null : close,
      dockType: useDepots || mall ? null : dock,
      contactName: contactName.trim() || null,
      contactPhone: contactPhone.trim() || null,
      receivingNotes: notes.trim() || null,
    };
    const outcome = await commands.run(OutletCommandKind.updateDetails, payload, details.data.rowVersion);
    if (!outcome.ok) return setError(conflictMessage(outcome.error));
    onSaved(outcome.queued);
  };

  return (
    <Modal label="Store details" onClose={onClose}>
      <div className="flex items-start gap-2">
        <div className="flex flex-1 flex-col">
          <h2 className="text-[24px] font-medium text-black">Store details</h2>
          <p className="text-[13px] text-go-secondary">
            {outlet.districtName} · {outlet.outletId}
          </p>
        </div>
        <button type="button" aria-label="Close" onClick={onClose} className="flex size-12 items-center justify-center rounded-full bg-go-surface">
          <Icon name="close" />
        </button>
      </div>
      {details.error && <Notice tone="danger" title="Could not load the store's details">{details.error.message}</Notice>}

      <fieldset className="flex flex-col gap-2">
        <legend className="mb-1.5 text-[13px] text-go-secondary">Delivery window</legend>
        <div className="flex items-center gap-2">
          <label className="flex flex-1 flex-col gap-1.5 text-[12px] text-go-secondary">
            Opens at
            <input
              type="time"
              value={open}
              onChange={(e) => {
                setOpen(e.target.value);
                setWindowError(null);
              }}
              aria-invalid={windowError ? true : undefined}
              aria-describedby={windowError ? "store-window-error" : undefined}
              className={field}
            />
          </label>
          <span className="pt-5 text-go-secondary">to</span>
          <label className="flex flex-1 flex-col gap-1.5 text-[12px] text-go-secondary">
            Closes at
            <input
              type="time"
              value={close}
              onChange={(e) => {
                setClose(e.target.value);
                setWindowError(null);
              }}
              aria-invalid={windowError ? true : undefined}
              aria-describedby={windowError ? "store-window-error" : undefined}
              className={field}
            />
          </label>
        </div>
        {mall && <p className="text-[12px] text-go-secondary">The mall lets vehicles in only at its own hours; the window must overlap them.</p>}
        {windowError && (
          <span id="store-window-error" role="alert" className="text-[13px] text-go-danger-strong">
            {windowError}
          </span>
        )}
      </fieldset>

      <label className="flex flex-col gap-1.5 text-[13px] text-go-secondary">
        Where goods are received
        <select value={dock} disabled={mall} onChange={(e) => setDock(e.target.value)} className={field}>
          {mall ? (
            <option value="mall_bay">{dockLabel("mall_bay")}</option>
          ) : (
            ["rear_dock", "street"].map((d) => (
              <option key={d} value={d}>
                {dockLabel(d)}
              </option>
            ))
          )}
        </select>
        {mall && <span className="text-[12px]">A mall bay belongs to the building, so it cannot be changed here.</span>}
      </label>

      <div className="grid gap-2 sm:grid-cols-2">
        <label className="flex flex-col gap-1.5 text-[13px] text-go-secondary">
          Contact person
          <input name="contactName" value={contactName} onChange={(e) => setContactName(e.target.value)} maxLength={80} className={field} />
        </label>
        <label className="flex flex-col gap-1.5 text-[13px] text-go-secondary">
          Store phone
          <input
            type="tel"
            inputMode="tel"
            autoComplete="tel"
            value={contactPhone}
            onChange={(e) => setContactPhone(e.target.value)}
            placeholder="+94 81 234 5678"
            className={field}
          />
        </label>
      </div>
      <label className="flex flex-col gap-1.5 text-[13px] text-go-secondary">
        Notes for the driver (optional)
        <textarea value={notes} onChange={(e) => setNotes(e.target.value)} maxLength={300} rows={2} className="rounded-[16px] border border-go-rule bg-go-surface p-3 text-[15px] text-black" />
      </label>

      <p className="rounded-[14px] bg-go-canvas px-4 py-3 text-[13px] text-go-ink">
        A new window or dock is used from the next plan. A plan already published does not change.
      </p>
      {error && <Notice tone="danger" live title={error} />}
      {changedFromDepot && (
        <button type="button" disabled={commands.busy} onClick={() => void send(true)} className="min-h-12 self-start text-[14px] font-medium text-go-teal disabled:cursor-wait disabled:opacity-60">
          {commands.busy ? "Saving…" : "Use the depot’s window and dock again"}
        </button>
      )}
      <div className="flex gap-2.5">
        <Button tone="plain" onClick={onClose}>
          Cancel
        </Button>
        <Button disabled={!details.data} busy={commands.busy} onClick={() => void send(false)}>
          {commands.busy ? "Saving…" : "Save"}
        </Button>
      </div>
    </Modal>
  );
}
