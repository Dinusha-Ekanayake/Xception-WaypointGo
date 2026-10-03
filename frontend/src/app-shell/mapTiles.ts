import { tileAllowed } from "@shared/ui/map/geo";

// The map tile proxy behind app/map-tiles (issue #161, D3). The provider and
// any key stay on the server in MAP_TILE_URL, so the browser's CSP stays
// 'self'. Only Sri Lanka at zoom 5-17 is served, so it is never an open tile
// relay. With no MAP_TILE_URL the answer is 404 and the map says "Base map
// unavailable".

const USER_AGENT = "WaypointDispatch/1.0 (+live map tile proxy)";

export async function serveTile(z: string, x: string, y: string, tileUrl = process.env.MAP_TILE_URL ?? ""): Promise<Response> {
  const [zn, xn, yn] = [z, x, y.replace(/\.png$/, "")].map((v) => (/^\d+$/.test(v) ? Number(v) : NaN));
  if (!tileUrl || !tileAllowed(zn!, xn!, yn!)) return new Response(null, { status: 404 });
  const url = tileUrl.replace("{z}", String(zn)).replace("{x}", String(xn)).replace("{y}", String(yn));
  try {
    const upstream = await fetch(url, { headers: { "user-agent": USER_AGENT }, signal: AbortSignal.timeout(8000) });
    const type = upstream.headers.get("content-type") ?? "";
    if (!upstream.ok || !type.startsWith("image/")) return new Response(null, { status: 502 });
    return new Response(upstream.body, {
      headers: { "content-type": type, "cache-control": "public, max-age=604800, immutable", "x-content-type-options": "nosniff" },
    });
  } catch {
    return new Response(null, { status: 504 });
  }
}
