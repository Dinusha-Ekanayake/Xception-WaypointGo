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
  if (!session) return <main className="ac-auth"><section><h1>Welcome to Waypoint.</h1><p>Sign in to administer your team, or explore the access console demo.</p><a className="ac-button primary" href="/admin">Admin sign in</a><a href="/super-admin/demo">Preview super admin console</a><a href="/admin/demo">Preview admin console</a></section></main>;

  return (
    <div className="shell">
      <RoleRouter role={session.roles.includes("super_admin") ? "super_admin" : session.roles.includes("admin") ? "admin" : session.roles[0]!} />
    </div>
  );
}
