import type { Temperature } from "../domain/common.ts";

// Labels for codes from the data, so a code such as `rear_dock` never reaches a
// screen (docs/architecture/GLOSSARY.md, "Codes shown to people"). The data
// sends these as strings; an unknown one is shown in words built from it rather
// than raw.

export const DOCK_TYPE: Record<string, string> = {
  rear_dock: "Rear dock",
  street: "Street",
  mall_bay: "Mall bay",
};

/** `normal` has no label: nothing is shown for an outlet without a constraint. */
export const PARKING: Record<string, string> = {
  van_only: "Van only",
  mall_dock: "Mall dock",
  normal: "",
};

export const TEMPERATURE = { chilled: "Chilled", ambient: "Ambient" } satisfies Record<Temperature, string>;

export const VEHICLE_TYPE: Record<string, string> = {
  truck: "Truck",
  van: "Van",
};

/** A code shown as words: the label when there is one, else "Rear dock" from "rear_dock". */
export function codeLabel(labels: Record<string, string>, code: string | null | undefined): string {
  if (!code) return "";
  const known = labels[code];
  if (known !== undefined) return known;
  const words = code.replace(/_/g, " ").trim().toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

/** Frozen is treated as chilled (R-PLN-26). */
export function temperatureLabel(temperature: string | null | undefined): string {
  if (temperature === "frozen") return TEMPERATURE.chilled;
  return codeLabel(TEMPERATURE, temperature);
}
