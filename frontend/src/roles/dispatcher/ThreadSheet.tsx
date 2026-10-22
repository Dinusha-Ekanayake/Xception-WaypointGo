"use client";

import { useRef, useState } from "react";
import { request } from "@shared/api/client";
import { useResource } from "@shared/api/useResource";
import type { DecisionView, MessageView, ReplyRankingView, RunSheetView, ThreadView } from "@shared/domain/types";
import { rankReplies, replies } from "@shared/messaging/templates";
import { rankingNote } from "./data/replyRanking.ts";
import { TripThread, useOverlay } from "@shared/ui";
import { dayLabel } from "@shared/wording";
import { useDispatcherInbox, type OpenThread } from "./inbox.tsx";
import { EndTripButton, SkipStopButton, skippable } from "./screens/LiveTripSkip.tsx";
import ReportSuggestions from "./screens/ReportSuggestions.tsx";
import TellEachStore from "./screens/TellEachStore.tsx";
import TripOutlookChips, { problemOpen, useTripOutlook } from "./screens/TripOutlookChips.tsx";

// A trip's thread in a sheet on the right (issue #136), over whichever screen
// opened it: a warning sign on the timeline, the trip page, a notification.
// The dispatcher reads every message on it and writes to anyone on the trip, and
// decides here (R-EXE-31): Skip store beside a report about a stop still open,
// End trip in the header. Stores and the driver see only the outcome.

/** Reports about the vehicle rather than one stop (messaging report types). */
const VEHICLE_REPORTS = new Set(["vehicle_fault", "road_disruption", "late"]);

export default function ThreadSheet({ online }: { online: boolean }): React.JSX.Element | null {
  const ctx = useDispatcherInbox();
  const open = ctx?.thread ?? null;
  const onClose = () => ctx?.openThread(null);
  if (!open) return null;
  return <ThreadSheetPanel open={open} online={online} onClose={onClose} />;
}

/** Mounted only while a thread is open, so useOverlay's focus trap and Escape set up and tear down cleanly with it. */
function ThreadSheetPanel({
  open,
  online,
  onClose,
}: {
  open: OpenThread;
  online: boolean;
  onClose: () => void;
}): React.JSX.Element {
  const panel = useRef<HTMLDivElement>(null);
  const inbox = useDispatcherInbox();
  const { closing, requestClose } = useOverlay(panel, onClose);
  const threadId = open.threadId;
  const thread = useResource(
    (signal: AbortSignal) => request<ThreadView>(`/api/threads/${encodeURIComponent(threadId)}`, { signal }),
    `thread-head|${threadId}`,
  );
  const head = thread.data && thread.data.threadId === open.threadId ? thread.data : null;
  // FF-08: the trip's state, for the ready replies: its run sheet and the stores' answers today.
  const date = head?.serviceDate ?? null;
  const depot = head?.depotCode ?? null;
  // The depot's run sheets: without a depot the server answers with the reader's own vehicles, which
  // for a dispatcher is none, and the chat would offer no decision at all.
  const sheets = useResource(
    date && depot
      ? (signal: AbortSignal) =>
          request<RunSheetView[]>(`/api/execution/run-sheets?date=${encodeURIComponent(date)}&depot=${encodeURIComponent(depot)}`, { signal })
      : null,
    `thread-sheets|${depot}|${date}`,
    30_000,
    // A stop skipped here or recorded on the road changes the buttons at once (F-07b).
    head ? { depots: [head.depotCode], topics: ["delivery", "trip"] } : undefined,
  );
  const answers = useResource(
    date ? (signal: AbortSignal) => request<DecisionView[]>(`/api/issues/decisions?date=${encodeURIComponent(date)}`, { signal }) : null,
    `thread-decisions|${date}`,
    30_000,
  );
  // AI-J5: the order learned from which replies dispatchers take; the rules' order until there is enough.
  const ranking = useResource(
    (signal: AbortSignal) => request<ReplyRankingView>("/api/ml/reply-ranking", { signal }),
    "reply-ranking",
    30 * 60_000,
  );
  // One roll per open sheet, so the order does not change under the dispatcher's finger.
  const roll = useRef(Math.random());
  const sheet = (sheets.data ?? []).find((s) => s.vehicleId === head?.vehicleId) ?? null;
  const tripStops = sheet && head ? sheet.stops.filter((s) => s.tripId === head.subjectId) : [];
  const ready =
    head && date && tripStops.length > 0
      ? replies({
          stops: tripStops,
          decisions: (answers.data ?? []).filter((d) => d.tripId === head.subjectId),
          channelOpen: head.channelOpen,
          serviceDate: date,
        })
      : [];
  const ordered = rankReplies(ready, ranking.data ?? null, roll.current);
  const openStops = tripStops.filter(skippable);
  // The decision beside a report is about the stop it names, while that stop is still open.
  // Issue #269: the decisions most likely here as chips, then Skip itself for a stop still open.
  // A report about the vehicle (a breakdown, a blocked road, running late) names no stop: its chips are
  // asked about the next stop still open, and its word goes to everyone on the trip.
  const nextOpen = [...openStops].sort((x, y) => x.sequence - y.sequence)[0];
  // R-ML-34: the trip's outlook at the top covers its reports; a report's own chips stay only as the
  // fallback while the outlook cannot be read, so the same decision is never offered twice.
  const [extra, setExtra] = useState<number | null>(null);
  const outlook = useTripOutlook(head?.subjectId ?? null, head?.depotCode ?? null, online, extra);
  const covered = new Set((outlook?.signals ?? []).map((x) => x.messageId));
  // While a problem is open, its problem room is the one place to decide or send: the header's
  // End trip and Tell each store, and the ready replies, step aside until the trip is quiet again.
  const problem = problemOpen(outlook);
  const reportActions = (m: MessageView) => {
    const named = tripStops.find((s) => s.outletId === m.aboutOutletId);
    const wholeTrip = !named && m.reportType !== null && VEHICLE_REPORTS.has(m.reportType);
    const about = named ?? (wholeTrip ? nextOpen : undefined);
    const stop = openStops.find((s) => s.outletId === m.aboutOutletId);
    if (!about) return null;
    return (
      <div className="flex flex-col gap-2">
        {m.reportType && m.resolvedAt === null && !covered.has(m.messageId) && (
          <ReportSuggestions
            stop={about}
            report={m.reportType}
            openStops={openStops}
            vehicleId={head?.vehicleId ?? null}
            online={online}
            wholeTrip={wholeTrip}
            onDraft={(draft) => inbox?.openThread({ threadId: open.threadId, draft })}
          />
        )}
        {stop && <SkipStopButton stop={stop} online={online} inline />}
      </div>
    );
  };

  return (
    <div data-closing={closing || undefined} className="go-overlay fixed inset-0 z-50 flex justify-end" role="presentation">
      <button type="button" tabIndex={-1} aria-label="Close messages" onClick={requestClose} className="go-backdrop absolute inset-0 bg-black/20" />
      <section
        ref={panel}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-label="Trip messages"
        className="go-panel go-panel-drawer relative flex h-dvh w-full max-w-[520px] flex-col gap-3 overscroll-contain bg-go-card p-5 shadow-go-card outline-none md:p-6"
      >
        <header className="flex items-start justify-between gap-3">
          <div className="flex flex-col gap-0.5">
            <h2 className="text-[22px] font-medium text-go-ink">{head?.vehicleId ? `Messages · ${head.vehicleId}` : "Trip messages"}</h2>
            {head && (
              <p className="text-[13px] text-go-secondary">
                {head.serviceDate ? `${dayLabel(head.serviceDate)} · ` : ""}
                {head.outletIds.join(", ")}
              </p>
            )}
          </div>
          <button type="button" onClick={requestClose} className="min-h-9 rounded-full bg-go-surface px-3.5 text-sm font-medium text-go-teal">
            Close
          </button>
        </header>
        {head && openStops.length > 0 && !problem && (
          <div className="flex flex-col gap-2">
            <EndTripButton tripId={head.subjectId} open={openStops} online={online} inline />
            {date && <TellEachStore threadId={open.threadId} stops={tripStops} serviceDate={date} online={online} />}
          </div>
        )}
        {head && (
          <TripOutlookChips
            view={outlook}
            threadId={open.threadId}
            tripId={head.subjectId}
            vehicleId={head.vehicleId}
            stops={tripStops}
            decisions={(answers.data ?? []).filter((d) => d.tripId === head.subjectId)}
            online={online}
            onExtra={setExtra}
          />
        )}
        {!online && (
          <p role="status" className="rounded-go-card-s bg-go-warning-tint px-3.5 py-2.5 text-sm text-go-warning-text">
            Offline. Showing what arrived; writing needs a connection.
          </p>
        )}
        <TripThread
          // A new draft for the same thread (a suggestion's message) starts the composer again.
          key={`${open.threadId}|${open.draft?.body ?? ""}`}
          threadId={open.threadId}
          online={online}
          variant="desk"
          focusMessageId={open.messageId ?? null}
          {...(open.draft ? { draft: open.draft } : {})}
          {...(ordered.length > 0 && !problem ? { replies: ordered, repliesNote: rankingNote(ranking.data ?? null, ranking.error != null) } : {})}
          reportActions={reportActions}
        />
      </section>
    </div>
  );
}
