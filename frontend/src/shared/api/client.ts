import { ApiError, parseProblem } from "./problem.ts";

// Every request carries a correlation id so one driver's failed sync can be
// followed from the phone through the command to the database.

export type RequestOptions = {
  method?: string;
  body?: unknown;
  signal?: AbortSignal;
  correlationId?: string;
};

function newCorrelationId(): string {
  return globalThis.crypto?.randomUUID?.() ?? `c-${Date.now()}-${Math.random().toString(16).slice(2)}`;
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
  const payload: unknown = text ? JSON.parse(text) : null;

  if (!response.ok) {
    const problem = parseProblem(response.status, payload);
    // A lockout or backpressure says when to try again; the sign-in screen counts it down.
    const retryAfter = Number(response.headers.get("retry-after"));
    if (retryAfter > 0) problem.extensions.retryAfterSeconds = retryAfter;
    throw new ApiError(problem);
  }
  return payload as T;
}
