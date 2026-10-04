// Liveness of the Next.js process alone, for container health checks. It does
// not call the backend: a frontend restarted because the backend is down would
// only add a second outage to the first.
export const dynamic = "force-dynamic";

export function GET(): Response {
  return Response.json({ status: "UP" }, { headers: { "cache-control": "no-store" } });
}
