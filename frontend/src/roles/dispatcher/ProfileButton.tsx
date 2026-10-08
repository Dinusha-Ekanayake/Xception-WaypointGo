"use client";

import { useState } from "react";
import { request } from "@shared/api/client";
import { useResource } from "@shared/api/useResource";
import { McpButton, SettingsPanel, cx, useDeviceLang, useShell } from "@shared/ui";

/** Where an assistant connects (RFC 9728); refused, and so no button, when MCP is not set up here. */
type ProtectedResource = { resource: string };

/**
 * The dispatcher's picture: opens Settings (language, the assistant connection,
 * installing the app), with the account below it as Figma's "Account menu"
 * draws it: role, depots, and Sign out. Sign out is not on the bar itself, so a
 * stray click on a shared depot PC does not end a session.
 */
export default function ProfileButton({ displayName, depots, placement }: { displayName: string; depots: string[]; placement: "popover" | "sheet" }): React.JSX.Element {
  const [open, setOpen] = useState(false);
  const [lang, setLang] = useDeviceLang();
  const shell = useShell();
  // Issue #177: read only when Settings is open; the server names its own address.
  const mcp = useResource(
    open ? (signal: AbortSignal) => request<ProtectedResource>("/api/oauth/protected-resource", { signal }) : null,
    open ? "mcp-resource" : "",
  );
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
                    "relative rounded-full px-3 py-1.5 text-[13px] font-medium before:absolute before:-inset-y-1.5",
                    role.value === shell.active ? "bg-go-ink text-go-card" : "bg-go-surface text-go-ink",
                  )}
                >
                  {role.label}
                </button>
              ))}
            </div>
          )}
          <McpButton
            url={mcp.data?.resource ?? null}
            className="flex min-h-12 w-full items-center justify-center gap-2 rounded-[14px] bg-go-surface text-[15px] font-medium text-go-ink"
          />
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

function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  return ((parts[0]?.[0] ?? "") + (parts.length > 1 ? parts[parts.length - 1]![0] : "")).toUpperCase() || "?";
}
