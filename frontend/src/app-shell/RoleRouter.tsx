"use client";

import type { Session } from "./session";
import AdminEntry from "./AdminEntry";
import Dispatcher from "@roles/dispatcher";
import Driver from "@roles/driver";
import Loader from "@roles/loader";
import Store from "@roles/store";

/**
 * One responsive application serving four roles, as the brief requires. The
 * shell chooses the surface; roles never reach into each other.
 */
export default function RoleRouter({ role }: { role: Session["roles"][number] }): React.JSX.Element {
  switch (role) {
    case "super_admin":
      return <AdminEntry requiredRole="super_admin" />;
    case "admin":
      return <AdminEntry requiredRole="admin" />;
    case "dispatcher":
      return <Dispatcher />;
    case "loader":
      return <Loader />;
    case "driver":
      return <Driver />;
    case "store_manager":
      return <Store />;
    default:
      return <p>Your role does not have a workspace yet.</p>;
  }
}
