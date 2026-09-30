"use client";

import { Icon, Segmented, cx } from "@shared/ui";
import { VIEWS, type ViewId } from "./navigation.ts";
import type { DepotFilter } from "./data/scope.ts";

// The Figma "Shell / Sidebar": brand, seven destinations, the depot scope and
// the signed-in dispatcher. Badges appear only when a module serves the count;
// a made-up "3" would be worse than none.

export default function Sidebar({
  view,
  onNavigate,
  displayName,
  depots,
  depotFilter,
  onDepotFilter,
}: {
  view: ViewId;
  onNavigate: (view: ViewId) => void;
  displayName: string;
  depots: string[];
  depotFilter: DepotFilter;
  onDepotFilter: (filter: DepotFilter) => void;
}): React.JSX.Element {
  const depotOptions = [
    ...(depots.length > 1 ? [{ value: "all", label: depots.length === 2 ? "Both" : "All" }] : []),
    ...depots.map((depot) => ({ value: depot, label: depot })),
  ];

  return (
    <aside className="flex h-full w-[260px] shrink-0 flex-col gap-1.5 bg-white px-5 pt-7 pb-6">
      <div className="flex items-center gap-2.5 px-2 pb-5">
        <span className="text-[34px] font-extrabold text-go-ink">GO</span>
        <span className="rounded-full bg-go-mint px-2.5 py-1 text-[13px] font-medium text-go-ink">Dispatch</span>
      </div>

      <nav aria-label="Dispatcher" className="flex flex-col gap-1.5">
        {VIEWS.map((item) => {
          const active = item.id === view;
          return (
            <a
              key={item.id}
              href={`#/${item.id}`}
              aria-current={active ? "page" : undefined}
              onClick={(event) => {
                event.preventDefault();
                onNavigate(item.id);
              }}
              className={cx(
                "flex w-full items-center gap-3 rounded-go-card-l px-3.5 py-3 text-base text-go-ink",
                active ? "bg-go-mint font-medium" : "hover:bg-go-subtle",
              )}
            >
              <Icon name={item.icon} />
              <span className="min-w-0 flex-1">{item.label}</span>
            </a>
          );
        })}
      </nav>

      <div className="flex-1" />

      {depotOptions.length > 1 && (
        <div className="mb-3 flex flex-col gap-2 rounded-go-card-s bg-go-surface p-3">
          <p className="text-[11px] font-medium text-go-teal">Showing</p>
          <Segmented label="Depot" options={depotOptions} value={depotFilter} onChange={onDepotFilter} />
        </div>
      )}

      <div className="flex items-center gap-2.5 pt-3.5">
        <span className="flex size-10 shrink-0 items-center justify-center rounded-[20px] bg-go-mint text-sm font-medium text-go-ink">
          {initials(displayName)}
        </span>
        <div className="flex min-w-0 flex-col gap-0.5">
          <p className="truncate text-[15px] font-medium text-go-ink">{displayName}</p>
          <p className="text-xs text-go-secondary">Dispatcher</p>
        </div>
      </div>
    </aside>
  );
}

function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  return ((parts[0]?.[0] ?? "") + (parts.length > 1 ? parts[parts.length - 1]![0] : "")).toUpperCase() || "?";
}
