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
const field = "min-h-14 w-full rounded-[16px] bg-[#f1f3f5] px-4 text-[16px] text-black outline-none placeholder:text-go-muted focus:ring-2 focus:ring-go-teal";

export default function SignIn({
  onSignedIn,
  notice,
  role = null,
  home = null,
}: {
  onSignedIn: (session: Session) => void;
  notice?: string;
  /** Set on a role address: the workspace this sign-in opens. */
  role?: ShellRole | null;
  /** Set on a role address: the address every role shares, for the way back. */
  home?: string | null;
}): React.JSX.Element {
  const online = useOnline();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  const [now, setNow] = useState(() => Date.now());

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
    <div className="flex min-h-dvh flex-col bg-go-canvas px-4 pt-6 pb-10 font-go text-go-ink md:px-16 md:pt-12">
      <div className="flex items-center justify-between gap-3">
        <span className="text-[40px] leading-none font-extrabold text-black">GO</span>
        {home && (
          <a href={`https://${home}/`} className="flex min-h-10 items-center gap-1.5 rounded-full bg-white px-4 text-[14px] font-medium text-[#031b08] outline-none focus-visible:ring-2 focus-visible:ring-go-teal">
            <span aria-hidden="true">←</span> All roles
          </a>
        )}
      </div>
      <div className="mx-auto flex w-full max-w-[440px] flex-1 flex-col justify-center gap-5 py-10 md:justify-start md:pt-[10vh]">
        <div className="flex flex-col gap-1.5">
          <h1 className="text-[40px] leading-tight font-medium text-black md:text-[48px]">Welcome back</h1>
          <p className="text-[15px] text-black/80">{role ? `Sign in to open the ${ROLE_LABEL[role].toLowerCase()} workspace.` : "Sign in to open your workspace."}</p>
        </div>

        <form onSubmit={(e) => void submit(e)} className="flex w-full flex-col gap-4 rounded-[31px] bg-white p-7 shadow-[0_5px_20px_rgba(0,0,0,0.09)]">
          {notice && <Notice tone="info" title={notice} />}
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

          <button type="submit" disabled={blocked} className="min-h-14 rounded-[16px] bg-[#031a0c] text-[17px] font-medium text-white disabled:cursor-not-allowed disabled:opacity-50">
            {busy ? "Signing in…" : "Sign in"}
          </button>
        </form>

        <p className="px-1 text-[13px] text-go-muted">After your first sign-in, GO keeps working offline. Your work syncs when the connection returns.</p>
      </div>
    </div>
  );
}
