import type { Project } from "@playwright/test";

// The phones and tablets the field roles are built for (issue #201): small
// and large phones upright, a phone held sideways, and tablets both ways up.
// Each role suite runs its devices.spec.ts on every one of them; the rest of
// the suite stays at the role's own size.

export const DEVICES = [
  { name: "phone-small", viewport: { width: 375, height: 667 } },
  { name: "phone-large", viewport: { width: 430, height: 932 } },
  { name: "phone-landscape", viewport: { width: 852, height: 393 } },
  { name: "tablet-portrait", viewport: { width: 768, height: 1024 } },
  { name: "tablet-portrait-large", viewport: { width: 834, height: 1194 } },
  { name: "tablet-landscape", viewport: { width: 1024, height: 768 } },
  { name: "tablet-landscape-large", viewport: { width: 1180, height: 820 } },
] as const;

/** The role's own size runs everything; each device runs devices.spec.ts only. */
export function deviceProjects(own: { width: number; height: number }): Project[] {
  return [
    { name: "own", use: { viewport: own } },
    ...DEVICES.map((d) => ({ name: d.name, use: { viewport: d.viewport }, testMatch: /devices\.spec\.ts/ })),
  ];
}
