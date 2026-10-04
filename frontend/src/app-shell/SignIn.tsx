"use client";

import { useEffect, useState } from "react";
import { ApiError } from "@shared/api/problem";
import { useOnline } from "@shared/api/useResource";
import { Notice } from "@shared/ui";
import { ROLE_LABEL, signIn, type Session, type ShellRole } from "./session.ts";

// Sign in. Three failures read differently, because the person does something
// different about each: a wrong password (one generic message, so accounts
// cannot be enumerated), a lockout (wait, with a countdown when the server says
// how long), and a server that did not answer (not their fault; try later).

type Failure = { kind: "credentials" | "locked" | "outage" | "offline"; message: string; until?: number };

function describe(error: unknown): Failure {
  if (error instanceof ApiError) {
    if (error.status === 401) return { kind: "credentials", message: "Email or password is incorrect." };
    if (error.status === 429 || error.status === 403) {
      const seconds = Number(error.problem.extensions.retryAfterSeconds ?? 0);
      return {
        kind: "locked",
        message: error.message || "Too many failed attempts.",
        until: seconds > 0 ? Date.now() + seconds * 1000 : undefined,
      };
    }
    if (error.status >= 500) return { kind: "outage", message: "Waypoint is not answering right now. Your details were not checked; try again in a few minutes." };
    return { kind: "credentials", message: error.message };
  }
  return { kind: "outage", message: "Could not reach Waypoint. Check the connection and try again." };
}

// Filled fields with the label as placeholder, as in "01 Sign in"; each keeps
// an aria-label because a placeholder is not a label.
const field = "min-h-14 w-full rounded-[16px] bg-go-surface px-4 text-[16px] text-go-ink outline-none placeholder:text-go-muted focus:ring-2 focus:ring-go-teal";

// Light or dark, kept per device like the role themes. Dark is the shared
// go-dark tokens (src/shared/ui/theme.css), so the page needs one set of classes.
const THEME_KEY = "waypoint.signin.theme";

function storedDark(): boolean {
  try {
    return window.localStorage.getItem(THEME_KEY) === "dark";
  } catch {
    return false;
  }
}

export default function SignIn({
  onSignedIn,
  notice,
  role = null,
}: {
  onSignedIn: (session: Session) => void;
  notice?: string;
  /** Set on a role address: the workspace this sign-in opens. */
  role?: ShellRole | null;
}): React.JSX.Element {
  const online = useOnline();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  const [now, setNow] = useState(() => Date.now());
  const [dark, setDark] = useState(false);

  useEffect(() => setDark(storedDark()), []);
  const toggleTheme = () => {
    const next = !dark;
    setDark(next);
    try {
      window.localStorage.setItem(THEME_KEY, next ? "dark" : "light");
    } catch {
      // Private mode or blocked storage: the choice lasts until reload.
    }
  };

  const lockedFor = failure?.until ? Math.max(0, Math.ceil((failure.until - now) / 1000)) : 0;
  useEffect(() => {
    if (!failure?.until) return;
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, [failure]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setFailure(null);
    try {
      onSignedIn(await signIn(email.trim(), password));
    } catch (error) {
      setFailure(describe(error));
      setPassword("");
    } finally {
      setBusy(false);
    }
  };

  const blocked = !online || busy || lockedFor > 0;

  return (
    <div className={`${dark ? "go-dark " : ""}relative isolate flex min-h-dvh flex-col overflow-hidden bg-go-canvas px-4 pt-6 pb-10 font-go text-go-ink transition-colors md:px-16 md:pt-12`}>
      {/* The Colombo skyline for the theme, washed with the canvas from the top so the words stay readable. */}
      <div
        aria-hidden="true"
        className="pointer-events-none absolute inset-0 -z-10 bg-cover bg-bottom"
        style={{ backgroundImage: `url(/assets/signin_skyline_${dark ? "dark" : "light"}.png)` }}
      >
        <div className="absolute inset-0 bg-linear-to-b from-go-canvas via-go-canvas/75 to-transparent" />
      </div>
      <header className="flex items-center gap-3">
        <span className="mr-auto text-[40px] leading-none font-extrabold text-go-ink">GO</span>
        {/* The driver sign-in's theme toggle, on tokens. */}
        <button
          type="button"
          onClick={toggleTheme}
          className="flex h-[42px] w-[42px] items-center justify-center rounded-[22px] border border-go-rule bg-go-card text-go-ink shadow-[0_5px_20px_rgba(0,0,0,0.09)] transition-all hover:bg-go-surface active:scale-95"
          title={dark ? "Use the light theme" : "Use the dark theme"}
          aria-label={dark ? "Use the light theme" : "Use the dark theme"}
        >
          {dark ? (
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z" />
            </svg>
          ) : (
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <circle cx="12" cy="12" r="5" />
              <line x1="12" y1="1" x2="12" y2="3" />
              <line x1="12" y1="21" x2="12" y2="23" />
              <line x1="4.22" y1="4.22" x2="5.64" y2="5.64" />
              <line x1="18.36" y1="18.36" x2="19.78" y2="19.78" />
              <line x1="1" y1="12" x2="3" y2="12" />
              <line x1="21" y1="12" x2="23" y2="12" />
              <line x1="4.22" y1="19.78" x2="5.64" y2="18.36" />
              <line x1="18.36" y1="5.64" x2="19.78" y2="4.22" />
            </svg>
          )}
        </button>
      </header>
      <div className="mx-auto flex w-full max-w-[440px] flex-1 flex-col justify-center gap-5 py-10 md:justify-start md:pt-[10vh]">
        <div className="flex flex-col gap-1.5">
          <h1 className="text-[40px] leading-tight font-medium text-go-ink md:text-[48px]">Welcome back</h1>
          <p className="text-[15px] text-go-ink/80">{role ? `Sign in to open the ${ROLE_LABEL[role].toLowerCase()} workspace.` : "Sign in to open your workspace."}</p>
        </div>

        <form onSubmit={(e) => void submit(e)} className="flex w-full flex-col gap-4 rounded-[31px] bg-go-card p-7 shadow-[0_5px_20px_rgba(0,0,0,0.09)]">
          {notice && <p role="status" className="px-1 text-[15px] font-medium text-go-success">{notice}</p>}
          {!online && (
            <Notice tone="warning" live title="This device is offline">
              Signing in needs a connection the first time. Once signed in, your work keeps going offline.
            </Notice>
          )}
          {failure && (
            <Notice tone={failure.kind === "outage" ? "warning" : "danger"} live title={failure.kind === "locked" && lockedFor > 0 ? `Too many failed attempts. Try again in ${Math.floor(lockedFor / 60)}:${String(lockedFor % 60).padStart(2, "0")}.` : failure.message} />
          )}

          <input className={field} type="email" aria-label="Email" placeholder="Enter your email" autoComplete="username" required value={email} onChange={(e) => setEmail(e.target.value)} />
          <input className={field} type="password" aria-label="Password" placeholder="Enter your password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />
          <p className="px-1 text-[13px] text-go-muted">Forgot password? Ask your administrator to reset it.</p>

          <button type="submit" disabled={blocked} className="min-h-14 rounded-[16px] bg-go-action text-[17px] font-medium text-go-on-action disabled:cursor-not-allowed disabled:opacity-50">
            {busy ? "Signing in…" : "Sign in"}
          </button>
        </form>

        <p className="px-1 text-[13px] text-go-muted">After your first sign-in, GO keeps working offline. Your work syncs when the connection returns.</p>
      </div>
    </div>
  );
}
