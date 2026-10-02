/** Public protocol routes forward only the headers needed by MCP, never cookies. */
export async function mcpProxy(request: Request): Promise<Response> {
  if (request.method !== "POST") return new Response(null, { status: 405, headers: { Allow: "POST" } });
  const headers = new Headers();
  for (const key of ["authorization", "accept", "content-type", "origin", "mcp-protocol-version"]) {
    const value = request.headers.get(key); if (value) headers.set(key, value);
  }
  const reader = request.body?.getReader(); const chunks: Uint8Array[] = []; let size = 0;
  try {
    if (reader) for (;;) {
      const { value, done } = await reader.read(); if (done) break;
      size += value.byteLength;
      if (size > 65536) { void reader.cancel(); return Response.json({ error: "request_too_large" }, { status: 413 }); }
      chunks.push(value);
    }
    return await forward(new URL("/mcp", process.env.MCP_INTERNAL_URL ?? "http://127.0.0.1:8081"), {
      method: "POST", headers, body: Buffer.concat(chunks),
    });
  } catch { return unavailable(); }
  finally { reader?.releaseLock(); }
}
export function oauthMetadata(kind: "protected-resource" | "authorization-server") {
  return () => forward(new URL(`/api/oauth/${kind}`, process.env.BACKEND_URL ?? "http://127.0.0.1:8080"), { method: "GET" });
}
async function forward(url: URL, init: RequestInit): Promise<Response> {
  try {
    const response = await fetch(url, { ...init, cache: "no-store", redirect: "error", signal: AbortSignal.timeout(15000) });
    const headers = new Headers({ "Cache-Control": "no-store" });
    for (const key of ["content-type", "www-authenticate", "allow"]) {
      const value = response.headers.get(key); if (value) headers.set(key, value);
    }
    return new Response(response.body, { status: response.status, headers });
  } catch { return unavailable(); }
}
function unavailable() { return Response.json({ error: "temporarily_unavailable" }, { status: 503, headers: { "Cache-Control": "no-store" } }); }
