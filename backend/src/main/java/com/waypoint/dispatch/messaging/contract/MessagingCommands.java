package com.waypoint.dispatch.messaging.contract;

/**
 * The messaging actions (issue #136). Writing is a command through
 * {@code POST /api/commands}; reading is {@code /api/threads}.
 *
 * <p>{@code message:Post} payload:
 *
 * <pre>
 * { "threadId": uuid,
 *   "body": "1 to 1000 characters",
 *   "to": "dispatch" | "driver" | "loader" | "outlet" | "all",
 *   "outletId": "OUT063",            only with to = outlet
 *   "report": "loading_shortfall",   optional: a report, always to the dispatcher
 *   "voiceNoteId": uuid,             optional: audio uploaded first to
 *                                    PUT /api/threads/{threadId}/voice/{voiceNoteId}; then body may be empty
 *   "clientMessageId": uuid }        optional: the phone's own id for the message
 * </pre>
 */
public final class MessagingCommands {
  public static final String POST = "message:Post";
  public static final String READ = "message:Read";

  private MessagingCommands() {}
}
