"use client";

import { useEffect, useRef, useState } from "react";
import { useOnline } from "@shared/api/useResource";
import { ToastProvider, useScrollMemory } from "@shared/ui";
import Sidebar, { CompactNav, useFolded, type Badges } from "./Sidebar.tsx";
import { DepotScopeProvider } from "./depotScope.tsx";
import { InboxProvider } from "./inbox.tsx";
import NotificationsPanel from "./NotificationsPanel.tsx";
import ThreadSheet from "./ThreadSheet.tsx";
import { useView } from "./navigation.ts";
import { depotToday, depotsFor, scopeLabel, type DepotFilter } from "./data/scope.ts";
import { useFleet } from "./data/fleet.ts";
import { attention } from "./data/live.ts";
import { flow } from "./data/orders.ts";
import { useLive, useOrders, usePlans, type DepotPlans } from "./data/useDay.ts";
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
  // Each screen comes back where it was scrolled (UX polish 2).
  const main = useRef<HTMLDivElement>(null);
  useScrollMemory(`dispatcher:${view}`, main);
  /** Opens the Plan screen on a day: from the Overview's plan card and from an order. */
  const openPlan = (day: string) => {
    setDate(day);
    navigate("plan");
  };
  const [depotFilter, setDepotFilter] = useState<DepotFilter>("all");
  const [date, setDate] = useState(depotToday);
  // An issue Live asked to open; the Issues screen selects it.
  const [issueFocus, setIssueFocus] = useState<string | null>(null);
  const online = useOnline();
  const [folded, setFolded] = useFolded();

  const depots = depotsFor(depotFilter, scope);
  const label = scopeLabel(depotFilter, scope);
  // Overview and Vehicles are today's fleet.
  const today = depotToday();
  const fleet = useFleet(depots, today);

  // The sidebar's counts: orders that need a person, and stops that do. Only a
  // read that arrived is counted, so a failed read shows no badge, not a zero.
  const dayOrders = useOrders(depots, today);
  const dayLive = useLive(depots, today);
  const dayPlans = usePlans(depots, date);
  const planWord = dayPlans.data ? planBadge(dayPlans.data) : null;
  const badges: Badges = {
    orders: dayOrders.data ? flow(dayOrders.data).attention : 0,
    ...(planWord ? { plan: { text: planWord } } : {}),
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
    <DepotScopeProvider value={{ scope, filter: depotFilter, onFilter: setDepotFilter }}>
    <InboxProvider userId={userId}>
    <ToastProvider>
    <NotificationsPanel onNavigate={navigate} />
    <ThreadSheet online={online} />
    <div className="flex min-h-dvh w-full flex-col bg-go-canvas font-go text-go-ink lg:h-dvh lg:flex-row">
      <CompactNav view={view} onNavigate={navigate} depots={scope} depotFilter={depotFilter} onDepotFilter={setDepotFilter} badges={badges} displayName={displayName} />
      <Sidebar
        view={view}
        onNavigate={navigate}
        displayName={displayName}
        depots={scope}
        depotFilter={depotFilter}
        onDepotFilter={setDepotFilter}
        badges={badges}
        folded={folded}
        onFold={setFolded}
        scopeLabel={label}
      />
      <div ref={main} className="flex min-w-0 flex-1 flex-col gap-5 px-4 py-5 md:px-9 md:py-7 lg:overflow-y-auto">
        {scope.length === 0 ? (
          <p className="text-sm text-go-secondary">Your account has no depot in scope. Ask an administrator to grant one.</p>
        ) : view === "overview" ? (
          <Overview displayName={displayName} depots={depots} scope={scope} depotFilter={depotFilter} onDepotFilter={setDepotFilter} scopeLabel={label} fleet={fleet} online={online} onNavigate={navigate} onOpenPlan={openPlan} />
        ) : view === "vehicles" ? (
          <Vehicles depots={depots} scopeLabel={label} date={today} fleet={fleet} online={online} />
        ) : view === "orders" ? (
          <Orders depots={depots} scopeLabel={label} online={online} onNavigate={navigate} onOpenPlan={openPlan} />
        ) : view === "plan" ? (
          <Plan depots={depots} scope={scope} depotFilter={depotFilter} onDepotFilter={setDepotFilter} date={date} onDate={setDate} online={online} />
        ) : view === "live" ? (
          <Live
            depots={depots}
            scopeLabel={label}
            date={date}
            onDate={setDate}
            online={online}
            depotFilter={depotFilter}
            onDepotFilter={setDepotFilter}
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
    </ToastProvider>
    </InboxProvider>
    </DepotScopeProvider>
  );
}

/**
 * The word on Plan in the sidebar: a draft not yet sent for the day in view.
 * Nothing once every depot's plan is published; the server serves no publish
 * deadline, so the badge never shows a made-up "Due" time.
 */
function planBadge(plans: DepotPlans[]): string | null {
  const unsent = plans.filter((p) => p.draft !== null).length;
  if (unsent === 0) return null;
  return plans.some((p) => p.draft !== null && p.published !== null) ? "Not sent" : "Draft";
}
