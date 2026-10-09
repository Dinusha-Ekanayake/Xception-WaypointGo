"use client";

import { useCallback, useEffect, useState } from "react";
import type { PhotoStatusView } from "@shared/domain/identity";
import { friendlyError } from "@shared/api/problem";
import { PersonPhoto } from "@shared/ui";
import { dayLabel } from "@shared/wording";
import { Modal, field, primary, secondary } from "./components";
import { PHOTO_TYPES, consentProblem, fetchPhotoStatuses, photoProblem, photoStatusFor, removePhoto, shrink, uploadPhoto } from "../data/profilePhotos";

// Roadmap FF-07: the administrator adds, replaces or removes a person's photo.
// The people who work with them that day see it: their depot, and the store
// their delivery is going to. Consent is recorded with every photo, and a
// removal deletes the image (policy P-PD-1).

export function ProfilePhotoDialog({ userId, name, onClose }: { userId: string; name: string; onClose: () => void }): React.JSX.Element {
  const [status, setStatus] = useState<PhotoStatusView | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [consent, setConsent] = useState("");
  const [removing, setRemoving] = useState(false);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const [shown, setShown] = useState(0);

  const load = useCallback(async (signal?: AbortSignal) => {
    try {
      setStatus(photoStatusFor(await fetchPhotoStatuses(signal), userId));
    } catch (e) {
      if (!signal?.aborted) setError(friendlyError(e));
    }
  }, [userId]);

  useEffect(() => {
    const controller = new AbortController();
    void load(controller.signal);
    return () => controller.abort();
  }, [load]);

  const problem = file ? photoProblem(file) : null;
  const consentIssue = file ? consentProblem(consent) : null;

  const upload = async () => {
    if (!status || !file || problem || consentIssue) return;
    setBusy(true);
    setError(null);
    try {
      await uploadPhoto(userId, await shrink(file), status.version, consent);
      setDone(status.photoId ? "Photo replaced." : "Photo added.");
      setFile(null);
      setConsent("");
      setShown((n) => n + 1);
      await load();
    } catch (e) {
      setError(friendlyError(e));
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    if (!status?.photoId || reason.trim().length < 3) return;
    setBusy(true);
    setError(null);
    try {
      await removePhoto(status, reason);
      setDone("Photo removed. The image is deleted; the reason is kept.");
      setRemoving(false);
      setReason("");
      await load();
    } catch (e) {
      setError(friendlyError(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal title={`Photo · ${name}`} onClose={onClose}>
      <div className="flex flex-col gap-4 text-sm">
        <p className="text-go-secondary">
          Shown only to the people who work with {name} that day: their depot, and the store their delivery is going to. Add it only with their consent.
        </p>

        <div className="flex items-center gap-3 rounded-2xl bg-go-surface px-4 py-3">
          <PersonPhoto key={shown} src={status?.photoId ? `/api/accounts/${encodeURIComponent(userId)}/photo` : null} name={name} size={56} />
          {status === null ? (
            <p className="text-go-secondary">{error ? "Could not read the photo." : "Reading…"}</p>
          ) : status.photoId ? (
            <p>
              <span className="font-semibold">Photo added</span>
              <span className="text-go-secondary">{status.uploadedAt ? ` · ${dayLabel(status.uploadedAt.slice(0, 10))}` : ""}</span>
            </p>
          ) : (
            <p className="font-semibold">No photo. Others see their initials.</p>
          )}
        </div>

        {done && <p role="status" className="rounded-xl bg-go-subtle p-3 text-go-ink">{done}</p>}
        {error && <p role="alert" className="rounded-xl border border-red-200 bg-red-50 p-3 text-red-700">{error}</p>}

        <label className="flex flex-col gap-1.5 font-medium">
          {status?.photoId ? "Replace with a new photo" : "Photo"}
          <input
            type="file"
            accept={PHOTO_TYPES.join(",")}
            className={field}
            onChange={(e) => {
              setDone(null);
              setFile(e.target.files?.[0] ?? null);
            }}
          />
        </label>
        {problem && <p className="text-go-danger">{problem}</p>}
        {file && !problem && (
          <label className="flex flex-col gap-1.5 font-medium">
            How did they give consent?
            <input className={field} value={consent} maxLength={300} placeholder="For example: signed the photo consent form on 9 Oct" onChange={(e) => setConsent(e.target.value)} />
          </label>
        )}

        <div className="flex flex-wrap gap-2">
          <button type="button" className={primary} disabled={!status || !file || !!problem || !!consentIssue || busy} onClick={() => void upload()}>
            {busy && !removing ? "Uploading…" : status?.photoId ? "Replace photo" : "Add photo"}
          </button>
          {status?.photoId && !removing && (
            <button type="button" className={secondary} disabled={busy} onClick={() => setRemoving(true)}>
              Remove
            </button>
          )}
        </div>

        {removing && (
          <div className="flex flex-col gap-2 rounded-2xl border border-go-rule p-4">
            <label className="flex flex-col gap-1.5 font-medium">
              Why is it removed?
              <input className={field} value={reason} maxLength={300} placeholder="For example: they asked for it to be removed" onChange={(e) => setReason(e.target.value)} />
            </label>
            <div className="flex gap-2">
              <button type="button" className={primary} disabled={busy || reason.trim().length < 3} onClick={() => void remove()}>
                {busy ? "Removing…" : "Remove photo"}
              </button>
              <button type="button" className={secondary} onClick={() => setRemoving(false)}>
                Cancel
              </button>
            </div>
          </div>
        )}
      </div>
    </Modal>
  );
}
