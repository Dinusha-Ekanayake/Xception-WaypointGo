"use client";

import { useState } from "react";
import { MAX_ATTACHMENT_BYTES, shrink } from "../data/image.ts";
import { Banner, Field, input } from "../ui.tsx";
import SignaturePad from "./SignaturePad.tsx";

// Proof of delivery (R-EXE-01): who received it, a signature, a photo. A phone
// that cannot capture never blocks the work (R-EXE-11): the driver says why and
// carries on, and the stop is recorded as lower evidence.

export type ProofDraft = {
  recipientName: string;
  signature: Blob | null;
  photo: Blob | null;
  fallbackReason: string;
};

export const EMPTY_PROOF: ProofDraft = { recipientName: "", signature: null, photo: null, fallbackReason: "" };

/** What the proof still needs before it can be saved, or null. */
export function proofMissing(proof: ProofDraft): string | null {
  if (proof.signature || proof.photo) return null;
  if (proof.fallbackReason.trim()) return null;
  return "Add a signature or a photo, or say why neither could be captured.";
}

export default function ProofCapture({ proof, onChange }: { proof: ProofDraft; onChange: (proof: ProofDraft) => void }): React.JSX.Element {
  const [photoError, setPhotoError] = useState<string | null>(null);
  const [cannot, setCannot] = useState(false);

  const pick = async (file: File | undefined) => {
    if (!file) return;
    setPhotoError(null);
    try {
      const small = await shrink(file);
      if (small.size > MAX_ATTACHMENT_BYTES) {
        // EXE-09: too large even after shrinking. Offer the fallback, never block.
        setPhotoError("The photo is too large to send. Take it again from further back, or record why there is no photo.");
        onChange({ ...proof, photo: null });
        return;
      }
      onChange({ ...proof, photo: small });
    } catch {
      setPhotoError("This phone could not read the photo. Try again, or record why there is no photo.");
      onChange({ ...proof, photo: null });
    }
  };

  return (
    <div className="flex flex-col gap-4">
      <Field label="Received by">
        <input
          className={input}
          value={proof.recipientName}
          maxLength={120}
          autoComplete="off"
          placeholder="Name of the person receiving"
          onChange={(event) => onChange({ ...proof, recipientName: event.target.value })}
        />
      </Field>

      <div className="flex flex-col gap-1.5">
        <span className="text-[15px] font-medium text-go-ink">Signature</span>
        <SignaturePad onChange={(signature) => onChange({ ...proof, signature })} />
      </div>

      <Field label="Photo of the delivery" hint={proof.photo ? `Photo ready · ${Math.round(proof.photo.size / 1024)} KB` : "Optional when there is a signature."}>
        <input
          type="file"
          accept="image/*"
          capture="environment"
          className="block min-h-14 w-full rounded-[14px] border border-go-rule bg-go-subtle px-3 py-3.5 text-[15px] text-go-ink file:mr-3 file:rounded-full file:border-0 file:bg-go-soft file:px-4 file:py-2 file:text-[15px] file:font-medium file:text-go-on-soft"
          onChange={(event) => void pick(event.target.files?.[0])}
        />
      </Field>
      {photoError && <Banner tone="warn" title={photoError} live />}

      <label className="flex min-h-12 items-center gap-3 text-[15px] text-go-ink">
        <input
          type="checkbox"
          className="size-6 accent-[#0e766d]"
          checked={cannot}
          onChange={(event) => {
            setCannot(event.target.checked);
            if (!event.target.checked) onChange({ ...proof, fallbackReason: "" });
          }}
        />
        I can't capture a signature or a photo
      </label>
      {cannot && (
        <Field label="Why not?" hint="The delivery is still recorded, marked as lower evidence.">
          <input
            className={input}
            value={proof.fallbackReason}
            maxLength={300}
            placeholder="For example: camera not working, receiver refused to sign"
            onChange={(event) => onChange({ ...proof, fallbackReason: event.target.value })}
          />
        </Field>
      )}
    </div>
  );
}
