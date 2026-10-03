"use client";

import { useEffect, useRef, useState } from "react";
import { useOnline } from "@shared/api/useResource";
import Sidebar, { CompactNav, type Badges } from "./Sidebar.tsx";
import { InboxProvider } from "./inbox.tsx";
import NotificationsPanel from "./NotificationsPanel.tsx";
import { useView } from "./navigation.ts";
import { depotToday, depotsFor, scopeLabel, type DepotFilter } from "./data/scope.ts";
import { useFleet } from "./data/fleet.ts";
import { attention } from "./data/live.ts";
import { flow } from "./data/orders.ts";
import { useLive, useOrders } from "./data/useDay.ts";
import Overview from "./screens/Overview.tsx";
import Vehicles from "./screens/Vehicles.tsx";
import Live from "./screens/Live.tsx";
import Orders from "./screens/Orders.tsx";
import Plan from "./screens/Plan.tsx";
import Issues from "./screens/Issues.tsx";
import Forecast from "./screens/Forecast.tsx";

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
  // An issue Live asked to open; the Issues screen selects it.
  const [issueFocus, setIssueFocus] = useState<string | null>(null);
  const online = useOnline();

  const depots = depotsFor(depotFilter, scope);
  const label = scopeLabel(depotFilter, scope);
  // Overview is always today; the Vehicles screen can look at another day.
  const fleetDate = view === "vehicles" ? date : depotToday();
  const fleet = useFleet(depots, fleetDate);

  // The sidebar's counts: orders that need a person, and stops that do. Only a
  // read that arrived is counted, so a failed read shows no badge, not a zero.
  const today = depotToday();
  const dayOrders = useOrders(depots, today);
  const dayLive = useLive(depots, today);
  const badges: Badges = {
    orders: dayOrders.data ? flow(dayOrders.data).attention : 0,
    live: dayLive.data ? attention(dayLive.data.sheets, new Date()).length : 0,
  };

  // Catch up as soon as the connection returns rather than at the next poll.
  const wasOnline = useRef(online);
  const { refresh } = fleet;
  useEffect(() => {
    if (online && !wasOnline.current) refresh();
    wasOnline.current = online;
  }, [online, refresh]);

  return (
    <InboxProvider userId={userId}>
    <NotificationsPanel onNavigate={navigate} />
    <div className="flex min-h-dvh w-full flex-col bg-go-canvas font-go text-go-ink lg:h-dvh lg:flex-row">
      <CompactNav view={view} onNavigate={navigate} depots={scope} depotFilter={depotFilter} onDepotFilter={setDepotFilter} badges={badges} />
      <Sidebar
        view={view}
        onNavigate={navigate}
        displayName={displayName}
        depots={scope}
        depotFilter={depotFilter}
        onDepotFilter={setDepotFilter}
        badges={badges}
        rail={view === "plan"}
      />
      <div className="flex min-w-0 flex-1 flex-col gap-5 px-4 py-5 md:px-9 md:py-7 lg:overflow-y-auto">
        {scope.length === 0 ? (
          <p className="text-sm text-go-secondary">Your account has no depot in scope. Ask an administrator to grant one.</p>
        ) : view === "overview" ? (
          <Overview displayName={displayName} userId={userId} depots={depots} scopeLabel={label} fleet={fleet} online={online} onNavigate={navigate} />
        ) : view === "vehicles" ? (
          <Vehicles depots={depots} scopeLabel={label} date={date} onDate={setDate} fleet={fleet} online={online} />
        ) : view === "orders" ? (
          <Orders depots={depots} scopeLabel={label} date={date} onDate={setDate} online={online} />
        ) : view === "plan" ? (
          <Plan depots={depots} date={date} onDate={setDate} online={online} />
        ) : view === "live" ? (
          <Live
            depots={depots}
            scopeLabel={label}
            date={date}
            onDate={setDate}
            online={online}
            onOpenIssue={(issueId) => {
              setIssueFocus(issueId);
              navigate("issues");
            }}
          />
        ) : view === "issues" ? (
          <Issues depots={depots} scopeLabel={label} userId={userId} online={online} focusIssueId={issueFocus} />
        ) : (
          <Forecast depots={depots} scopeLabel={label} online={online} />
        )}
      </div>
    </div>
    </InboxProvider>
  );
}
