"use client";

import { useEffect, useState } from "react";
import { currentSession, type Session } from "./session.ts";
import RoleRouter from "./RoleRouter.tsx";

/**
 * Session gate and role routing. Screens arrive with the Figma design system;
 * this is the frame they hang in.
 */
export default function AppShell(): React.JSX.Element {
  const [session, setSession] = useState<Session | null>(null);
  const [checked, setChecked] = useState(false);

  useEffect(() => {
    let active = true;
    currentSession().then((result) => {
      if (!active) return;
      setSession(result);
      setChecked(true);
    });
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    if ("serviceWorker" in navigator && process.env.NODE_ENV === "production") {
      navigator.serviceWorker.register("/sw.js").catch(() => {
        // An unavailable service worker degrades offline support; it must not
        // stop the application loading.
      });
    }
  }, []);

  if (!checked) return <main className="shell">Checking your session...</main>;
  if (!session) return (
    <main className="shell mx-auto flex min-h-screen max-w-2xl items-center px-6 py-12">
      <section className="w-full rounded-2xl border border-[#d9e3de] bg-white p-8 shadow-sm">
        <p className="text-sm font-semibold uppercase tracking-wide text-[#0a6b63]">Waypoint Dispatch</p>
        <h1 className="mt-3 text-3xl font-semibold">Sign in to continue</h1>
        <p className="mt-3 text-[#4c5851]">The live application needs a session. You can explore the permission UI with sample people and capabilities right now.</p>
        <a className="mt-6 inline-flex min-h-11 items-center rounded-xl bg-[#0a6b63] px-5 font-semibold text-white hover:bg-[#084f4a]" href="/access-demo">Open permission UI demo</a>
        <p className="mt-3 text-sm text-[#66736b]">Demo changes stay in this browser session and do not update the backend.</p>
      </section>
    </main>
  );

  return (
    <main className="shell">
      <RoleRouter role={session.roles[0]!} />
    </main>
  );
}
