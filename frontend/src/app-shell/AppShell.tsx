"use client";

import { useCallback, useEffect, useState } from "react";
import { useOnline } from "@shared/api/useResource";
import { useSync } from "@shared/offline";
import { Notice, ShellProvider, cx, type ShellControls } from "@shared/ui";
import RoleRouter from "./RoleRouter.tsx";
import SignIn from "./SignIn.tsx";
import SyncStatus from "./SyncStatus.tsx";
import {
  ROLE_LABEL,
  currentSession,
  rememberRole,
  rememberedRole,
  signOut,
  type SessionState,
  type ShellRole,
} from "./session.ts";

/**
 * Roles whose design puts sign-out, the role switcher and the sync badge in
 * their own top bar. For these the shell draws no strip of its own and lends
 * the controls through ShellProvider instead.
 */
const OWN_HEADER = new Set<ShellRole>(["loader", "store_manager", "dispatcher"]);

/**
 * Session gate and role routing. Signed out, server unreachable and offline are
 * different states with different words (architecture rule 9): an outage must
 * never look like "sign in".
 */
export default function AppShell(): React.JSX.Element {
  const online = useOnline();
  const [state, setState] = useState<SessionState | null>(null);
  const [role, setRole] = useState<ShellRole | null>(null);
  const [notice, setNotice] = useState<string | undefined>();
  const [pending, setPending] = useState<number | null>(null);
  const [signOutError, setSignOutError] = useState<string | null>(null);
  const sync = useSync(state?.kind === "signed-in" ? state.session.userId : null);

  const check = useCallback(() => {
    setState(null);
    void currentSession().then(setState);
  }, []);

  useEffect(check, [check]);

  useEffect(() => {
    if (state?.kind === "signed-in") setRole(rememberedRole(state.session));
  }, [state]);

  useEffect(() => {
    if ("serviceWorker" in navigator && process.env.NODE_ENV === "production") {
      navigator.serviceWorker.register("/sw.js").catch(() => {
        // An unavailable service worker degrades offline support; it must not
        // stop the application loading.
      });
    }
  }, []);

  if (!state) return <main className="flex min-h-dvh items-center justify-center bg-go-canvas font-go text-go-muted">Checking your session…</main>;

  if (state.kind === "unreachable") {
    return (
      <main className="flex min-h-dvh items-center justify-center bg-go-canvas px-4 font-go">
        <div className="flex w-full max-w-[400px] flex-col gap-4">
          <Notice tone="warning" live title={online ? "Waypoint is not answering" : "This device is offline"}>
            {state.message} You are not signed out; nothing on this device was lost.
          </Notice>
          <button type="button" onClick={check} className="min-h-12 rounded-[22px] bg-[#031a0c] text-[15px] font-medium text-white">
            Try again
          </button>
        </div>
      </main>
    );
  }

  if (state.kind === "signed-out") {
    return (
      <SignIn
        notice={notice}
        onSignedIn={(session) => {
          setNotice(undefined);
          setState({ kind: "signed-in", session });
        }}
      />
    );
  }

  const { session } = state;
  const active = role ?? rememberedRole(session);

  const leave = async (force: boolean) => {
    // Writes still on this device belong to this account; signing out would
    // strand them until the same person signs in again (SEC-01).
    const waiting = sync.pending;
    if (waiting > 0 && !force) {
      setPending(waiting);
      return;
    }
    try {
      await signOut();
      setPending(null);
      setNotice("You are signed out.");
      setState({ kind: "signed-out" });
    } catch {
      setSignOutError("Could not sign out: Waypoint did not answer. Try again when the connection is back.");
    }
  };

  const controls: ShellControls = {
    roles: session.roles.map((r) => ({ value: r, label: ROLE_LABEL[r] })),
    active,
    onRole: (r) => {
      rememberRole(session, r as ShellRole);
      setRole(r as ShellRole);
    },
    onSignOut: () => void leave(false),
    sync: <SyncStatus sync={sync} online={online} />,
  };

  return (
    <ShellProvider value={controls}>
      <main className="shell">
        {!OWN_HEADER.has(active) && (
          <div className="mx-auto flex w-full max-w-[1440px] flex-wrap items-center justify-end gap-2 bg-go-canvas px-4 pt-2 font-go">
            <SyncStatus sync={sync} online={online} />
            {session.roles.length > 1 && (
              <div role="tablist" aria-label="Role" className="flex gap-1 rounded-full bg-white p-1">
                {session.roles.map((r) => (
                  <button
                    key={r}
                    type="button"
                    role="tab"
                    aria-selected={r === active}
                    onClick={() => {
                      rememberRole(session, r);
                      setRole(r);
                    }}
                    className={cx("min-h-10 rounded-full px-3 text-[13px] font-medium", r === active ? "bg-[#031a0c] text-white" : "text-go-muted")}
                  >
                    {ROLE_LABEL[r]}
                  </button>
                ))}
              </div>
            )}
            <button type="button" onClick={() => void leave(false)} className="min-h-10 rounded-full border border-[#dfe7e6] bg-white px-3.5 text-[13px] font-medium text-[#031b08]">
              Sign out
            </button>
          </div>
        )}
        {(pending !== null || signOutError) && (
          <div className="mx-auto w-full max-w-[720px] px-4 pt-2 font-go">
            {signOutError ? (
              <Notice tone="danger" live title={signOutError} action={<button type="button" className="min-h-12 px-2 text-[13px] font-medium text-go-teal" onClick={() => setSignOutError(null)}>Dismiss</button>} />
            ) : (
              <Notice
                tone="warning"
                live
                title={`${pending} ${pending === 1 ? "change is" : "changes are"} still only on this device`}
                action={
                  <span className="flex shrink-0 gap-1">
                    <button type="button" className="min-h-12 px-2 text-[13px] font-medium text-go-teal" onClick={() => setPending(null)}>
                      Stay
                    </button>
                    <button type="button" className="min-h-12 px-2 text-[13px] font-medium text-go-danger-strong" onClick={() => void leave(true)}>
                      Sign out anyway
                    </button>
                  </span>
                }
              >
                {online ? "Send them first: tap sync now, or review any the server refused." : "Reconnect so they can be sent. If you sign out now, they wait here until you sign in again."}
              </Notice>
            )}
          </div>
        )}
        <RoleRouter key={active} session={session} role={active} />
      </main>
    </ShellProvider>
  );
}
