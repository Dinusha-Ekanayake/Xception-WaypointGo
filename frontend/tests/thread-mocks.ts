import type { MemberRole, MemberView, MessageView, PostMessagePayload, ReportMarkView, ThreadView } from "../src/shared/domain/messaging.ts";

// Issue #136: a trip's thread as each role's browser suite mocks it. The
// suites' own mocks.ts route /api/threads/* here and apply message:Post here.

export type ThreadMock = { thread: ThreadView; members: MemberView[]; messages: MessageView[] };

const ROLE_MEMBERS: Record<MemberRole, (outlets: string[]) => MemberView[]> = {
  dispatcher: (outlets) => [
    { to: "driver", outletId: null, label: "Dilan R. (DRV-00133)" },
    { to: "loader", outletId: null, label: "Loaders at Kandy" },
    ...outlets.map((o) => ({ to: "outlet" as const, outletId: o, label: o })),
    { to: "all", outletId: null, label: "Everyone on the trip" },
  ],
  driver: (outlets) => [{ to: "dispatch", outletId: null, label: "Dispatcher" }, ...outlets.map((o) => ({ to: "outlet" as const, outletId: o, label: o }))],
  loader: () => [{ to: "dispatch", outletId: null, label: "Dispatcher" }],
  store_manager: () => [{ to: "dispatch", outletId: null, label: "Dispatcher" }],
};

export function tripThread(
  tripId: string,
  vehicleId: string,
  outlets: string[],
  role: MemberRole,
  messages: MessageView[] = [],
): ThreadMock {
  const threadId = `thread-${tripId}`;
  return {
    thread: {
      threadId, subjectType: "trip", subjectId: tripId, depotCode: "Kandy", vehicleId, serviceDate: "2027-03-01",
      outletIds: outlets, memberRole: role, myOutlets: role === "store_manager" ? outlets.slice(0, 1) : [], open: true,
    },
    members: ROLE_MEMBERS[role](outlets),
    messages: messages.map((m) => ({ ...m, threadId })),
  };
}

export function said(id: string, at: string, over: Partial<MessageView> = {}): MessageView {
  return {
    messageId: id, threadId: "", authorName: "Dinusha Bawantha", authorRole: "dispatcher", kind: "message", reportType: null,
    audience: "all", audienceOutlet: null, body: id, voiceNoteId: null, voiceDurationMs: null, createdAt: at, mine: false, ...over,
  };
}

/** The report marks of every thread, for the dispatcher's timeline. */
export function marks(threads: ThreadMock[]): ReportMarkView[] {
  return threads.flatMap((t) =>
    t.messages
      .filter((m) => m.kind === "report")
      .map((m) => ({
        threadId: t.thread.threadId, tripId: t.thread.subjectId, vehicleId: t.thread.vehicleId, messageId: m.messageId, at: m.createdAt,
        reportType: m.reportType ?? "other", authorRole: m.authorRole, excerpt: m.body, voice: m.voiceNoteId !== null,
      })),
  );
}

/** The answer to a GET under /api/threads, or undefined when the path is not one. */
export function threadRead(threads: ThreadMock[], url: URL): { status: number; body: unknown } | undefined {
  const { pathname, searchParams } = url;
  if (!pathname.startsWith("/api/threads")) return undefined;
  if (pathname === "/api/threads/by-subject") {
    const found = threads.find((t) => t.thread.subjectId === searchParams.get("id"));
    return found ? { status: 200, body: found.thread } : { status: 404, body: { title: "NOT_FOUND", status: 404, code: "NOT_FOUND", detail: "No thread" } };
  }
  if (pathname === "/api/threads/reports") return { status: 200, body: marks(threads) };
  const [, , , id, part] = pathname.split("/");
  const found = threads.find((t) => t.thread.threadId === id);
  if (!found) return { status: 404, body: { title: "NOT_FOUND", status: 404, code: "NOT_FOUND", detail: "No thread" } };
  if (part === "members") return { status: 200, body: found.members };
  if (part === "messages") return { status: 200, body: { items: [...found.messages].reverse(), nextCursor: null } };
  return { status: 200, body: found.thread };
}

/** Applies message:Post as the server would for the writer, mine and newest. */
export function postToThread(threads: ThreadMock[], payload: PostMessagePayload, author: { name: string; role: MemberRole }, at: string): unknown {
  const found = threads.find((t) => t.thread.threadId === payload.threadId);
  if (!found) return {};
  const messageId = `posted-${found.messages.length + 1}`;
  found.messages.push({
    messageId, threadId: payload.threadId, authorName: author.name, authorRole: author.role, kind: payload.report ? "report" : "message",
    reportType: payload.report ?? null, audience: payload.to, audienceOutlet: payload.outletId ?? null, body: payload.body,
    voiceNoteId: payload.voiceNoteId ?? null, voiceDurationMs: null, createdAt: at, mine: true,
  });
  return { messageId, threadId: payload.threadId, alreadySent: false };
}
