import { ApiError, parseProblem } from "./problem.ts";

// Every request carries a correlation id so one driver's failed sync can be
// followed from the phone through the command to the database.

export type RequestOptions = {
  method?: string;
  body?: unknown;
  signal?: AbortSignal;
  correlationId?: string;
};

// UUID-shaped, because the backend replaces anything else with an id of its own,
// and then the id the client logged would not match the one the server did.
// randomUUID is missing outside a secure context, such as plain http on a LAN.
function newCorrelationId(): string {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
  const bytes = new Uint8Array(16);
  globalThis.crypto.getRandomValues(bytes);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function parseBody(text: string): unknown {
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    // An HTML error page from nginx or a gateway, not the API. Treated as a
    // problem with no body, so the caller still gets an ApiError it can branch on.
    return null;
  }
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = {
    accept: "application/json, application/problem+json",
    "x-correlation-id": options.correlationId ?? newCorrelationId(),
  };
  if (options.body !== undefined) headers["content-type"] = "application/json";

  const response = await fetch(path, {
    method: options.method ?? "GET",
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    credentials: "same-origin",
    signal: options.signal,
  });

  const text = await response.text();
  const payload = parseBody(text);

  if (!response.ok) throw new ApiError(parseProblem(response.status, payload));
  return payload as T;
}
