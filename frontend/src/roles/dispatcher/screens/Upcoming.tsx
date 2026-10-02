"use client";

import { Card, CardHead, Pending } from "@shared/ui";
import PageHeader from "../PageHeader.tsx";
import type { ViewId } from "../navigation.ts";

// Screens whose backend module is not built yet. Decision D-D puts role UIs
// behind their backend with no mocks, so each one says what it will do and
// what it is waiting for, instead of showing invented numbers.

type Upcoming = Exclude<ViewId, "overview" | "orders" | "plan" | "live" | "vehicles" | "issues">;

const SCREENS: Record<Upcoming, { title: string; does: string[]; waitingOn: string }> = {
  forecast: {
    title: "Forecast",
    does: ["Demand forecast per brand and week, and refrigerated vehicles needed against available"],
    waitingOn: "the forecast (#16)",
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
