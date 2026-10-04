package com.waypoint.dispatch.platform.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * One business decision, in one envelope.
 *
 * <p>Mirrors `frontend/src/shared/api/commands.ts` exactly, because a device
 * that queued this while offline will replay it verbatim.
 *
 * @param commandId client generated, so an offline device can mint one with no
 *     server round trip. The idempotency key.
 * @param kind which handler runs it
 * @param expectedVersion the aggregate version the caller believed it was
 *     changing. Null only for commands that create something.
 * @param payload the command's own fields
 * @param clientRecordedAt when the device recorded it. Kept for forensics only:
 *     the server clock decides.
 */
public record Command(
    UUID commandId,
    String kind,
    Long expectedVersion,
    JsonNode payload,
    Instant clientRecordedAt,
    UUID actingUserId) {

  /** Preserve the constructor used by existing non-loader command producers. */
  public Command(UUID commandId, String kind, Long expectedVersion, JsonNode payload, Instant clientRecordedAt) {
    this(commandId, kind, expectedVersion, payload, clientRecordedAt, null);
  }
}
