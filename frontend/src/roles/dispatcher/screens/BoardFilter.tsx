"use client";

import { Menu, cx, type MenuItem } from "@shared/ui";
import { filterActive, NO_FILTER, type BoardFilter as Filter } from "../data/planViews.ts";

// Figma "Plan · 2c Filter trips": show only Fresh and Chilled, or any mix, to
// check the cold chain, and a search over vehicle, outlet and district. The
// choices are the brands in the plan and the two temperature classes.

const TEMPERATURES: Array<{ id: "chilled" | "ambient"; label: string }> = [
  { id: "chilled", label: "Chilled" },
  { id: "ambient", label: "Ambient" },
];

function toggle<T>(list: T[], value: T): T[] {
  return list.includes(value) ? list.filter((item) => item !== value) : [...list, value];
}

export default function BoardFilter({
  filter,
  brands,
  onChange,
}: {
  filter: Filter;
  brands: string[];
  onChange: (filter: Filter) => void;
}): React.JSX.Element {
  const items: MenuItem[] = [
    ...brands.map((brand) => ({ id: `brand:${brand}`, label: brand, selected: filter.brands.includes(brand) })),
    ...TEMPERATURES.map((t) => ({ id: `temperature:${t.id}`, label: t.label, selected: filter.temperatures.includes(t.id) })),
  ];
  const chosen = filter.brands.length + filter.temperatures.length;

  return (
    <div className="flex flex-wrap items-center gap-2 pb-2">
      <Menu
        label="Filter trips"
        items={items}
        onSelect={(id) => {
          const [kind, value] = id.split(":") as [string, string];
          onChange(
            kind === "brand"
              ? { ...filter, brands: toggle(filter.brands, value) }
              : { ...filter, temperatures: toggle(filter.temperatures, value as "chilled" | "ambient") },
          );
        }}
        className={cx("relative flex items-center gap-1.5 rounded-full border px-3 py-1.5 text-[13px] font-medium before:absolute before:-inset-y-1.5", chosen > 0 ? "border-go-ink bg-go-ink text-go-card" : "border-go-rule bg-go-card text-go-ink")}
      >
        {chosen > 0 ? `Filter · ${chosen}` : "Filter"}
      </Menu>
      <input
        type="search"
        value={filter.text}
        onChange={(event) => onChange({ ...filter, text: event.target.value })}
        placeholder="Search vehicle, outlet..."
        aria-label="Search trips"
        className="w-[200px] rounded-full border border-go-rule bg-go-card px-3 py-1.5 text-[13px] text-go-ink outline-none placeholder:text-go-placeholder"
      />
      {filterActive(filter) && (
        <button type="button" onClick={() => onChange(NO_FILTER)} className="relative text-[13px] font-medium text-go-teal before:absolute before:-inset-x-2 before:-inset-y-3">
          Clear
        </button>
      )}
    </div>
  );
}
