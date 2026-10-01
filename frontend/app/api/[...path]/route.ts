import { NextRequest } from "next/server";

export const dynamic = "force-dynamic";

const HAS_BODY = new Set(["POST", "PUT", "PATCH"]);

// The backend enforces the same limit (RequestSizeFilter); refusing here too
// means an oversized upload is not streamed across the network first.
const MAX_BODY_BYTES = Number(process.env.MAX_BODY_BYTES) || 4 * 1024 * 1024;

// Request headers the backend needs. Tracing headers keep one trace across the
// proxy hop; the correlation id lets a support ticket be followed into the logs.
const FORWARD_REQUEST = [
  "accept",
  "content-type",
  "cookie",
  "origin",
  "user-agent",
  "traceparent",
  "tracestate",
  "x-correlation-id",
];

// Response headers a client acts on. Everything else stays on this side.
const FORWARD_RESPONSE = [
  "content-type",
  "location",
  "retry-after",
  "www-authenticate",
  "x-correlation-id",
];

function problem(status: number, code: string, title: string, detail: string, instance: string): Response {
  return new Response(
    JSON.stringify({ type: `urn:waypoint:problem:${code.toLowerCase().replaceAll("_", "-")}`, title, status, detail, instance, code, correlationId: "", violations: [] }),
    { status, headers: { "content-type": "application/problem+json", "cache-control": "no-store" } },
  );
}

async function proxy(request: NextRequest): Promise<Response> {
  const path = request.nextUrl.pathname;
  const declared = Number(request.headers.get("content-length") ?? "0");
  if (declared > MAX_BODY_BYTES) {
    return problem(413, "PAYLOAD_TOO_LARGE", "Request too large", `The request body exceeds ${MAX_BODY_BYTES} bytes`, path);
  }

  const target = new URL(path + request.nextUrl.search, process.env.BACKEND_URL || "http://127.0.0.1:8080");
  const headers = new Headers();
  for (const name of FORWARD_REQUEST) {
    const value = request.headers.get(name);
    if (value) headers.set(name, value);
  }
  // Node fetch (undici) forbids overriding `Host`, so the backend would see
  // BACKEND_URL's host instead of the browser's. Forward it separately: the
  // backend's OriginGuardFilter compares `Origin` with X-Forwarded-Host and
  // refuses a state-changing request that came from another site.
  const browserHost = request.headers.get("host") || request.nextUrl.host;
  headers.set("Host", browserHost);
  headers.set("X-Forwarded-Host", browserHost);
  headers.set("X-Forwarded-Proto", request.nextUrl.protocol.replace(":", ""));
  // The client address as whoever is in front of Next saw it. The sign-in
  // throttle and the audit log record it, so it must not collapse to this proxy.
  const forwardedFor = request.headers.get("x-forwarded-for") ?? request.headers.get("x-real-ip");
  if (forwardedFor) headers.set("X-Forwarded-For", forwardedFor);

  let response: Response;
  try {
    response = await fetch(target, {
      method: request.method, headers, cache: "no-store", redirect: "manual",
      ...(HAS_BODY.has(request.method) ? { body: request.body, duplex: "half" } : {}),
      signal: AbortSignal.timeout(25000),
    } as RequestInit);
  } catch {
    // Same contract as every backend error, so the client's retry logic sees a
    // problem it understands rather than a shape only this proxy produces.
    return problem(503, "DEPENDENCY_UNAVAILABLE", "Service unavailable", "Backend unavailable. Saved commands can be retried.", path);
  }

  const outgoing = new Headers({ "Cache-Control": "no-store" });
  for (const name of FORWARD_RESPONSE) {
    const value = response.headers.get(name);
    if (value) outgoing.set(name, value);
  }
  // get("set-cookie") joins several cookies into one comma-separated header,
  // which the browser then reads as one broken cookie. Forward each one.
  for (const cookie of response.headers.getSetCookie()) outgoing.append("set-cookie", cookie);
  return new Response(response.body, { status: response.status, headers: outgoing });
}
// Every write is a POST to /api/commands today. The other methods are forwarded
// so that what the browser can reach and what curl can reach against the backend
// never drift apart.
export const GET = proxy;
export const POST = proxy;
export const PUT = proxy;
export const PATCH = proxy;
export const DELETE = proxy;
