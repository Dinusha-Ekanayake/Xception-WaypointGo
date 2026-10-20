"use client";

import type { Temperature } from "../domain/common.ts";
import { temperatureLabel } from "../wording/labels.ts";
import { Icon } from "./Icon.tsx";
import { cx } from "./primitives.tsx";

// One look for temperature in every role, with the loader's Figma icons:
// chilled goods are the thermometer, ambient goods the box, and a
// refrigerated vehicle the snowflake. Goods and vehicles are different
// things, so they never share an icon.

type Translate = (text: string) => string;
const plain: Translate = (text) => text;

const SIZE = {
  sm: { box: "gap-1 px-2 py-[3px] text-[11px]", icon: 12 },
  md: { box: "gap-1.5 px-2.5 py-1 text-[13px]", icon: 14 },
} as const;

/** Chilled or ambient goods: an order, a trip's load, a product line. */
export function TemperatureBadge({
  temperature,
  size = "sm",
  tr = plain,
}: {
  temperature: Temperature | string;
  size?: keyof typeof SIZE;
  tr?: Translate;
}): React.JSX.Element {
  const chilled = temperature === "chilled" || temperature === "frozen";
  return (
    <span
      className={cx(
        "inline-flex shrink-0 items-center rounded-full font-medium whitespace-nowrap",
        SIZE[size].box,
        chilled ? "bg-go-info-tint text-go-info" : "bg-go-surface text-go-secondary",
      )}
    >
      <Icon name={chilled ? "chilled" : "box"} size={SIZE[size].icon} />
      {tr(temperatureLabel(chilled ? "chilled" : "ambient"))}
    </span>
  );
}

/** A refrigerated vehicle, which can carry chilled goods (R-PLN-02). */
export function RefrigeratedBadge({
  size = "sm",
  label = "Refrigerated vehicle",
  tr = plain,
}: {
  size?: keyof typeof SIZE;
  label?: string;
  tr?: Translate;
}): React.JSX.Element {
  return (
    <span className={cx("inline-flex shrink-0 items-center rounded-full bg-go-info-tint font-medium whitespace-nowrap text-go-info", SIZE[size].box)}>
      <Icon name="snowflake" size={SIZE[size].icon - 2} />
      {tr(label)}
    </span>
  );
}
