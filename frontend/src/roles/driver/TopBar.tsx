"use client";

import { useShell } from "@shared/ui";
import { clock } from "./data/run.ts";
import { BackIcon, MoonIcon, RoundButton, SignOutIcon, SunIcon } from "./ui.tsx";

/**
 * The top of every driver screen. Home shows the GO mark and sign-out; inner
 * screens show Back. Both show whether the phone is in step with the server,
 * in words, because "saved" and "sent" are different promises (EXE-01).
 */
export default function TopBar({
  online,
  syncedAt,
  keptAt,
  uploads,
  dark,
  onTheme,
  onBack,
  onSignOut,
}: {
  online: boolean;
  syncedAt: Date | null;
  /** Showing the copy kept on this phone, read at this time. */
  keptAt: Date | null;
  /** Proof photos and signatures still only on this phone. */
  uploads: number;
  dark: boolean;
  onTheme: () => void;
  onBack?: () => void;
  onSignOut?: () => void;
}): React.JSX.Element {
  const shell = useShell();
  // Writes waiting are counted by the shell's badge beside this; proof files are counted here.
  const status = !online
    ? "Offline"
    : uploads > 0
      ? `Sending ${uploads} proof ${uploads === 1 ? "file" : "files"}`
      : syncedAt
        ? `Synced ${clock(syncedAt)}`
        : keptAt
          ? `Saved copy ${clock(keptAt)}`
          : "Connecting";
  return (
    <header className="flex min-h-16 flex-wrap items-center gap-2 px-5 pt-3">
      {onBack ? (
        <button type="button" onClick={onBack} className="-ml-2 flex min-h-12 items-center gap-2 rounded-full px-2 text-[19px] font-medium text-go-ink">
          <BackIcon />
          Back
        </button>
      ) : (
        <span className="text-[34px] font-black tracking-tight text-go-ink" aria-label="GO">
          GO
        </span>
      )}
      <div className="ml-auto flex flex-wrap items-center justify-end gap-2">
        <span
          role="status"
          aria-live="polite"
          className={`flex min-h-10 items-center whitespace-nowrap rounded-full px-4 text-[15px] font-medium shadow-go-float ${
            online ? "bg-go-card text-go-ink" : "bg-go-warning-tint text-go-warning-text"
          }`}
        >
          {status}
        </span>
        {shell?.sync}
        {onSignOut && (
          <RoundButton label="Sign out" onClick={onSignOut}>
            <SignOutIcon />
          </RoundButton>
        )}
        <RoundButton label={dark ? "Use the light theme" : "Use the dark theme"} onClick={onTheme}>
          {dark ? <MoonIcon /> : <SunIcon />}
        </RoundButton>
      </div>
    </header>
  );
}
