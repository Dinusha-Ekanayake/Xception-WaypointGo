"use client";

import { createContext, useContext, useEffect, useId, useMemo, useRef, useState, type ReactNode } from "react";
import { Icon, cx, useEscape, writeKept } from "@shared/ui";
import type { ViewId } from "./navigation.ts";
import { groupResults, kindLabel, search, type SearchResult, type SearchSource } from "./data/search.ts";

// The dispatcher's global search: one box over everything already loaded for
// the depots in scope (issue #usability). Opens with "/" (outside an input),
// Ctrl+K or Cmd+K always, and the icon button beside the bell for touch and
// mouse. Grouped results, arrow keys and Enter to choose, Escape to close. It
// never calls the network itself: `source` is what the shell already read.
//
// Rendered once per screen's PageHeader (GlobalSearchSlot), reading what the
// shell provides below (GlobalSearchProvider), the same shape as the inbox's
// bell: the shell reads the data once, every header just shows the control.

type GlobalSearchProps = {
  source: SearchSource;
  onNavigate: (view: ViewId) => void;
  /** Issues supports opening one directly; everything else only navigates. */
  onFocusIssue: (issueId: string) => void;
  onDepotFilter: (depot: string) => void;
};

const Context = createContext<GlobalSearchProps | null>(null);

export function GlobalSearchProvider({ children, ...value }: GlobalSearchProps & { children: ReactNode }): React.JSX.Element {
  return <Context.Provider value={value}>{children}</Context.Provider>;
}

/** What a screen's header renders; nothing before the shell has provided its data. */
export function GlobalSearchSlot(): React.JSX.Element | null {
  const value = useContext(Context);
  if (!value) return null;
  return <GlobalSearch {...value} />;
}

function isTypingTarget(el: Element | null): boolean {
  if (!el) return false;
  const tag = el.tagName;
  return tag === "INPUT" || tag === "TEXTAREA" || el.getAttribute("contenteditable") === "true";
}

function GlobalSearch({ source, onNavigate, onFocusIssue, onDepotFilter }: GlobalSearchProps): React.JSX.Element {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [activeIndex, setActiveIndex] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const rootRef = useRef<HTMLDivElement>(null);
  const listboxId = useId();

  const results = useMemo(() => search(source, query), [source, query]);
  const groups = useMemo(() => groupResults(results), [results]);

  const close = () => {
    setOpen(false);
    setQuery("");
    setActiveIndex(0);
  };

  useEscape(open ? close : undefined);

  // "/" opens from anywhere that is not itself a text field; Ctrl+K and Cmd+K
  // always do, even while typing elsewhere, since that is the conventional
  // escape hatch for this shortcut.
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const combo = (event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k";
      const slash = event.key === "/" && !isTypingTarget(document.activeElement);
      if (!combo && !slash) return;
      event.preventDefault();
      setOpen(true);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  useEffect(() => {
    if (open) inputRef.current?.focus();
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const outside = (event: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(event.target as Node)) close();
    };
    document.addEventListener("mousedown", outside);
    return () => document.removeEventListener("mousedown", outside);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  useEffect(() => setActiveIndex(0), [query]);

  const choose = (result: SearchResult) => {
    if (result.kind === "order" && result.prefillOrderText) writeKept("dispatcher:orders:text", result.prefillOrderText);
    if (result.kind === "issue" && result.focusIssueId) onFocusIssue(result.focusIssueId);
    if (result.kind === "depot" && result.depotFilter) onDepotFilter(result.depotFilter);
    onNavigate(result.view);
    close();
  };

  const onKeyDown = (event: React.KeyboardEvent) => {
    if (event.key === "ArrowDown") {
      event.preventDefault();
      setActiveIndex((i) => Math.min(i + 1, Math.max(results.length - 1, 0)));
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      setActiveIndex((i) => Math.max(i - 1, 0));
    } else if (event.key === "Enter") {
      event.preventDefault();
      const chosen = results[activeIndex];
      if (chosen) choose(chosen);
    }
  };

  const activeId = results[activeIndex] ? `${listboxId}-${results[activeIndex]!.kind}-${results[activeIndex]!.id}` : undefined;

  return (
    <div ref={rootRef} className="relative">
      {!open && (
        <button
          type="button"
          onClick={() => setOpen(true)}
          aria-label="Search"
          title="Search · / or Ctrl+K"
          className="flex rounded-[21px] bg-go-card p-[11px]"
        >
          <Icon name="search" />
        </button>
      )}
      {open && (
        <div className="flex items-center gap-2 rounded-full bg-go-card px-4 py-2.5 ring-1 ring-go-rule">
          <Icon name="search" />
          <input
            ref={inputRef}
            role="combobox"
            aria-expanded={results.length > 0}
            aria-controls={listboxId}
            aria-activedescendant={activeId}
            aria-autocomplete="list"
            aria-label="Search orders, vehicles, trips, issues and depots"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            onKeyDown={onKeyDown}
            placeholder="Search orders, vehicles, trips, issues, depots"
            className="w-64 bg-transparent text-[14px] text-go-ink outline-none placeholder:text-go-placeholder"
          />
          <button type="button" onClick={close} aria-label="Close search" className="shrink-0">
            <Icon name="close" />
          </button>
        </div>
      )}
      {open && results.length > 0 && (
        <ul id={listboxId} role="listbox" aria-label="Search results" className="absolute top-full right-0 z-30 mt-1.5 flex max-h-[420px] w-[360px] flex-col gap-1 overflow-y-auto rounded-go-card bg-go-card p-1.5 shadow-go-card ring-1 ring-go-rule">
          {groups.map((group) => (
            <li key={group.kind} role="presentation">
              <p className="px-3 pt-2 pb-1 text-xs font-medium text-go-secondary">{kindLabel(group.kind)}</p>
              <ul role="group" aria-label={kindLabel(group.kind)}>
                {group.items.map((result) => {
                  const index = results.indexOf(result);
                  const active = index === activeIndex;
                  return (
                    <li key={`${result.kind}-${result.id}`} role="none">
                      <button
                        type="button"
                        id={`${listboxId}-${result.kind}-${result.id}`}
                        role="option"
                        aria-selected={active}
                        onMouseEnter={() => setActiveIndex(index)}
                        onClick={() => choose(result)}
                        className={cx(
                          "flex w-full flex-col rounded-go-card-s px-3 py-2 text-left",
                          active ? "bg-go-success-tint" : "hover:bg-go-subtle",
                        )}
                      >
                        <span className="truncate text-[13px] font-medium text-go-ink">{result.label}</span>
                        <span className="truncate text-xs text-go-secondary">{result.sublabel}</span>
                      </button>
                    </li>
                  );
                })}
              </ul>
            </li>
          ))}
        </ul>
      )}
      {open && query.trim().length > 0 && results.length === 0 && (
        <div className="absolute top-full right-0 z-30 mt-1.5 w-[360px] rounded-go-card bg-go-card p-4 text-center text-[13px] text-go-secondary shadow-go-card ring-1 ring-go-rule">
          No match for &quot;{query.trim()}&quot;.
        </div>
      )}
    </div>
  );
}
