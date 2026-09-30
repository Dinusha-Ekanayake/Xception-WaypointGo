"use client";

import type { Role } from "@shared/offline";
import Dispatcher from "@roles/dispatcher";
import Driver from "@roles/driver";
import Loader from "@roles/loader";
import Store from "@roles/store";

/**
 * One responsive application serving four roles, as the brief requires. The
 * shell chooses the surface; roles never reach into each other.
 */
export default function RoleRouter({ role }: { role: Role }): React.JSX.Element {
  switch (role) {
    case "dispatcher":
      return <Dispatcher />;
    case "loader":
      return <Loader />;
    case "driver":
      return <Driver />;
    case "store_manager":
      return <Store />;
  }
}
