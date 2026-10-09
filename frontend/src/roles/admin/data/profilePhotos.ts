import { putFile, request } from "../../../shared/api/client.ts";
import { newCommand, send } from "../../../shared/api/commands.ts";
import type { PhotoStatusView, RemoveProfilePhoto } from "../../../shared/domain/identity.ts";

// Roadmap FF-07: a person's photo, so the people who work with them that day
// recognise them. Uploaded here with the person's consent on record and removed
// on request (policy P-PD-1). The screen shrinks the picture before sending it.

export const PHOTO_TYPES = ["image/jpeg", "image/png", "image/webp"] as const;
export const PHOTO_MAX_BYTES = 256 * 1024;
const EDGE = 384;

export function fetchPhotoStatuses(signal?: AbortSignal) {
  return request<PhotoStatusView[]>("/api/accounts/photos", { signal });
}

/** A person who never had a photo is not in the list: version 0, nothing current. */
export function photoStatusFor(statuses: PhotoStatusView[], userId: string): PhotoStatusView {
  return statuses.find((s) => s.userId === userId) ?? { userId, version: 0, photoId: null, uploadedAt: null, sizeBytes: null, photoRowVersion: null };
}

/** Why a chosen file cannot be a photo, in words; null when it can be used. */
export function photoProblem(file: Pick<File, "type" | "size">): string | null {
  if (!(PHOTO_TYPES as readonly string[]).includes(file.type)) return "Choose a JPEG, PNG or WebP photo.";
  if (file.size === 0) return "This file is empty.";
  return null;
}

/** Whether the consent note can be sent: how the person agreed, in a few words. */
export function consentProblem(note: string): string | null {
  const length = note.trim().length;
  if (length < 3) return "Say how the person gave consent, for example: signed the consent form.";
  if (length > 300) return "Keep the consent note under 300 characters.";
  return null;
}

/** Shrinks the picture to a square-ish avatar and re-encodes it, so it stays well under the limit. */
export async function shrink(file: Blob): Promise<Blob> {
  const bitmap = await createImageBitmap(file);
  const scale = Math.min(1, EDGE / Math.max(bitmap.width, bitmap.height));
  const canvas = document.createElement("canvas");
  canvas.width = Math.round(bitmap.width * scale);
  canvas.height = Math.round(bitmap.height * scale);
  canvas.getContext("2d")?.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  bitmap.close();
  const encode = (type: string) => new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, type, 0.85));
  // A browser that cannot write WebP returns PNG instead; JPEG is then smaller.
  const webp = await encode("image/webp");
  const blob = webp && webp.type === "image/webp" ? webp : await encode("image/jpeg");
  if (!blob) throw new Error("This photo could not be read. Try another one.");
  if (blob.size > PHOTO_MAX_BYTES) throw new Error("This photo is still larger than 256 KB after shrinking. Try another one.");
  return blob;
}

/** Uploads the photo, replacing the current one. A screen that was out of date is refused (version conflict). */
export async function uploadPhoto(userId: string, photo: Blob, expectedVersion: number, consent: string): Promise<void> {
  const query = `expectedVersion=${expectedVersion}&consent=${encodeURIComponent(consent.trim())}`;
  await putFile(`/api/accounts/${encodeURIComponent(userId)}/photo/${globalThis.crypto.randomUUID()}?${query}`, photo);
}

export function removePhoto(status: PhotoStatusView, reason: string) {
  const payload: RemoveProfilePhoto = { userId: status.userId, reason: reason.trim() };
  return send(newCommand("iam:RemoveProfilePhoto", payload, status.photoRowVersion));
}
