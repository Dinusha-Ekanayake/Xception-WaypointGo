"use client";

import { useEffect, useRef, useState } from "react";
import { useOnline } from "@shared/api/useResource";
import Sidebar, { CompactNav } from "./Sidebar.tsx";
import { useView } from "./navigation.ts";
import { depotToday, depotsFor, scopeLabel, type DepotFilter } from "./data/scope.ts";
import { useFleet } from "./data/fleet.ts";
import Overview from "./screens/Overview.tsx";
import Vehicles from "./screens/Vehicles.tsx";
import Live from "./screens/Live.tsx";
import Orders from "./screens/Orders.tsx";
import Plan from "./screens/Plan.tsx";
import Issues from "./screens/Issues.tsx";
import UpcomingScreen from "./screens/Upcoming.tsx";

// The dispatcher workspace from the Figma "Dispatcher · Desktop" page. It is
// online only: no write queue, and a read-only state when the connection drops
// (src/shared/offline/tiers.ts).

export default function Dispatcher({
  userId,
  displayName,
  scope,
}: {
  userId: string;
  displayName: string;
  /** Depot codes from the session. The server enforces them; this only chooses among them. */
  scope: string[];
}): React.JSX.Element {
  const [view, navigate] = useView();
  const [depotFilter, setDepotFilter] = useState<DepotFilter>("all");
  const [date, setDate] = useState(depotToday);
  const online = useOnline();

  const depots = depotsFor(depotFilter, scope);
  const label = scopeLabel(depotFilter, scope);
  // Overview is always today; the Vehicles screen can look at another day.
  const fleetDate = view === "vehicles" ? date : depotToday();
  const fleet = useFleet(depots, fleetDate);

  // Catch up as soon as the connection returns rather than at the next poll.
  const wasOnline = useRef(online);
  const { refresh } = fleet;
  useEffect(() => {
    if (online && !wasOnline.current) refresh();
    wasOnline.current = online;
  }, [online, refresh]);

  return (
    <div className="flex min-h-dvh w-full flex-col bg-go-canvas font-go text-go-ink lg:h-dvh lg:flex-row">
      <CompactNav view={view} onNavigate={navigate} depots={scope} depotFilter={depotFilter} onDepotFilter={setDepotFilter} />
      <Sidebar
        view={view}
        onNavigate={navigate}
        displayName={displayName}
        depots={scope}
        depotFilter={depotFilter}
        onDepotFilter={setDepotFilter}
      />
      <div className="flex min-w-0 flex-1 flex-col gap-5 px-4 py-5 md:px-9 md:py-7 lg:overflow-y-auto">
        {scope.length === 0 ? (
          <p className="text-sm text-go-secondary">Your account has no depot in scope. Ask an administrator to grant one.</p>
        ) : view === "overview" ? (
          <Overview displayName={displayName} userId={userId} depots={depots} scopeLabel={label} fleet={fleet} online={online} onNavigate={navigate} />
        ) : view === "vehicles" ? (
          <Vehicles scopeLabel={label} date={date} onDate={setDate} fleet={fleet} online={online} />
        ) : view === "orders" ? (
          <Orders depots={depots} scopeLabel={label} date={date} onDate={setDate} online={online} />
        ) : view === "plan" ? (
          <Plan depots={depots} date={date} onDate={setDate} online={online} />
        ) : view === "live" ? (
          <Live depots={depots} scopeLabel={label} date={date} onDate={setDate} online={online} />
        ) : view === "issues" ? (
          <Issues depots={depots} scopeLabel={label} userId={userId} online={online} />
        ) : (
          <UpcomingScreen view={view} scopeLabel={label} online={online} lastSyncedAt={fleet.loadedAt} />
        )}
      </div>
    </div>
  );
}
