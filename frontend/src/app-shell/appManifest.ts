import type { MetadataRoute } from "next";
import { roleForHost } from "./hostRole.ts";
import type { ShellRole } from "./session.ts";

// Each role address installs as its own app (issue #201): its own name, icon
// and colour, so a driver's phone shows "Waypoint Driver" and a dock tablet
// "Waypoint Loader". The role addresses are separate origins, so each install
// already has its own worker, queue and device id. The address shared by every
// role installs as the generic app.

/** The icon set a host installs with; also names the files in public/icons/app. */
export type AppIcon = "driver" | "loader" | "store" | "waypoint";

type AppIdentity = { name: string; shortName: string; description: string; icon: AppIcon; theme: string };

const CANVAS = "#e7f3f2";

const GENERIC: AppIdentity = {
  name: "Waypoint Dispatch",
  shortName: "Waypoint",
  description: "One delivery record, from the store order to the store signature.",
  icon: "waypoint",
  theme: "#0e766d",
};

const BY_ROLE: Partial<Record<ShellRole, AppIdentity>> = {
  driver: {
    name: "Waypoint Driver",
    shortName: "Driver",
    description: "Today's run, stop by stop, with proof of delivery. Works without coverage.",
    icon: "driver",
    theme: "#0e766d",
  },
  loader: {
    name: "Waypoint Loader",
    shortName: "Loader",
    description: "Load each truck in stop order and flag what is missing or damaged before it leaves.",
    icon: "loader",
    theme: "#031b08",
  },
  store_manager: {
    name: "Waypoint Store",
    shortName: "Store",
    description: "Order before the cutoff, follow the delivery and confirm what arrived.",
    icon: "store",
    theme: "#0b8a3a",
  },
};

function identityFor(hostname: string): AppIdentity {
  const role = roleForHost(hostname);
  return (role && BY_ROLE[role]) ?? GENERIC;
}

/** The icon set a host installs with; the shared address and other roles get the generic one. */
export function appIconFor(hostname: string): AppIcon {
  return identityFor(hostname).icon;
}

/** The web app manifest for the address being visited. */
export function manifestFor(hostname: string): MetadataRoute.Manifest {
  const app = identityFor(hostname);
  const icon = (file: string) => `/icons/app/${app.icon}-${file}.png`;
  return {
    id: "/",
    name: app.name,
    short_name: app.shortName,
    description: app.description,
    start_url: "/",
    scope: "/",
    display: "standalone",
    background_color: CANVAS,
    theme_color: app.theme,
    icons: [
      { src: icon("192"), sizes: "192x192", type: "image/png", purpose: "any" },
      { src: icon("512"), sizes: "512x512", type: "image/png", purpose: "any" },
      { src: icon("maskable-512"), sizes: "512x512", type: "image/png", purpose: "maskable" },
    ],
  };
}
