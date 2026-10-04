"use client";

import { useEffect, useState } from "react";
import { CountBadge, Icon, Popover, Segmented, SettingsPanel, ShellActions, cx, useDeviceLang, useShell } from "@shared/ui";
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
              title={rail ? item.label : undefined}
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

function FullBadge({ badge }: { badge: Badge | undefined }): React.JSX.Element | null {
  if (typeof badge === "object") {
    return <span className="rounded-full bg-go-warning-tint px-2 py-0.5 text-xs font-medium text-go-warning-text">{badge.text}</span>;
  }
  if (!badge) return null;
  return <span className="rounded-full bg-go-danger-tint px-2 py-0.5 text-xs font-medium text-go-danger">{badge}</span>;
}

/**
 * The dispatcher's picture: opens Settings (language, the assistant connection,
 * installing the app), with the account below it as Figma's "Account menu"
 * draws it: role, depots, and Sign out. Sign out is not on the bar itself, so a
 * stray click on a shared depot PC does not end a session.
 */
function ProfileButton({ displayName, depots, placement }: { displayName: string; depots: string[]; placement: "popover" | "sheet" }): React.JSX.Element {
  const [open, setOpen] = useState(false);
  const [lang, setLang] = useDeviceLang();
  const shell = useShell();
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
        >
          <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1.5 rounded-[14px] bg-go-surface px-3 py-2.5 text-[13px]">
            <dt className="text-go-secondary">Role</dt>
            <dd className="text-right font-medium text-go-ink">Dispatcher</dd>
            <dt className="text-go-secondary">Depots</dt>
            <dd className="text-right font-medium text-go-ink">{depots.join(" · ") || "None"}</dd>
          </dl>
          {shell && shell.roles.length > 1 && (
            <div role="group" aria-label="Role" className="flex flex-wrap gap-1">
              {shell.roles.map((role) => (
                <button
                  key={role.value}
                  type="button"
                  aria-pressed={role.value === shell.active}
                  onClick={() => shell.onRole(role.value)}
                  className={cx(
                    "rounded-full px-3 py-1.5 text-[13px] font-medium",
                    role.value === shell.active ? "bg-go-ink text-go-card" : "bg-go-surface text-go-ink",
                  )}
                >
                  {role.label}
                </button>
              ))}
            </div>
          )}
          {shell && (
            <button
              type="button"
              onClick={shell.onSignOut}
              className="flex min-h-12 w-full items-center justify-center rounded-[14px] bg-go-danger-tint text-[15px] font-medium text-go-danger-strong"
            >
              Sign out
            </button>
          )}
        </SettingsPanel>
      )}
    </>
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

function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  return ((parts[0]?.[0] ?? "") + (parts.length > 1 ? parts[parts.length - 1]![0] : "")).toUpperCase() || "?";
}
