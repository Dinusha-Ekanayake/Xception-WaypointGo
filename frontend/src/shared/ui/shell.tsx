"use client";

import { createContext, useContext, type ReactNode } from "react";
import { Icon } from "./Icon.tsx";
import { cx } from "./primitives.tsx";

// What the app shell lends a role's own header. In the designs, signing out
// ("Switch user"), the role switcher and the sync badge sit inside each role's
// top bar, not in a strip above it, so the shell hands them down rather than
// drawing its own bar. Shared rather than in the shell because roles may not
// import the shell.

export type ShellRoleOption = { value: string; label: string };

export type ShellControls = {
  roles: ShellRoleOption[];
  active: string;
  onRole: (role: string) => void;
  onSignOut: () => void;
  /** The pending-writes badge and review list, or nothing when there is none. */
  sync: ReactNode;
  /** The read-only MCP address on the shared host, for "Connect AI assistant". */
  mcpUrl: string | null;
};

const ShellContext = createContext<ShellControls | null>(null);

export const ShellProvider = ShellContext.Provider;

export function useShell(): ShellControls | null {
  return useContext(ShellContext);
}

const pill = "flex min-h-12 shrink-0 items-center gap-2 rounded-full bg-white shadow-[0_5px_20px_rgba(0,0,0,0.09)]";

/**
 * The right-hand cluster of a role's top bar: sync badge, role switcher when the
 * account holds several, and "Switch user". Labels hide below `lg`, where the
 * designs show icon buttons.
 */
export function ShellActions({ compact = false }: { compact?: boolean }): React.JSX.Element | null {
  const shell = useShell();
  if (!shell) return null;
  return (
    <div className="flex flex-wrap items-center justify-end gap-2">
      {shell.sync}
      {shell.roles.length > 1 && (
        <div role="tablist" aria-label="Role" className={cx(pill, "gap-1 p-1")}>
          {shell.roles.map((r) => (
            <button
              key={r.value}
              type="button"
              role="tab"
              aria-selected={r.value === shell.active}
              onClick={() => shell.onRole(r.value)}
              className={cx(
                "min-h-10 rounded-full px-3 text-[13px] font-medium",
                r.value === shell.active ? "bg-[#031a0c] text-white" : "text-go-muted",
              )}
            >
              {r.label}
            </button>
          ))}
        </div>
      )}
      <button
        type="button"
        onClick={shell.onSignOut}
        aria-label="Switch user"
        className={cx(pill, compact ? "size-12 justify-center" : "justify-center px-3.5 max-lg:size-12 max-lg:px-0")}
      >
        <Icon name="switch-user" />
        {!compact && <span className="text-[14px] text-go-muted max-lg:hidden">Switch user</span>}
      </button>
    </div>
  );
}
