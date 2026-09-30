"use client";

import type { Session } from "./session.ts";
import Dispatcher from "@roles/dispatcher";
import Driver from "@roles/driver";
import Loader from "@roles/loader";
import Store from "@roles/store";

/**
 * One responsive application serving four roles, as the brief requires. The
 * shell chooses the surface; roles never reach into each other.
 */
export default function RoleRouter({ session }: { session: Session }): React.JSX.Element {
  switch (session.roles[0]!) {
    case "dispatcher":
      return <Dispatcher displayName={session.displayName} scope={session.scope} />;
    case "loader":
      return <Loader userId={session.userId} displayName={session.displayName} scope={session.scope} />;
    case "driver":
      return <Driver />;
    case "store_manager":
      return <Store />;
  }
}
