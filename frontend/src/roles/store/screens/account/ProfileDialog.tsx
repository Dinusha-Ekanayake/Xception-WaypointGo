"use client";

import { useEffect, useState } from "react";
import { useResource } from "@shared/api/useResource";
import { IdentityCommandKind, type UpdateOwnProfile } from "@shared/domain/types";
import { Icon, Notice } from "@shared/ui";
import type { StoreGateway } from "../../data/gateway.ts";
import { conflictMessage, type useCommands } from "../../data/useCommands.ts";
import { Button, Modal } from "../../ui.tsx";

// The manager's own name and phone number (R-IAM-32). The email is the sign-in
// name and the password stays with the administrator, so neither is here.

const field = "min-h-12 rounded-[16px] border border-go-rule bg-go-surface px-4 text-[15px] text-black";

export default function ProfileDialog({
  gateway,
  commands,
  onSaved,
  onClose,
}: {
  gateway: StoreGateway;
  commands: ReturnType<typeof useCommands>;
  /** The name as saved, and whether it is only on this device so far. */
  onSaved: (displayName: string, queued: boolean) => void;
  onClose: () => void;
}): React.JSX.Element {
  const profile = useResource((s) => gateway.profile(s), "profile");
  const [name, setName] = useState("");
  const [phone, setPhone] = useState("");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!profile.data) return;
    setName(profile.data.displayName);
    setPhone(profile.data.phone ?? "");
  }, [profile.data]);

  const save = async () => {
    if (!profile.data) return;
    if (!name.trim()) return setError("A name is required.");
    setError(null);
    const payload: UpdateOwnProfile = { displayName: name, phone: phone.trim() ? phone : null };
    const outcome = await commands.run(IdentityCommandKind.updateOwnProfile, payload, profile.data.rowVersion);
    if (!outcome.ok) return setError(conflictMessage(outcome.error));
    onSaved(name.trim().replace(/\s+/g, " "), outcome.queued);
  };

  return (
    <Modal label="Your profile" onClose={onClose}>
      <div className="flex items-start gap-2">
        <div className="flex flex-1 flex-col">
          <h2 className="text-[24px] font-medium text-black">Your profile</h2>
          <p className="text-[13px] text-go-secondary">How dispatch and drivers see you.</p>
        </div>
        <button type="button" aria-label="Close" onClick={onClose} className="flex size-12 items-center justify-center rounded-full bg-go-surface">
          <Icon name="close" />
        </button>
      </div>
      {profile.error && <Notice tone="danger" title="Could not load your profile">{profile.error.message}</Notice>}
      <label className="flex flex-col gap-1.5 text-[13px] text-go-secondary">
        Name
        <input value={name} onChange={(e) => setName(e.target.value)} maxLength={80} autoComplete="name" className={field} />
      </label>
      <label className="flex flex-col gap-1.5 text-[13px] text-go-secondary">
        Phone number
        <input
          type="tel"
          inputMode="tel"
          autoComplete="tel"
          value={phone}
          onChange={(e) => setPhone(e.target.value)}
          placeholder="+94 77 123 4567"
          className={field}
        />
      </label>
      {profile.data && (
        <p className="text-[13px] text-go-secondary">
          You sign in as {profile.data.email}; an administrator changes that and your password.
        </p>
      )}
      {error && <Notice tone="danger" live title={error} />}
      <div className="flex gap-2.5">
        <Button tone="plain" onClick={onClose}>
          Cancel
        </Button>
        <Button disabled={!profile.data || commands.busy} onClick={() => void save()}>
          {commands.busy ? "Saving…" : "Save"}
        </Button>
      </div>
    </Modal>
  );
}
