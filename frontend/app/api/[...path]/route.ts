import { NextRequest } from "next/server";

export const dynamic = "force-dynamic";

async function proxy(request: NextRequest): Promise<Response> {
  const target = new URL(request.nextUrl.pathname + request.nextUrl.search,
    process.env.BACKEND_URL || "http://127.0.0.1:8080");
  const headers = new Headers();
  for (const name of ["cookie", "content-type", "origin"]) {
    const value = request.headers.get(name);
    if (value) headers.set(name, value);
  }
  // Node fetch (undici) forbids overriding `Host`, so the backend would see
  // BACKEND_URL's host instead of the browser's. Forward it separately so
  // the backend origin guard can compare against the real page host.
  const browserHost = request.headers.get("host") || request.nextUrl.host;
  headers.set("Host", browserHost);
  headers.set("X-Forwarded-Host", browserHost);
  try {
    const response = await fetch(target, {
      method: request.method, headers, cache: "no-store", redirect: "manual",
      ...(request.method === "POST" ? { body: request.body, duplex: "half" } : {}),
      signal: AbortSignal.timeout(25000),
    } as RequestInit);
    const outgoing = new Headers({ "Cache-Control": "no-store" });
    for (const name of ["content-type", "set-cookie"]) {
      const value = response.headers.get(name);
      if (value) outgoing.set(name, value);
    }
    return new Response(response.body, { status: response.status, headers: outgoing });
  } catch {
    return Response.json({ error: "Backend unavailable. Saved commands can be retried." }, { status: 503 });
  }
}
export const GET = proxy;
export const POST = proxy;
