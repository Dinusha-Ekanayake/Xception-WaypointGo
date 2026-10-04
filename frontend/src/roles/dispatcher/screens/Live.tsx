"use client";

import { useEffect, useMemo, useState } from "react";
import { Notice } from "@shared/ui";
import PageHeader from "../PageHeader.tsx";
import { byUrgency, punctuality, vehicleDay } from "../data/live.ts";
import { closedOnTheirOwn, depotSummaries, filterRuns, needCards, runsOf, summaryText, type RunFilter } from "../data/liveDesk.ts";
import { dayLabel } from "../data/scope.ts";
import { useIssues, useLive, usePositions, useReports } from "../data/useDay.ts";
import { useDispatcherInbox } from "../inbox.tsx";
import DayPicker from "./DayTools.tsx";
import { Retry } from "./Orders.tsx";
import LiveMapView from "./LiveMap.tsx";
import LiveNeeds from "./LiveNeeds.tsx";
import { Toggle } from "./LiveParts.tsx";
import LiveTimeline, { RoadCard } from "./LiveTimeline.tsx";
import LiveTrip from "./LiveTrip.tsx";
import Refusal from "./Refusal.tsx";

// Figma "05 Live" (189:20983 needs you, 189:21127 timeline, 189:21358 map,
// 189:21943 trip). Run sheets are read every 30 seconds and positions every 15
// while the tab is visible and online; the header says when. The views share
// one join of run sheet, loading trip and position (data/liveDesk.ts), and the
// depot and status filters under the header choose what each shows.

type View = "needs" | "map" | "timeline";

export default function Live({
  depots,
  scopeLabel,
  date,
  onDate,
  online,
  onOpenIssue,
  depotFilter,
  onDepotFilter,
}: {
  depots: string[];
  scopeLabel: string;
  date: string;
  onDate: (date: string) => void;
  online: boolean;
  onOpenIssue: (issueId: string) => void;
  /** The sidebar's depot scope: the depot pills here read and set it, so there is one depot choice. */
  depotFilter?: string;
  onDepotFilter?: (filter: string) => void;
}): React.JSX.Element {
  const live = useLive(depots, date);
  const positions = usePositions(depots, date);
  const issues = useIssues(depots);
  const reports = useReports(depots, date);
  const inbox = useDispatcherInbox();
  const [now, setNow] = useState(() => new Date());
  const [view, setView] = useState<View>("needs");
  const [localDepot, setLocalDepot] = useState("all");
  const depot = depotFilter ?? localDepot;
  const setDepot = onDepotFilter ?? setLocalDepot;
  const [filter, setFilter] = useState<RunFilter>("all");
  const [trip, setTrip] = useState<string | null>(null);

  // "At risk" compares a window with the time now, so the time moves.
  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 30_000);
    return () => window.clearInterval(timer);
  }, []);

  const sheets = live.data?.sheets ?? [];
  const depotOf = live.data?.depotOf ?? {};
  const runs = useMemo(
    () => runsOf(byUrgency(sheets.map((s) => vehicleDay(s, now))), live.data?.dock ?? [], positions.data ?? [], live.data?.depotOf ?? {}, date, now),
    [sheets, live.data, positions.data, date, now],
  );
  const depotNames = [...new Set(Object.values(depotOf))].sort();
  const inDepot = filterRuns(runs, depot, "all").runs;
  const shown = filterRuns(runs, depot, filter);
  const depotIssues = (issues.data ?? []).filter((i) => depot === "all" || i.depotCode === depot);
  const cards = needCards(inDepot, depotIssues, date, now);
  const onTheRoad = inDepot.filter((r) => r.day.state !== "finished").length;
  const atDock = (live.data?.dock ?? []).filter((t) => t.releasedAt === null && (depot === "all" || depotOf[t.vehicleId] === depot)).length;
  const depotName = (run: { depot: string | null }) => (run.depot ? `${run.depot} depot` : "the depot");
  const opened = trip ? runs.find((r) => r.day.vehicleId === trip) ?? null : null;

  if (opened) {
    return (
      <LiveTrip
        run={opened}
        date={date}
        depotName={depotName(opened)}
        now={now}
        online={online}
        lastSyncedAt={live.loadedAt}
        onBack={() => setTrip(null)}
      />
    );
  }

  // The filter row shows only when it has a choice to offer; the day picker joins the header otherwise.
  const rowShown = depotNames.length > 1 || view !== "needs";
  const subtitle = !live.data
    ? "Loading"
    : view === "needs"
      ? `${cards.length} need you · ${closedOnTheirOwn(inDepot)} closed on their own · ${onTheRoad} on the road${atDock ? ` · ${atDock} at the dock` : ""}`
      : [`${onTheRoad} on the road`, ...(depot === "all" && depotNames.length > 1 ? depotSummaries(runs).map((s) => `${s.depot} ${s.onTheRoad}`) : [scopeLabel])].join(" · ");
  const punctual = punctuality(inDepot.map((r) => ({ vehicleId: r.day.vehicleId, serviceDate: date, stops: r.day.stops })));

  return (
    <>
      <PageHeader
        title={`Live · ${dayLabel(date)}`}
        subtitle={subtitle}
        online={online}
        lastSyncedAt={live.loadedAt}
        onSync={() => {
          live.refresh();
          positions.refresh();
          issues.refresh();
          reports.refresh();
        }}
        syncing={live.loading}
        tools={
          <span className="flex flex-wrap items-center gap-2.5">
          {!rowShown && <DayPicker warnNotToday date={date} onDate={onDate} />}
          <Toggle
            label="View"
            value={view}
            onChange={setView}
            options={[
              { value: "needs", label: `Needs you (${cards.length})` },
              { value: "map", label: "Map" },
              { value: "timeline", label: "Timeline" },
            ]}
          />
          </span>
        }
      />

      {rowShown && (
      <div className="flex w-full flex-wrap items-center gap-2.5">
        {depotNames.length > 1 && (
          <Toggle label="Depot" value={depot} onChange={setDepot} options={[{ value: "all", label: "Both" }, ...depotNames.map((d) => ({ value: d, label: d }))]} />
        )}
        {view !== "needs" && (
          <Toggle
            label="Status"
            value={filter}
            onChange={setFilter}
            options={[
              { value: "all", label: `All (${shown.inDepot})` },
              { value: "at-risk", label: `At risk (${shown.atRisk})` },
              { value: "offline", label: `Offline (${shown.offline})` },
            ]}
          />
        )}
        <span className="ml-auto">
          <DayPicker warnNotToday date={date} onDate={onDate} />
        </span>
      </div>
      )}

      {live.error && <Refusal error={live.error} what="the live view" action={<Retry onClick={live.refresh} />} />}
      {!online && <Notice tone="warning" title="Live updates are paused" />}

      {view === "needs" && (
        <LiveNeeds cards={cards} runs={inDepot} date={date} closedOnTheirOwn={closedOnTheirOwn(inDepot)} online={online} onOpenTrip={setTrip} onOpenIssue={onOpenIssue} onViewAll={() => setView("timeline")} />
      )}

      {view === "map" && <LiveMapView depots={depots} date={date} runs={shown.runs} positions={positions} depotName={depotName} now={now} onOpenTrip={setTrip} />}

      {view === "timeline" && (
        <div className="flex w-full items-start gap-[18px] max-lg:flex-col">
          <LiveTimeline
            runs={shown.runs}
            now={now}
            onOpen={setTrip}
            reports={reports.data ?? []}
            onReport={(mark) => inbox?.openThread({ threadId: mark.threadId, messageId: mark.messageId })}
          />
          <aside aria-label="Vehicles on the road" className="flex w-full flex-col gap-2.5 rounded-[24px] bg-white p-[18px] shadow-go-card lg:max-w-[360px]">
            <div className="flex items-baseline justify-between">
              <h2 className="text-[17px] font-medium text-go-ink">On the road</h2>
              <span className="text-[12px] text-go-secondary">most urgent first</span>
            </div>
            {shown.runs.length === 0 && <p className="py-6 text-center text-[13px] text-go-secondary">No vehicle matches on {dayLabel(date)}.</p>}
            {shown.runs.map((r) => (
              <RoadCard key={r.day.vehicleId} run={r} onSelect={() => setTrip(r.day.vehicleId)} />
            ))}
            {depot === "all" &&
              filter === "all" &&
              depotSummaries(runs).length > 1 &&
              depotSummaries(runs).map((s) => (
                <p key={s.depot} className="rounded-2xl bg-go-subtle px-3.5 py-2.5 text-[13px] text-go-ink">
                  {s.depot} · {summaryText(s)}
                </p>
              ))}
            {punctual.served > 0 && (
              <p className="mt-2 rounded-2xl bg-go-success-tint px-3.5 py-2.5 text-[13px] text-go-ink">
                <span className="block font-medium">{punctual.served} delivered so far</span>
                <span className="text-go-secondary">{Math.round((punctual.onTime / punctual.served) * 100)}% inside the window</span>
              </p>
            )}
          </aside>
        </div>
      )}
    </>
  );
}
