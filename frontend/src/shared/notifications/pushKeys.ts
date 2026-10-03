// The push keys in the encodings each side needs (issue #118): the browser's
// subscription keys to base64url for the backend, and the server's VAPID key
// from base64url to the bytes PushManager.subscribe() takes.

/** base64url, as the backend reads the keys (RFC 4648 section 5, no padding). */
export function base64url(bytes: ArrayBuffer | null): string {
  if (!bytes) return "";
  let binary = "";
  for (const b of new Uint8Array(bytes)) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** The server's VAPID key, from base64url to the bytes subscribe() takes. */
export function keyBytes(key: string): Uint8Array<ArrayBuffer> {
  const padded = key.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - (key.length % 4)) % 4);
  const raw = atob(padded);
  const out = new Uint8Array(new ArrayBuffer(raw.length));
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
  return out;
}
