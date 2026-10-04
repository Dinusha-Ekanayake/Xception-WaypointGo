import { ApiError, parseProblem } from "@shared/api/problem";
import { allUploads, putUpload, removeUpload, type StoredUpload } from "./store.ts";

// Binary artifacts (proof photos, signatures) on their way to the server.
//
// The same two rules as the command queue: "saved" means durable on this
// device, and a refusal is held for a person, never dropped or resent blindly.
// An artifact is addressed by an id minted here and its content, so sending it
// twice is one artifact on the server.

export const UPLOADS_EVENT = "waypoint:uploads";

export type SaveResult = { durable: true } | { durable: false; reason: string };

export async function saveUpload(
  accountId: string,
  upload: Pick<StoredUpload, "id" | "path" | "subject" | "contentType" | "blob">,
): Promise<SaveResult> {
  try {
    await putUpload(accountId, { ...upload, savedAt: new Date().toISOString(), attempts: 0 });
    globalThis.dispatchEvent?.(new Event(UPLOADS_EVENT));
    return { durable: true };
  } catch (error) {
    return { durable: false, reason: String(error) };
  }
}

export type UploadReport = { sent: number; remaining: number; heldForReview: number };

const running = new Map<string, Promise<UploadReport>>();

/** Sends what it can, once. Concurrent calls for one account share a pass. */
export function drainUploads(accountId: string): Promise<UploadReport> {
  const current = running.get(accountId);
  if (current) return current;
  const pass = drainOnce(accountId).finally(() => running.delete(accountId));
  running.set(accountId, pass);
  return pass;
}

async function drainOnce(accountId: string): Promise<UploadReport> {
  let uploads: StoredUpload[];
  try {
    uploads = await allUploads(accountId);
  } catch {
    return { sent: 0, remaining: 0, heldForReview: 0 };
  }
  let sent = 0;
  for (const upload of uploads) {
    if (upload.needsReview) continue;
    try {
      await send(upload);
      // Only the server's confirmation lets an artifact leave the device.
      await removeUpload(accountId, upload.id);
      sent++;
    } catch (error) {
      const refused = error instanceof ApiError && !error.isRetryable && error.status !== 401;
      await putUpload(accountId, {
        ...upload,
        attempts: upload.attempts + 1,
        lastError: error instanceof ApiError ? error.problem.detail || error.problem.title : String(error),
        ...(refused ? { needsReview: true } : {}),
      });
      // An outage or an expired session stops the pass; the rest wait in order.
      if (!refused) break;
    }
  }
  const left = await allUploads(accountId).catch(() => []);
  return { sent, remaining: left.length, heldForReview: left.filter((u) => u.needsReview).length };
}

async function send(upload: StoredUpload): Promise<void> {
  const response = await fetch(upload.path, {
    method: "PUT",
    headers: { "content-type": upload.contentType, accept: "application/json, application/problem+json" },
    body: upload.blob,
    credentials: "same-origin",
  });
  if (response.ok) return;
  let payload: unknown = null;
  try {
    payload = JSON.parse(await response.text());
  } catch {
    // An error page from a proxy, not the API.
  }
  throw new ApiError(parseProblem(response.status, payload));
}

/** Artifacts still on this device, for the "not yet uploaded" indicator. */
export async function pendingUploads(accountId: string): Promise<StoredUpload[]> {
  try {
    return await allUploads(accountId);
  } catch {
    return [];
  }
}

/** Drop an artifact the server refused. Only a person does this. */
export function discardUpload(accountId: string, id: string): Promise<void> {
  return removeUpload(accountId, id);
}
