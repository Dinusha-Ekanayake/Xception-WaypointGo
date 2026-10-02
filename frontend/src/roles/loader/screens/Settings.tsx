"use client";

import { cx, useShell } from "@shared/ui";
import { LANGS } from "../data/strings.ts";
import { useLang, useT } from "../i18n.tsx";
import { ChevronLeftIcon, MoonIcon, SunIcon } from "../icons.tsx";
import { useTheme, type Theme } from "../theme.tsx";
import { BigButton } from "../ui.tsx";

// Figma "08 Loader · Phone", Settings (rationale 24): Appearance (Light or
// Dark) and Language (සිං / த / EN), and one clear way back to work. Both are
// kept per device, so the next loader on a shared tablet finds them as left.
//
// Below the card, the device's own sign-in: the supervisor signs the device out
// here at the end of the shift (decision 2026-10-01). Figma has no place for it,
// and the loaders' Switch user is not a sign-out.

const segment = "flex min-h-11 items-center gap-1.5 rounded-full px-4 text-[15px]";

export default function Settings({
  hasLoader,
  deviceName,
  onClose,
}: {
  /** Someone is working, so the way back is Done rather than Choose loader. */
  hasLoader: boolean;
  /** The supervisor account the device is signed in as. */
  deviceName: string;
  onClose: () => void;
}): React.JSX.Element {
  const tr = useT();
  const { theme, setTheme } = useTheme();
  const { lang, setLang } = useLang();
  const shell = useShell();
  const themes: Array<{ value: Theme; label: string; icon: React.JSX.Element }> = [
    { value: "light", label: tr("Light"), icon: <SunIcon size={18} /> },
    { value: "dark", label: tr("Dark"), icon: <MoonIcon size={18} /> },
  ];

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
              <span className="text-[16px] text-go-ink">{tr("Appearance")}</span>
              <span className="text-[13px] text-go-muted">{tr("Light or dark theme")}</span>
            </legend>
            <div className="flex gap-1">
              {themes.map((t) => (
                <button
                  key={t.value}
                  type="button"
                  aria-pressed={theme === t.value}
                  onClick={() => setTheme(t.value)}
                  className={cx(segment, theme === t.value ? "bg-go-card text-go-ink shadow-go-float" : "text-go-muted")}
                >
                  {t.icon}
                  {t.label}
                </button>
              ))}
            </div>
          </fieldset>
          <hr className="border-go-rule" />
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
        </div>
        <BigButton tone="ink" size="l" onClick={onClose}>
          {tr(hasLoader ? "Done" : "Choose loader")}
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
