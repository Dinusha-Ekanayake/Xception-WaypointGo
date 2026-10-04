package com.waypoint.dispatch.platform.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Makes every write safe to retry.
 *
 * <p>Networks retry and offline devices replay, so the same command id will
 * arrive more than once. Same id and same payload returns the original response.
 * Same id with a different payload is a client bug and is rejected, because
 * silently applying the second version would discard the first.
 *
 * <p>The fingerprint is taken over the canonical JSON of the payload, so key
 * ordering does not produce a false mismatch.
 */
@Component
public class IdempotencyGuard {
  private final ObjectMapper mapper;

  public IdempotencyGuard(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public String fingerprint(String kind, JsonNode payload) {
    try {
      Object canonical = mapper.treeToValue(payload == null ? mapper.createObjectNode() : payload, Object.class);
      String canonicalJson =
          mapper
              .writerFor(Object.class)
              .with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
              .writeValueAsString(canonical);
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(digest.digest((kind + "|" + canonicalJson).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("Could not fingerprint command payload", e);
    }
  }

  /**
   * Compares an incoming command against a stored receipt.
   *
   * @return the stored response when this is a genuine replay
   * @throws DomainException when the same id arrives with a different payload
   */
  public Optional<Object> replayOrReject(
      UUID commandId, String fingerprint, Map<String, Object> receipt) {
    if (receipt == null) {
      return Optional.empty();
    }
    String stored = String.valueOf(receipt.get("payload_hash"));
    if (!stored.equals(fingerprint)) {
      throw new DomainException(
          ErrorCode.CONFLICT,
          "Command " + commandId + " was already used with a different payload");
    }
    return Optional.ofNullable(receipt.get("result_body"));
  }
}
