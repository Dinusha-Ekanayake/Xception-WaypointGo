"use client";

import { McpButton, cx, useShell } from "@shared/ui";
import { usePush, type PushState } from "@shared/notifications/push";
import { LANGS } from "../data/strings.ts";
import { useLang, useT } from "../i18n.tsx";
import { ChevronLeftIcon } from "../icons.tsx";
import { BigButton } from "../ui.tsx";

// Figma "08 Loader · Phone", Settings (rationale 24): Language (සිං / த / EN)
// and the assistant connection (MCP, issue #177), and one clear way back to
// work. The theme is the sun and moon in the top bar. Choices are kept per
// device, so the next loader on a shared tablet finds them as left.
// Notifications (issue #118) turns this device's alerts on or off, and says
// plainly when it cannot: not supported, not set up on the server, or blocked.
//
// Below the card, the device's own sign-in: the supervisor signs the device out
// here at the end of the shift (decision 2026-10-01). Figma has no place for it,
// and the loaders' Switch user is not a sign-out.

const segment = "flex min-h-11 items-center gap-1.5 rounded-full px-4 text-[15px]";

export default function Settings({
  deviceName,
  onClose,
}: {
  /** The supervisor account the device is signed in as. */
  deviceName: string;
  onClose: () => void;
}): React.JSX.Element {
  const tr = useT();
  const { lang, setLang } = useLang();
  const shell = useShell();
  const push = usePush();

  return (
    <main className="flex w-full flex-col gap-6 px-4 pt-2 pb-8 md:mx-auto md:max-w-[480px]">
      <button type="button" onClick={onClose} className="flex min-h-12 items-center gap-3 self-start text-[18px] text-go-ink">
        <ChevronLeftIcon /> {tr("Back")}
      </button>
      <section aria-labelledby="loader-settings" className="flex flex-col gap-4 rounded-go-panel bg-go-card p-5 shadow-go-card">
        <h1 id="loader-settings" className="text-[24px] font-medium text-go-ink">{tr("Settings")}</h1>
        <div className="flex flex-col gap-4 rounded-go-card-l bg-go-canvas p-4">
          <fieldset className="flex flex-col gap-3">
            <legend className="flex flex-col">
              <span className="text-[16px] text-go-ink">{tr("Language")}</span>
              <span className="text-[13px] text-go-muted">{tr("App language")}</span>
            </legend>
            <div className="flex gap-1">
              {LANGS.map((l) => (
                <button
                  key={l.value}
                  type="button"
                  lang={l.html}
                  aria-label={l.label}
                  aria-pressed={lang === l.value}
                  onClick={() => setLang(l.value)}
                  className={cx(segment, "min-w-12 justify-center", lang === l.value ? "bg-go-card font-medium text-go-ink shadow-go-float" : "text-go-muted")}
                >
                  {l.short}
                </button>
              ))}
            </div>
          </fieldset>
          {shell && (
            <McpButton url={shell.mcpUrl} className="flex min-h-14 w-full items-center justify-center gap-2 rounded-[18px] bg-go-card text-[16px] font-medium text-go-ink" />
          )}
          <hr className="border-go-rule" />
          <fieldset className="flex flex-col gap-3">
            <legend className="flex flex-col">
              <span className="text-[16px] text-go-ink">{tr("Notifications")}</span>
              <span className="text-[13px] text-go-muted">{tr(pushNote(push.state))}</span>
            </legend>
            {(push.state.kind === "on" || push.state.kind === "off") && (
              <div className="flex gap-1">
                {(["on", "off"] as const).map((value) => (
                  <button
                    key={value}
                    type="button"
                    aria-pressed={push.state.kind === value}
                    disabled={push.busy}
                    onClick={() => void (value === "on" ? push.turnOn() : push.turnOff())}
                    className={cx(segment, "min-w-16 justify-center", push.state.kind === value ? "bg-go-card font-medium text-go-ink shadow-go-float" : "text-go-muted")}
                  >
                    {tr(value === "on" ? "On" : "Off")}
                  </button>
                ))}
              </div>
            )}
            {push.error && <p role="alert" className="text-[13px] text-go-danger-strong">{push.error}</p>}
          </fieldset>
        </div>
        <BigButton tone="ink" size="l" onClick={onClose}>
          {tr("Apply changes")}
        </BigButton>
      </section>

      {shell && (
        <section aria-label={tr("This device")} className="flex flex-col gap-3 px-1">
          <p className="text-[13px] text-go-muted">{tr("This device is signed in as {name}.", { name: deviceName })}</p>
          {shell.roles.length > 1 && (
            <div role="tablist" aria-label={tr("Role")} className="flex flex-wrap gap-1">
              {shell.roles.map((r) => (
                <button
                  key={r.value}
                  type="button"
                  role="tab"
                  aria-selected={r.value === shell.active}
                  onClick={() => shell.onRole(r.value)}
                  className={cx(segment, r.value === shell.active ? "bg-go-action text-go-on-action" : "bg-go-surface text-go-muted")}
                >
                  {r.label}
                </button>
              ))}
            </div>
          )}
          <BigButton tone="plain" onClick={shell.onSignOut}>
            {tr("Sign out this device")}
          </BigButton>
        </section>
      )}
    </main>
  );
}

function pushNote(state: PushState): string {
  switch (state.kind) {
    case "checking":
      return "Checking…";
    case "unsupported":
      return "This browser cannot show alerts";
    case "server-off":
      return "Alerts are not set up on this server";
    case "blocked":
      return "Blocked in this browser's settings";
    default:
      return "Alerts on this device, even with the app closed";
  }
}
