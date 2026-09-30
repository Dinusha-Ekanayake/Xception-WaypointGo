"use client";

import { Card, CardHead, Pending } from "@shared/ui";
import PageHeader from "../PageHeader.tsx";
import type { ViewId } from "../navigation.ts";

// Screens whose backend module is not built yet. Decision D-D puts role UIs
// behind their backend with no mocks, so each one says what it will do and
// what it is waiting for, instead of showing invented numbers.

type Upcoming = Exclude<ViewId, "overview" | "vehicles">;

const SCREENS: Record<Upcoming, { title: string; does: string[]; waitingOn: string }> = {
  orders: {
    title: "Orders",
    does: [
      "One queue of confirmed orders for a depot and day, stock-unknown orders marked as degraded",
      "Close orders for the day, with the cutoff shown",
    ],
    waitingOn: "the Ordering module (#8)",
  },
  plan: {
    title: "Plan",
    does: [
      "Generate a draft: every order served, deferred or unservable, with its binding constraint and slack",
      "Trips per vehicle with temperature, brand, district, load bars and time budget",
      "Move orders with instant revalidation, defer with a reason, publish once the gate passes",
      "Compare plan versions, and show a stale draft as a diff",
    ],
    waitingOn: "the Planning module (#9)",
  },
  live: {
    title: "Live",
    does: [
      "Trip and stop progress, ETA shifts and lateness",
      "Trips at risk and offline drivers, raised before they become late deliveries",
    ],
    waitingOn: "the Execution module (#12)",
  },
  forecast: {
    title: "Forecast",
    does: ["Demand forecast per brand and week, and refrigerated vehicles needed against available"],
    waitingOn: "the forecast (#16)",
  },
  issues: {
    title: "Issues",
    does: ["Assign and resolve issues, record replacements and schedule redelivery"],
    waitingOn: "the Issues module (#13)",
  },
};

export default function UpcomingScreen({
  view,
  scopeLabel,
  online,
  lastSyncedAt,
}: {
  view: Upcoming;
  scopeLabel: string;
  online: boolean;
  lastSyncedAt: Date | null;
}): React.JSX.Element {
  const screen = SCREENS[view];
  return (
    <>
      <PageHeader title={screen.title} subtitle={scopeLabel} online={online} lastSyncedAt={lastSyncedAt} />
      <Card label={screen.title} className="w-full max-w-[760px]">
        <CardHead title="What this screen will do" />
        <ul className="flex list-disc flex-col gap-1.5 pl-5 text-[13px] text-go-ink">
          {screen.does.map((line) => (
            <li key={line}>{line}</li>
          ))}
        </ul>
        <Pending what={screen.title} waitingOn={screen.waitingOn} />
      </Card>
    </>
  );
}

export type { Upcoming };
