"use client";

import { useState } from "react";
import type { StoreContactView } from "@shared/domain/types";
import { request } from "@shared/api/client";
import { useResource } from "@shared/api/useResource";
import { PrimaryButton, SecondaryButton } from "@shared/ui";
import type { PlanActions } from "./planActions.ts";

// R-IAM-41: who to call about a deferred order. The outlet's store managers
// with the phone each keeps on their own profile, as text (no Call button,
// owner decision 2026-10-21), and a message to the store through the plan
// (plan:ContactStore), which the store manager reads in their inbox.

const MIN = 3;

export default function StoreContactCard({
  orderId,
  outletId,
  canMessage,
  actions,
}: {
  orderId: string;
  outletId: string;
  /** The plan is editable, so the message is sent against it. */
  canMessage: boolean;
  actions: PlanActions;
}): React.JSX.Element {
  const contacts = useResource<StoreContactView[]>(
    (signal) => request<StoreContactView[]>(`/api/outlets/${encodeURIComponent(outletId)}/contacts`, { signal }),
    `store-contacts|${outletId}`,
  );
  const [writing, setWriting] = useState(false);
  const [message, setMessage] = useState("");
  const [sent, setSent] = useState(false);

  return (
    <div className="flex flex-col gap-2 rounded-go-card border border-go-rule px-3.5 py-3">
      <h3 className="text-[15px] font-medium text-go-ink">{`Store manager · ${outletId}`}</h3>
      {contacts.error && <p className="text-[13px] text-go-secondary">The store's contact could not be read.</p>}
      {!contacts.error && !contacts.data && <p className="text-[13px] text-go-secondary">Reading the contact…</p>}
      {contacts.data && contacts.data.length === 0 && <p className="text-[13px] text-go-secondary">No store manager holds this outlet.</p>}
      {contacts.data?.map((c) => (
        <p key={`${c.name}|${c.phone ?? ""}`} className="min-w-0 text-[14px]">
          <span className="block font-medium text-go-ink">{c.name}</span>
          <span className="block text-[13px] text-go-secondary">{c.phone ?? "No phone on their profile"}</span>
        </p>
      ))}
      {canMessage && !writing && (
        <SecondaryButton onClick={() => { setWriting(true); setSent(false); }}>{sent ? "Message sent · write another" : "Message the store"}</SecondaryButton>
      )}
      {writing && (
        <div className="flex flex-col gap-2">
          <label className="flex flex-col gap-1 text-[13px] font-medium text-go-ink">
            Message to the store manager
            <textarea
              value={message}
              maxLength={500}
              rows={3}
              onChange={(event) => setMessage(event.target.value)}
              className="rounded-go-input border border-go-rule bg-white px-3 py-2.5 text-[14px] font-normal outline-none focus:border-go-teal"
            />
          </label>
          <div className="flex gap-2">
            <PrimaryButton
              disabled={actions.busy || message.trim().length < MIN}
              onClick={() =>
                void actions.contactStore(orderId, message.trim()).then((ok) => {
                  if (!ok) return;
                  setSent(true);
                  setWriting(false);
                  setMessage("");
                })
              }
            >
              Send message
            </PrimaryButton>
            <SecondaryButton onClick={() => setWriting(false)}>Cancel</SecondaryButton>
          </div>
        </div>
      )}
    </div>
  );
}
