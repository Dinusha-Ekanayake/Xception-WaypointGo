"use client";

import { useState } from "react";
import { CountBadge, Icon, Segmented, SettingsPanel, ShellActions, cx, useDeviceLang } from "@shared/ui";
import { VIEWS, type ViewId } from "./navigation.ts";
import type { DepotFilter } from "./data/scope.ts";

// The Figma "Shell / Sidebar": brand, seven destinations, the depot scope and
// the signed-in dispatcher. Badges appear only when a module serves the count;
// a made-up "3" would be worse than none. On the Plan screen the sidebar folds
// to the icon rail Figma draws there, to give the plan the room it needs.

/** Counts the screens serve, for the badge on their destination. */
export type Badges = Partial<Record<ViewId, number>>;

export default function Sidebar({
  view,
  onNavigate,
  displayName,
  depots,
  depotFilter,
  onDepotFilter,
  badges = {},
  rail = false,
}: {
  view: ViewId;
  onNavigate: (view: ViewId) => void;
  displayName: string;
  depots: string[];
  depotFilter: DepotFilter;
  onDepotFilter: (filter: DepotFilter) => void;
  badges?: Badges;
  /** Fold to icons only, as on the Plan screen. */
  rail?: boolean;
}): React.JSX.Element {
  const depotOptions = [
    ...(depots.length > 1 ? [{ value: "all", label: depots.length === 2 ? "Both" : "All" }] : []),
    ...depots.map((depot) => ({ value: depot, label: depot })),
  ];

  return (
    <aside className={cx("hidden h-full shrink-0 flex-col gap-1.5 overflow-y-auto bg-white pt-7 pb-6 lg:flex", rail ? "w-[84px] items-center px-3" : "w-[260px] px-5")}>
      <div className={cx("flex items-center gap-2.5 pb-5", rail ? "justify-center" : "px-2")}>
        <span className={cx("font-extrabold text-go-ink", rail ? "text-[26px]" : "text-[34px]")}>GO</span>
        {!rail && <span className="rounded-full bg-go-mint px-2.5 py-1 text-[13px] font-medium text-go-ink">Dispatch</span>}
      </div>

      <nav aria-label="Dispatcher" className="flex w-full flex-col gap-1.5">
        {VIEWS.map((item) => {
          const active = item.id === view;
          const count = badges[item.id] ?? 0;
          return (
            <a
              key={item.id}
              href={`#/${item.id}`}
              aria-current={active ? "page" : undefined}
              aria-label={rail ? (count > 0 ? `${item.label}, ${count}` : item.label) : undefined}
              title={rail ? item.label : undefined}
              onClick={(event) => {
                event.preventDefault();
                onNavigate(item.id);
              }}
              className={cx(
                "relative flex w-full items-center rounded-go-card-l text-base text-go-ink",
                rail ? "justify-center px-0 py-3" : "gap-3 px-3.5 py-3",
                active ? "bg-go-mint font-medium" : "hover:bg-go-subtle",
              )}
            >
              <Icon name={item.icon} />
              {!rail && <span className="min-w-0 flex-1">{item.label}</span>}
              {!rail && count > 0 && (
                <span className="rounded-full bg-go-danger-tint px-2 py-0.5 text-xs font-medium text-go-danger">{count}</span>
              )}
              {rail && <CountBadge count={count} />}
            </a>
          );
        })}
      </nav>

      <div className="flex-1" />

      {!rail && depotOptions.length > 1 && (
        <div className="mb-3 flex flex-col gap-2 rounded-go-card-s bg-go-surface p-3">
          <p className="text-[11px] font-medium text-go-teal">Showing</p>
          <Segmented label="Depot" options={depotOptions} value={depotFilter} onChange={onDepotFilter} />
        </div>
      )}

      <div className="flex items-center gap-2.5 pt-3.5">
        <ProfileButton displayName={displayName} placement="popover" />
        {!rail && (
          <>
            <div className="flex min-w-0 flex-1 flex-col gap-0.5">
              <p title={displayName} className="text-[15px] leading-tight font-medium text-go-ink [overflow-wrap:anywhere]">{displayName}</p>
              <p className="text-xs text-go-secondary">Dispatcher</p>
            </div>
            <ShellActions compact />
          </>
        )}
      </div>
    </aside>
  );
}

/**
 * Below lg there is no room for the sidebar, and the designs are desktop only,
 * so the same destinations and depot scope sit in a bar across the top.
 */
export function CompactNav({
  view,
  onNavigate,
  depots,
  depotFilter,
  onDepotFilter,
  badges = {},
  displayName,
}: {
  view: ViewId;
  onNavigate: (view: ViewId) => void;
  depots: string[];
  depotFilter: DepotFilter;
  onDepotFilter: (filter: DepotFilter) => void;
  badges?: Badges;
  displayName: string;
}): React.JSX.Element {
  const depotOptions = [
    ...(depots.length > 1 ? [{ value: "all", label: depots.length === 2 ? "Both" : "All" }] : []),
    ...depots.map((depot) => ({ value: depot, label: depot })),
  ];
  return (
    <div className="flex flex-col gap-3 bg-white px-4 pt-4 pb-3 lg:hidden">
      <div className="flex items-center gap-2.5">
        <span className="text-[34px] leading-none font-extrabold text-go-ink">GO</span>
        <span className="rounded-full bg-go-mint px-2.5 py-1 text-[13px] font-medium text-go-ink">Dispatch</span>
        <span className="flex-1" />
        {depotOptions.length > 1 && <Segmented label="Depot" options={depotOptions} value={depotFilter} onChange={onDepotFilter} />}
        <ShellActions compact />
        <ProfileButton displayName={displayName} placement="sheet" />
      </div>
      <nav aria-label="Dispatcher" className="-mx-4 flex gap-1.5 overflow-x-auto px-4">
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
                "flex min-h-11 shrink-0 items-center gap-2 rounded-full px-3.5 text-[15px] text-go-ink",
                active ? "bg-go-mint font-medium" : "bg-go-subtle",
              )}
            >
              <Icon name={item.icon} />
              {item.label}
              {(badges[item.id] ?? 0) > 0 && (
                <span aria-label={`${badges[item.id]} to look at`} className="rounded-full bg-go-danger-tint px-2 py-0.5 text-xs font-medium text-go-danger">
                  {badges[item.id]}
                </span>
              )}
            </a>
          );
        })}
      </nav>
    </div>
  );
}

/** The dispatcher's picture: opens Settings (language and the assistant connection). */
function ProfileButton({ displayName, placement }: { displayName: string; placement: "popover" | "sheet" }): React.JSX.Element {
  const [open, setOpen] = useState(false);
  const [lang, setLang] = useDeviceLang();
  return (
    <>
      <button
        type="button"
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-label={`Settings: ${displayName}`}
        title={displayName}
        onClick={() => setOpen(true)}
        className="flex size-10 shrink-0 items-center justify-center rounded-[20px] bg-go-mint text-sm font-medium text-go-ink"
      >
        {initials(displayName)}
      </button>
      {open && (
        <SettingsPanel
          displayName={displayName}
          roleLabel="Dispatcher"
          lang={lang}
          onLang={setLang}
          translated={false}
          placement={placement}
          onClose={() => setOpen(false)}
        />
      )}
    </>
  );
}

function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  return ((parts[0]?.[0] ?? "") + (parts.length > 1 ? parts[parts.length - 1]![0] : "")).toUpperCase() || "?";
}
