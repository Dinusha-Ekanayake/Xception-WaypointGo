"use client";

import { useCallback, useEffect, useState } from "react";
import { useOnline } from "@shared/api/useResource";
import { useSync } from "@shared/offline";
import { McpButton, Notice, ShellProvider, StructuredError, cx, type ShellControls } from "@shared/ui";
import { ROLE_ADDRESSES, hostForRole, roleForHost, sharedHomeFor } from "./hostRole.ts";
import RoleLanding from "./RoleLanding.tsx";
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
/** How often an open tab asks whether a new build was deployed. */
const UPDATE_CHECK_MS = 30 * 60_000;
const OWN_HEADER =new Set<ShellRole>(["loader", "store_manager", "dispatcher", "driver"]);

/**
 * Session gate and role routing. Signed out, server unreachable and offline are
 * different states with different words (architecture rule 9): an outage must
 * never look like "sign in".
 */
export default function AppShell(): React.JSX.Element {
  const online = useOnline();
  const [state, setState] = useState<SessionState | null>(() => {
    if (typeof window !== "undefined" && process.env.NODE_ENV !== "production") {
      const urlRole = new URLSearchParams(window.location.search).get("role") as ShellRole | null;
      if (urlRole && ["driver", "loader", "store_manager", "dispatcher"].includes(urlRole)) {
        return {
          kind: "signed-in",
          session: {
            userId: "dev-driver-id",
            displayName: "Rashmika Dilshan",
            roles: ["driver", "loader", "store_manager", "dispatcher"],
            operator: null,
            scope: ["depot:PELIYAGODA", "outlet:OUT001", "vehicle:DRV-00021"],
          },
        };
      }
    }
    return null;
  });
  const unverified = state?.kind === "signed-in" && state.unverified === true;

  const [role, setRole] = useState<ShellRole | null>(() => {
    if (typeof window !== "undefined" && process.env.NODE_ENV !== "production") {
      const urlRole = new URLSearchParams(window.location.search).get("role") as ShellRole | null;
      if (urlRole && ["driver", "loader", "store_manager", "dispatcher"].includes(urlRole)) {
        return urlRole;
      }
    }
    return null;
  });

  const [notice, setNotice] = useState<string | undefined>();
  const [pending, setPending] = useState<number | null>(null);
  const [signOutError, setSignOutError] = useState<string | null>(null);
  const sync = useSync(state?.kind === "signed-in" ? state.session.userId : null);

  const check = useCallback(() => {
    setState(null);
    void currentSession().then(setState);
  }, []);

  useEffect(() => {
    // Only check server session if not already in dev role mode
    if (!state) {
      check();
    }
  }, [check, state]);

  // Working from the remembered session because the server could not be asked:
  // ask again when the connection returns, so an expired session is found out
  // before the queue is sent rather than by it.
  useEffect(() => {
    if (!unverified || !online) return;
    void currentSession().then(setState);
  }, [unverified, online]);

  // A role address such as loader.waypointgo.live shows that role and no other.
  // Read only once the session is known, so the first paint matches the server's.
  const host = state ? window.location.hostname : "";
  const pinned = roleForHost(host);

  // The address every role shares has no workspace or sign-in where the role
  // addresses are served: it offers them, whatever the session on this one says.
  const landing = sharedHomeFor(host, "dispatcher", ROLE_ADDRESSES) !== null;

  useEffect(() => {
    if (state?.kind === "signed-in") setRole(rememberedRole(state.session));
  }, [state]);

  // The read-only MCP address for "Connect AI assistant", taken from the
  // server's own metadata: it is the configured shared host, never a role
  // address, and the button stays hidden where MCP is off (404) or unreachable.
  const [mcpUrl, setMcpUrl] = useState<string | null>(null);
  const signedIn = state?.kind === "signed-in";
  useEffect(() => {
    if (!signedIn) return;
    fetch("/.well-known/oauth-protected-resource/mcp", { cache: "no-store" })
      .then((r) => (r.ok ? r.json() : null))
      .then((m: { resource?: unknown } | null) => setMcpUrl(typeof m?.resource === "string" ? m.resource : null))
      .catch(() => setMcpUrl(null));
  }, [signedIn]);

  useEffect(() => {
    if (!("serviceWorker" in navigator) || process.env.NODE_ENV !== "production") return;
    const sw = navigator.serviceWorker;
    // A dock tablet stays open all shift, so it never navigates and would keep
    // the build it first loaded. Ask for a new worker when the screen comes
    // back and every 30 minutes; when one takes over, reload into its build.
    // Queued work lives in IndexedDB, so the reload loses nothing.
    const hadController = sw.controller !== null;
    let reloaded = false;
    const onControllerChange = () => {
      if (!hadController || reloaded) return;
      reloaded = true;
      window.location.reload();
    };
    const check = () => void sw.getRegistration().then((reg) => reg?.update()).catch(() => {});
    const onVisible = () => document.visibilityState === "visible" && check();
    sw.addEventListener("controllerchange", onControllerChange);
    document.addEventListener("visibilitychange", onVisible);
    const timer = window.setInterval(check, UPDATE_CHECK_MS);
    sw.register("/sw.js").catch(() => {
      // An unavailable service worker degrades offline support; it must not
      // stop the application loading.
    });
    return () => {
      sw.removeEventListener("controllerchange", onControllerChange);
      document.removeEventListener("visibilitychange", onVisible);
      window.clearInterval(timer);
    };
  }, []);


  if (!state) return <main className="flex min-h-dvh items-center justify-center bg-go-canvas font-go text-go-muted">Checking your session…</main>;

  if (landing) return <RoleLanding host={host} />;

  if (state.kind === "unreachable") {
    const code = state.status ?? (online ? 503 : "OFFLINE");
    const message =
      sync.pending > 0
<<<<<<< HEAD
        ? `Having trouble connecting right now.\nDon't worry, ${sync.pending} ${
=======
        ? `Having trouble connecting right now.\nDon't worry: ${sync.pending} ${
>>>>>>> origin/dev
            sync.pending === 1 ? "change is" : "changes are"
          } saved safely on this phone.`
        : undefined;

    return (
      <StructuredError
        code={code}
        message={message}
        actionLabel="Try again"
        onAction={check}
        secondaryAction={
          process.env.NODE_ENV !== "production" ? (
            <div className="mt-4 flex flex-col items-center gap-2">
              <span className="text-[12px] text-go-muted font-medium">Dev Mode (Backend Offline)</span>
              <div className="flex flex-wrap justify-center gap-1.5">
                {(["driver", "loader", "store_manager", "dispatcher"] as const).map((r) => (
                  <button
                    key={r}
                    type="button"
                    onClick={() => {
                      setState({
                        kind: "signed-in",
                        session: {
                          userId: "dev-driver-id",
                          displayName: "Dev Driver",
                          roles: ["driver", "loader", "store_manager", "dispatcher"],
                          operator: null,
                          scope: ["depot:PELIYAGODA", "outlet:OUT001", "vehicle:DRV-00021"],
                        },
                      });
                      setRole(r);
                    }}
                    className="rounded-full bg-white border border-[#dfe7e6] px-3.5 py-1.5 text-xs font-semibold text-[#031b08] shadow-xs hover:bg-slate-50 active:scale-95 transition-all"
                  >
                    Open {r.replace("_", " ")}
                  </button>
                ))}
              </div>
            </div>
          ) : undefined
        }
      />
    );
  }

  if (state.kind === "signed-out") {
    return (
      <SignIn
        role={pinned}
        notice={notice}
        onSignedIn={(session) => {
          setNotice(undefined);
          setState({ kind: "signed-in", session });
        }}
      />
    );
  }

  const { session } = state;
  // Signed in on another role's address: say so and point at their own,
  // rather than show a surface the account does not hold.
  const misplaced = pinned !== null && !session.roles.includes(pinned);
  const roles = pinned ? (misplaced ? [] : [pinned]) : session.roles;
  const active = pinned && !misplaced ? pinned : role ?? rememberedRole(session);
  const adminPreview = !misplaced && active === "admin" && host.startsWith("admin-preview.");

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
    roles: roles.map((r) => ({ value: r, label: ROLE_LABEL[r] })),
    active,
    onRole: (r) => {
      rememberRole(session, r as ShellRole);
      setRole(r as ShellRole);
    },
    onSignOut: () => void leave(false),
    sync: <SyncStatus sync={sync} online={online} />,
    mcpUrl,
  };

  return (
    <ShellProvider value={controls}>
      <main className={cx("shell", adminPreview && "relative")}>
        {(misplaced || !OWN_HEADER.has(active) && !adminPreview) && (
          <div className={cx("mx-auto flex w-full max-w-[1440px] flex-wrap items-center justify-end gap-2 bg-go-canvas px-4 pt-2 font-go", adminPreview && "lg:absolute lg:inset-x-0 lg:top-0 lg:z-10 lg:max-w-none lg:bg-transparent lg:pr-8")}>
            <SyncStatus sync={sync} online={online} />
            {!misplaced && <McpButton url={mcpUrl} className="flex min-h-10 items-center gap-2 rounded-full border border-[#dfe7e6] bg-white px-3.5 text-[13px] font-medium text-[#031b08]" />}
            {roles.length > 1 && (
              <div role="tablist" aria-label="Role" className="flex gap-1 rounded-full bg-white p-1">
                {roles.map((r) => (
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
        {misplaced ? (
          <section aria-label="Wrong address" className="mx-auto flex w-full max-w-[720px] flex-col gap-3 px-4 py-10 font-go">
            <Notice tone="warning" title={`This address is for the ${ROLE_LABEL[pinned].toLowerCase()} role`}>
              {session.displayName} does not hold it. Open your own address and sign in there.
            </Notice>
            {session.roles.map((r) => (
              <a key={r} href={`https://${hostForRole(host, r)}/`} className="flex min-h-12 items-center rounded-[16px] bg-white px-4 text-[15px] font-medium text-go-teal">
                {ROLE_LABEL[r]}: {hostForRole(host, r)}
              </a>
            ))}
          </section>
        ) : (
          <RoleRouter key={active} session={session} role={active} />
        )}
      </main>
    </ShellProvider>
  );
}
