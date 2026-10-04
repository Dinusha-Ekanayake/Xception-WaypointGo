"use client";

import { useEffect, useState } from "react";
import ProfileButton from "./ProfileButton.tsx";
import { CountBadge, Icon, Popover, Segmented, ShellActions, cx } from "@shared/ui";
import { VIEWS, type ViewId } from "./navigation.ts";
import type { DepotFilter } from "./data/scope.ts";

// The Figma "Shell / Sidebar": brand, seven destinations, the depot scope and
// the signed-in dispatcher. One sidebar on every screen: the dispatcher folds
// it to the icon rail Figma draws on Plan and Live with the button above the
// avatar, and the choice is kept, so the page never jumps between screens. The
// rail keeps everything the full bar has: badges become dots, the depot scope
// opens as a card, and the picture opens Settings (as in every role) with the
// account and Sign out, as Figma's "Account menu" draws them. Badges appear only when a module serves the
// count; a made-up "3" would be worse than none.

/** A count in red, or a short word in amber (Plan: "2 to decide"). */
export type Badge = number | { text: string };

/** What the screens serve, for the badge on their destination. */
export type Badges = Partial<Record<ViewId, Badge>>;

const FOLDED_KEY = "wp.dispatcher.sidebar.folded";

/** Whether the sidebar is folded to the rail, remembered on this device only. */
export function useFolded(): [boolean, (folded: boolean) => void] {
  const [folded, setFolded] = useState(false);
  useEffect(() => {
    try {
      setFolded(window.localStorage.getItem(FOLDED_KEY) === "1");
    } catch {
      // Storage refused (private window): the sidebar starts open.
    }
  }, []);
  const set = (next: boolean) => {
    setFolded(next);
    try {
      window.localStorage.setItem(FOLDED_KEY, next ? "1" : "0");
    } catch {
      // Not remembered, still applied.
    }
  };
  return [folded, set];
}

export function depotOptions(depots: string[]): Array<{ value: string; label: string }> {
  return [
    ...(depots.length > 1 ? [{ value: "all", label: depots.length === 2 ? "Both" : "All" }] : []),
    ...depots.map((depot) => ({ value: depot, label: depot })),
  ];
}

export default function Sidebar({
  view,
  onNavigate,
  displayName,
  depots,
  depotFilter,
  onDepotFilter,
  badges = {},
  folded,
  onFold,
  scopeLabel = "",
}: {
  view: ViewId;
  onNavigate: (view: ViewId) => void;
  displayName: string;
  depots: string[];
  depotFilter: DepotFilter;
  onDepotFilter: (filter: DepotFilter) => void;
  badges?: Badges;
  /** Folded to the icon rail, by the dispatcher's own choice. */
  folded: boolean;
  onFold: (folded: boolean) => void;
  /** The depots in view, for the badges' tooltips ("both depots"). */
  scopeLabel?: string;
}): React.JSX.Element {
  const options = depotOptions(depots);
  const scopeShown = options.find((option) => option.value === depotFilter)?.label ?? "All";
  const rail = folded;

  return (
    <aside
      aria-label="Sidebar"
      className={cx(
        "hidden h-full shrink-0 flex-col bg-go-card pt-7 pb-6 lg:flex",
        rail ? "w-[84px] items-center gap-[5px] px-3" : "w-[260px] gap-1.5 px-5",
      )}
    >
      <div className={cx("flex items-center gap-2.5", rail ? "justify-center pb-[13px]" : "px-2 pb-5")}>
        <span className="text-[34px] leading-none font-extrabold text-go-ink">GO</span>
        {!rail && <span className="rounded-full bg-go-mint px-2.5 py-1 text-[13px] font-medium text-go-ink">Dispatch</span>}
      </div>

      <nav aria-label="Dispatcher" className={cx("flex flex-col", rail ? "items-center gap-[5px]" : "w-full gap-1.5")}>
        {VIEWS.map((item) => {
          const active = item.id === view;
          const badge = badges[item.id];
          const count = typeof badge === "number" ? badge : 0;
          const word = typeof badge === "object" ? badge.text : null;
          const spoken = count > 0 ? `${item.label}, ${count}` : word ? `${item.label}, ${word}` : item.label;
          return (
            <a
              key={item.id}
              href={`#/${item.id}`}
              aria-current={active ? "page" : undefined}
              aria-label={rail ? spoken : undefined}
              title={badgeHint(item.id, badge, scopeLabel) ?? (rail ? item.label : undefined)}
              onClick={(event) => {
                event.preventDefault();
                onNavigate(item.id);
              }}
              className={cx(
                "relative flex items-center text-base text-go-ink",
                rail ? "size-12 justify-center rounded-go-card" : "w-full gap-3 rounded-go-card-l px-3.5 py-3",
                active ? "bg-go-mint font-medium" : "hover:bg-go-subtle",
              )}
            >
              <Icon name={item.icon} />
              {!rail && <span className="min-w-0 flex-1">{item.label}</span>}
              {!rail && <FullBadge badge={badge} />}
              {rail && <CountBadge count={count} />}
              {rail && word && <span aria-hidden className="absolute top-0.5 right-0.5 size-2.5 rounded-full bg-go-warning ring-2 ring-go-card" />}
            </a>
          );
        })}
      </nav>

      <div className="flex-1" />

      {options.length > 1 &&
        (rail ? (
          <Popover
            label="Depot scope"
            side="right"
            trigger={<span className="text-[11px] font-medium text-go-teal">{scopeShown}</span>}
            className="mb-1 flex h-9 w-12 items-center justify-center rounded-go-card-s bg-go-surface"
            panelClassName="flex flex-col gap-2 p-3"
          >
            <p className="text-[11px] font-medium text-go-teal">Showing</p>
            <Segmented label="Depot" options={options} value={depotFilter} onChange={onDepotFilter} />
          </Popover>
        ) : (
          <div className="mb-3 flex flex-col gap-2 rounded-go-card-s bg-go-surface p-3">
            <p className="text-[11px] font-medium text-go-teal">Showing</p>
            <Segmented label="Depot" options={options} value={depotFilter} onChange={onDepotFilter} />
          </div>
        ))}

      <button
        type="button"
        onClick={() => onFold(!folded)}
        aria-label={folded ? "Expand the sidebar" : "Fold the sidebar"}
        title={folded ? "Expand the sidebar" : "Fold the sidebar"}
        className={cx(
          "flex items-center gap-2.5 rounded-go-card-s bg-go-surface p-3 text-[13px] text-go-secondary hover:bg-go-subtle",
          rail ? "mb-1" : "mb-2 w-fit",
        )}
      >
        <Icon name="table-columns" />
      </button>

      <div className={cx("flex items-center gap-2.5", !rail && "w-full p-1")}>
        <ProfileButton displayName={displayName} depots={depots} placement="popover" />
        {!rail && (
          <span className="flex min-w-0 flex-1 flex-col gap-0.5">
            <span title={displayName} className="truncate text-[15px] leading-tight font-medium text-go-ink">
              {displayName}
            </span>
            <span className="text-xs text-go-secondary">Dispatcher</span>
          </span>
        )}
      </div>
    </aside>
  );
}

/** What a badge counts, in words, for its tooltip. */
function badgeHint(view: ViewId, badge: Badge | undefined, scope: string): string | undefined {
  if (badge === undefined || badge === 0) return undefined;
  const where = scope ? ` · ${scope}` : "";
  if (typeof badge === "object") return `Plan for the day in view: ${badge.text.toLowerCase()}${where}`;
  if (view === "orders") return `${badge} ${badge === 1 ? "order today needs" : "orders today need"} a person (stock, deferred, too big, failed or not confirmed)${where}`;
  if (view === "live") return `${badge} ${badge === 1 ? "stop needs" : "stops need"} you on the road${where}`;
  return `${badge}${where}`;
}

function FullBadge({ badge }: { badge: Badge | undefined }): React.JSX.Element | null {
  if (typeof badge === "object") {
    return <span className="rounded-full bg-go-warning-tint px-2 py-0.5 text-xs font-medium text-go-warning-text">{badge.text}</span>;
  }
  if (!badge) return null;
  return <span className="rounded-full bg-go-danger-tint px-2 py-0.5 text-xs font-medium text-go-danger">{badge}</span>;
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
  const options = depotOptions(depots);
  return (
    <div className="flex flex-col gap-3 bg-go-card px-4 pt-4 pb-3 lg:hidden">
      {/* Wraps on a narrow phone so the depot switch and actions stay on screen. */}
      <div className="flex flex-wrap items-center gap-2.5">
        <span className="text-[34px] leading-none font-extrabold text-go-ink">GO</span>
        <span className="rounded-full bg-go-mint px-2.5 py-1 text-[13px] font-medium text-go-ink">Dispatch</span>
        <span className="flex-1" />
        {options.length > 1 && <Segmented label="Depot" options={options} value={depotFilter} onChange={onDepotFilter} />}
        <ShellActions compact />
        <ProfileButton displayName={displayName} depots={depots} placement="sheet" />
      </div>
      <nav aria-label="Dispatcher" className="-mx-4 flex gap-1.5 overflow-x-auto px-4">
        {VIEWS.map((item) => {
          const active = item.id === view;
          const badge = badges[item.id];
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
              {typeof badge === "number" && badge > 0 ? (
                <span aria-label={`${badge} to look at`} className="rounded-full bg-go-danger-tint px-2 py-0.5 text-xs font-medium text-go-danger">
                  {badge}
                </span>
              ) : (
                <FullBadge badge={typeof badge === "object" ? badge : undefined} />
              )}
            </a>
          );
        })}
      </nav>
    </div>
  );
}
