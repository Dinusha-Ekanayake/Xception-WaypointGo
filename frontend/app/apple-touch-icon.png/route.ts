import { readFile } from "node:fs/promises";
import path from "node:path";
import { appIconFor } from "@app-shell/appManifest";

// iOS reads the home screen icon from this address rather than from the
// manifest, so it is chosen by Host like the manifest is (issue #201).
export async function GET(request: Request): Promise<Response> {
  const host = (request.headers.get("host") ?? "").split(":")[0] ?? "";
  const file = path.join(process.cwd(), "public", "icons", "app", `${appIconFor(host)}-180.png`);
  return new Response(new Uint8Array(await readFile(file)), {
    headers: { "Content-Type": "image/png", "Cache-Control": "public, max-age=86400", Vary: "Host" },
  });
}
