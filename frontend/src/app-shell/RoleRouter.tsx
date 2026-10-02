"use client";

import { scopeOf, type Session, type ShellRole } from "./session.ts";
import Dispatcher from "@roles/dispatcher";
import Driver from "@roles/driver";
import Loader from "@roles/loader";
import Store from "@roles/store";
import AccessDemo from "@roles/admin/access/AccessDemo";

/**
 * One responsive application serving every role, as the brief requires. The
 * shell chooses the surface and hands each role only the grants it works in;
 * roles never reach into each other.
 */
export default function RoleRouter({ session, role }: { session: Session; role: ShellRole }): React.JSX.Element {
  const depots = scopeOf(session, "depot");
  switch (role) {
    case "dispatcher":
      return <Dispatcher displayName={session.displayName} scope={depots} />;
    case "loader":
      return <Loader userId={session.userId} displayName={session.displayName} scope={depots} operator={session.operator} />;
    case "driver":
      return <Driver userId={session.userId} displayName={session.displayName} scope={depots} />;
    case "store_manager":
      return <Store userId={session.userId} displayName={session.displayName} scope={scopeOf(session, "outlet")} />;
    case "admin":
      // The admin preview currently has an interactive sample console. Keep it
      // behind the normal session and role gate until live admin APIs exist.
      if (typeof window !== "undefined" && window.location.hostname.startsWith("admin-preview.")) {
        return <AccessDemo />;
      }
      return (
        <section aria-label="admin" className="mx-auto max-w-[720px] px-5 py-10 text-[15px] text-go-muted">
          The admin console is not built yet.
        </section>
      );
    case "auditor":
      // Built in #22 and #23.
      return (
        <section aria-label={role} className="mx-auto max-w-[720px] px-5 py-10 text-[15px] text-go-muted">
          The auditor console is not built yet.
        </section>
      );
  }
}
