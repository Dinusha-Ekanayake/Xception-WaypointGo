"use client";

import { useEffect, useState } from "react";
import { ApiError } from "@shared/api/problem";
import { useOnline } from "@shared/api/useResource";
import { Notice } from "@shared/ui";
import { signIn, type Session } from "./session.ts";

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

const field = "min-h-12 w-full rounded-[16px] border border-[#dfe7e6] bg-white px-4 text-[16px] text-black outline-none focus:border-go-teal";

export default function SignIn({ onSignedIn, notice }: { onSignedIn: (session: Session) => void; notice?: string }): React.JSX.Element {
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
    <div className="flex min-h-dvh items-center justify-center bg-go-canvas px-4 py-10 font-go text-go-ink">
      <form onSubmit={(e) => void submit(e)} className="flex w-full max-w-[400px] flex-col gap-5 rounded-[31px] bg-white px-6 py-8 shadow-[0_5px_20px_rgba(0,0,0,0.09)]">
        <div className="flex flex-col gap-1">
          <span className="text-[40px] leading-none font-extrabold text-black">GO</span>
          <h1 className="text-[24px] font-medium text-black">Sign in to Waypoint</h1>
        </div>

        {notice && <Notice tone="info" title={notice} />}
        {!online && (
          <Notice tone="warning" live title="This device is offline">
            Signing in needs a connection the first time. Once signed in, your work keeps going offline.
          </Notice>
        )}
        {failure && (
          <Notice tone={failure.kind === "outage" ? "warning" : "danger"} live title={failure.kind === "locked" && lockedFor > 0 ? `Too many failed attempts. Try again in ${Math.floor(lockedFor / 60)}:${String(lockedFor % 60).padStart(2, "0")}.` : failure.message} />
        )}

        <label className="flex flex-col gap-1.5 text-[13px] font-medium text-go-muted">
          Email
          <input className={field} type="email" autoComplete="username" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </label>
        <label className="flex flex-col gap-1.5 text-[13px] font-medium text-go-muted">
          Password
          <input className={field} type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />
        </label>

        <button type="submit" disabled={blocked} className="min-h-14 rounded-[22px] bg-[#031a0c] text-[17px] font-medium text-white disabled:cursor-not-allowed disabled:opacity-50">
          {busy ? "Signing in…" : "Sign in"}
        </button>
      </form>
    </div>
  );
}
