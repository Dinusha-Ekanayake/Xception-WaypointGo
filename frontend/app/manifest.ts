import type { MetadataRoute } from "next";
import { headers } from "next/headers";
import { manifestFor } from "@app-shell/appManifest";

// Read at request time: the same build serves every role address, and each
// installs as its own app (issue #201). Reading the Host keeps this one route
// dynamic while the page itself stays static.
export default async function manifest(): Promise<MetadataRoute.Manifest> {
  const host = (await headers()).get("host") ?? "";
  return manifestFor(host.split(":")[0] ?? "");
}
