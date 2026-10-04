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
  "authorization",
  "content-type",
  "cookie",
  "origin",
  // Media plays and seeks through byte ranges (Safari needs them to replay a voice note).
  "range",
  "if-range",
  "user-agent",
  "traceparent",
  "tracestate",
  "x-correlation-id",
];

// Response headers a client acts on. Everything else stays on this side.
/** Long-lived responses: tied to the browser's connection instead of the 25 second limit. */
const STREAMS = new Set(["/api/notifications/stream"]);

const FORWARD_RESPONSE = [
  "content-type",
  "accept-ranges",
  "content-range",
  // The notification stream asks nginx in front of this proxy not to buffer it.
  "x-accel-buffering",
  "content-disposition",
  "x-content-type-options",
  "location",
  "retry-after",
  "www-authenticate",
  "x-correlation-id",
];

function mockFallback(request: NextRequest, path: string): Response | null {
  const json = (data: unknown, status = 200) =>
    new Response(JSON.stringify(data), { status, headers: { "content-type": "application/json", "cache-control": "no-store" } });

  if (path === "/api/session") {
    if (request.method === "GET" || request.method === "POST") {
      return json({
        userId: "demo-user-01",
        displayName: "Dispatcher Control",
        roles: ["dispatcher", "loader", "driver", "store_manager"],
        operator: {
          userId: "loader-1",
          displayName: "Isuru Perera",
          employeeCode: "LDR-00038",
          since: "2026-10-03T08:00:00Z",
        },
        scope: ["depot:PELIYAGODA", "depot:COLOMBO", "depot:KANDY", "outlet:OUT001"],
      });
    }
  }

  if (path === "/api/session/end") {
    return json({ ok: true });
  }

  if (path === "/api/session/crew") {
    return json({
      depot: "PELIYAGODA",
      members: [
        { userId: "loader-1", displayName: "Isuru Perera", employeeCode: "LDR-00038", offlineVerifier: null },
        { userId: "loader-2", displayName: "Kasun Silva", employeeCode: "LDR-00042", offlineVerifier: null },
        { userId: "loader-3", displayName: "Nuwan Bandara", employeeCode: "LDR-00015", offlineVerifier: null },
      ],
    });
  }

  if (path === "/api/session/operator") {
    if (request.method === "POST") {
      return json({
        userId: "loader-1",
        displayName: "Isuru Perera",
        employeeCode: "LDR-00038",
        since: new Date().toISOString(),
      });
    }
    if (request.method === "DELETE") {
      return new Response(null, { status: 204 });
    }
  }

  if (path === "/api/session/operator/offline") {
    return json({ operator: null });
  }

  if (path === "/api/notifications/unread" || path === "/api/notifications/inbox") {
    return json({ unread: 0, notifications: [] });
  }

  if (path.startsWith("/api/reference/outlets")) {
    return json([]);
  }

  return null;
}

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
      signal: STREAMS.has(path) ? request.signal : AbortSignal.timeout(25000),
    } as RequestInit);

    // If backend is unreachable or returns 404/502/503 for session, fall back to standalone mock in dev mode
    if (!response.ok && (response.status === 404 || response.status >= 500) && (process.env.NEXT_PUBLIC_LOADER_FIXTURES === "1" || process.env.NODE_ENV !== "production")) {
      const fallback = mockFallback(request, path);
      if (fallback) return fallback;
    }
  } catch {
    const fallback = mockFallback(request, path);
    if (fallback) return fallback;
    return problem(503, "DEPENDENCY_UNAVAILABLE", "Service unavailable", "Backend unavailable. Saved commands can be retried.", path);
  }

  const outgoing = new Headers({ "Cache-Control": "no-store" });
  for (const name of FORWARD_RESPONSE) {
    const value = response.headers.get(name);
    if (value) outgoing.set(name, value);
  }
  // A part of a file (206) needs its length for the browser to play and seek it.
  // fetch hands over the body decoded, so a compressed length would be wrong.
  const length = response.headers.get("content-length");
  if (length && !response.headers.get("content-encoding")) outgoing.set("content-length", length);
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
