import type { Page } from "../domain/common.ts";
import { ApiError, parseProblem } from "./problem.ts";

// Every request carries a correlation id so one driver's failed sync can be
// followed from the phone through the command to the database.

export type RequestOptions = {
  method?: string;
  body?: unknown;
  signal?: AbortSignal;
  correlationId?: string;
  /** Give up after this long; 0 waits as long as the browser does. Defaults to READ_TIMEOUT_MS or WRITE_TIMEOUT_MS. */
  timeoutMs?: number;
};

// A line that is connected but carries nothing (a weak signal in the hills) can
// keep a request open for minutes, and the kept answer only appears once the
// browser gives up. A timeout ends the wait as a TimeoutError, which kept reads
// treat as an outage; a caller's own cancel stays an AbortError, which they do
// not (roadmap F-02).
export const READ_TIMEOUT_MS = 8_000;
export const WRITE_TIMEOUT_MS = 15_000;

function timeoutError(): DOMException {
  return new DOMException("Waypoint did not answer in time", "TimeoutError");
}

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

  const method = options.method ?? "GET";
  const timeoutMs = options.timeoutMs ?? (method === "GET" ? READ_TIMEOUT_MS : WRITE_TIMEOUT_MS);
  // One controller for both the caller's cancel and the timeout, written out
  // rather than AbortSignal.any, which older iPhones lack.
  const controller = new AbortController();
  let timedOut = false;
  const onCallerAbort = () => controller.abort(options.signal?.reason);
  if (options.signal?.aborted) controller.abort(options.signal.reason);
  else options.signal?.addEventListener("abort", onCallerAbort, { once: true });
  const timer =
    timeoutMs > 0
      ? setTimeout(() => {
          timedOut = true;
          controller.abort(timeoutError());
        }, timeoutMs)
      : undefined;

  let response: Response;
  let text: string;
  try {
    response = await fetch(path, {
      method,
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      credentials: "same-origin",
      signal: controller.signal,
    });
    text = await response.text();
  } catch (error) {
    // Browsers differ in what an abort with a reason rejects with; the flag
    // decides, so a timeout is always a TimeoutError.
    if (timedOut) throw timeoutError();
    throw error;
  } finally {
    if (timer !== undefined) clearTimeout(timer);
    options.signal?.removeEventListener("abort", onCallerAbort);
  }
  const payload = parseBody(text);

  if (!response.ok) {
    const problem = parseProblem(response.status, payload);
    // A lockout or backpressure says when to try again; the sign-in screen counts it down.
    const retryAfter = Number(response.headers.get("retry-after"));
    if (retryAfter > 0) problem.extensions.retryAfterSeconds = retryAfter;
    throw new ApiError(problem);
  }
  return payload as T;
}

/**
 * Sends a file as the request body (PUT), for an endpoint that takes an image
 * rather than JSON. Refusals come back as the same ApiError as any request.
 */
export async function putFile<T>(path: string, file: Blob, options: Pick<RequestOptions, "signal" | "timeoutMs"> = {}): Promise<T> {
  const controller = new AbortController();
  const onCallerAbort = () => controller.abort(options.signal?.reason);
  options.signal?.addEventListener("abort", onCallerAbort, { once: true });
  let timedOut = false;
  const timeoutMs = options.timeoutMs ?? WRITE_TIMEOUT_MS;
  const timer = timeoutMs > 0 ? setTimeout(() => ((timedOut = true), controller.abort(timeoutError())), timeoutMs) : undefined;
  let response: Response;
  let text: string;
  try {
    response = await fetch(path, {
      method: "PUT",
      headers: {
        accept: "application/json, application/problem+json",
        "content-type": file.type || "application/octet-stream",
        "x-correlation-id": newCorrelationId(),
      },
      body: file,
      credentials: "same-origin",
      signal: controller.signal,
    });
    text = await response.text();
  } catch (error) {
    if (timedOut) throw timeoutError();
    throw error;
  } finally {
    if (timer !== undefined) clearTimeout(timer);
    options.signal?.removeEventListener("abort", onCallerAbort);
  }
  const payload = parseBody(text);
  if (!response.ok) throw new ApiError(parseProblem(response.status, payload));
  return payload as T;
}

/**
 * Every item of a keyset-paginated list, following `nextCursor` until it is null.
 * For lists bounded by the domain, such as one depot's outlets or fleet; an
 * unbounded list is paged on screen instead. `cursorParam` is the query
 * parameter the endpoint reads the cursor from.
 */
export async function requestAll<T>(path: string, options: RequestOptions = {}, cursorParam = "after"): Promise<T[]> {
  const items: T[] = [];
  const separator = path.includes("?") ? "&" : "?";
  let cursor: string | null = null;
  do {
    const query: string = cursor === null ? "" : `${separator}${cursorParam}=${encodeURIComponent(cursor)}`;
    const page: Page<T> = await request<Page<T>>(`${path}${query}`, options);
    items.push(...page.items);
    cursor = page.nextCursor;
  } while (cursor !== null);
  return items;
}
