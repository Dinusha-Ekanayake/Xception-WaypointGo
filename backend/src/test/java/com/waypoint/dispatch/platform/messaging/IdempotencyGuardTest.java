package com.waypoint.dispatch.platform.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The contract every write depends on: networks retry and offline devices
 * replay, so the same command id will arrive more than once.
 */
class IdempotencyGuardTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final IdempotencyGuard guard = new IdempotencyGuard(mapper);

  @Test
  void keyOrderDoesNotChangeTheFingerprint() {
    var a = mapper.createObjectNode().put("outlet", "OUT001").put("units", 4);
    var b = mapper.createObjectNode().put("units", 4).put("outlet", "OUT001");

    assertEquals(
        guard.fingerprint("order:Place", a),
        guard.fingerprint("order:Place", b),
        "the same payload written in a different key order is the same command");
  }

  @Test
  void differentPayloadsProduceDifferentFingerprints() {
    var a = mapper.createObjectNode().put("units", 4);
    var b = mapper.createObjectNode().put("units", 5);

    assertNotEquals(guard.fingerprint("order:Place", a), guard.fingerprint("order:Place", b));
  }

  @Test
  void theSameKindMattersToo() {
    var payload = mapper.createObjectNode().put("units", 4);

    assertNotEquals(
        guard.fingerprint("order:Place", payload), guard.fingerprint("order:Amend", payload));
  }

  @Test
  void aFirstAttemptHasNothingToReplay() {
    assertTrue(guard.replayOrReject(UUID.randomUUID(), "hash", null).isEmpty());
  }

  @Test
  void aGenuineReplayReturnsTheStoredResult() {
    Optional<Object> replay =
        guard.replayOrReject(
            UUID.randomUUID(),
            "hash-1",
            Map.of("payload_hash", "hash-1", "result_body", "{\"orderId\":\"ORD1\"}"));

    assertEquals("{\"orderId\":\"ORD1\"}", replay.orElseThrow());
  }

  @Test
  void theSameIdWithADifferentPayloadIsRejected() {
    UUID commandId = UUID.randomUUID();

    DomainException thrown =
        assertThrows(
            DomainException.class,
            () ->
                guard.replayOrReject(
                    commandId, "hash-2", Map.of("payload_hash", "hash-1", "result_body", "{}")),
            "applying the second version silently would discard the first");

    assertEquals(ErrorCode.CONFLICT, thrown.code());
    assertTrue(thrown.getMessage().contains(commandId.toString()));
  }
}
