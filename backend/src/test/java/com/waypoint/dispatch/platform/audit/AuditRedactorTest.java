package com.waypoint.dispatch.platform.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AuditRedactorTest {
  private final ObjectMapper mapper = new ObjectMapper();

  private JsonNode json(String text) throws Exception {
    return mapper.readTree(text);
  }

  @Test
  void locationTrailsAndNestedCoordinatesNeverReachAuditSnapshots() throws Exception {
    JsonNode original = json("""
        {"vehicleId":"VEH001","points":[{"latitude":7.123456,"longitude":80.654321,
        "recordedAt":"2026-10-03T06:00:00Z"}],"result":{"latitude":7.123456,"longitude":80.654321}}
        """);
    JsonNode redacted = AuditRedactor.redact(original);
    assertEquals("VEH001", redacted.get("vehicleId").asText());
    assertEquals(AuditRedactor.REDACTED, redacted.get("points").asText());
    assertEquals(AuditRedactor.REDACTED, redacted.at("/result/latitude").asText());
    assertEquals(AuditRedactor.REDACTED, redacted.at("/result/longitude").asText());
    assertFalse(redacted.toString().contains("7.123456"));
    assertTrue(original.get("points").isArray());
  }

  @Test
  void personalAndSecretFieldsAreReplacedAtAnyDepth() throws Exception {
    JsonNode redacted =
        AuditRedactor.redact(
            json(
                """
                {"status":"ok","email":"a@b.lk","user":{"displayName":"Nimal","PIN":"1234",
                 "contacts":[{"phone":"0771234567","note":"gate 2"}]},"password":"x"}
                """));

    assertEquals("ok", redacted.get("status").asText());
    assertEquals(AuditRedactor.REDACTED, redacted.get("email").asText());
    assertEquals(AuditRedactor.REDACTED, redacted.get("password").asText());
    assertEquals(AuditRedactor.REDACTED, redacted.at("/user/displayName").asText());
    assertEquals(AuditRedactor.REDACTED, redacted.at("/user/PIN").asText());
    assertEquals(AuditRedactor.REDACTED, redacted.at("/user/contacts/0/phone").asText());
    assertEquals("gate 2", redacted.at("/user/contacts/0/note").asText(), "other fields survive");
  }

  @Test
  void aValueIsReplacedNotMaskedSoItsShapeLeaksNothing() throws Exception {
    JsonNode redacted = AuditRedactor.redact(json("{\"sessionToken\":\"abcdef0123456789\"}"));

    assertEquals(AuditRedactor.REDACTED, redacted.get("sessionToken").asText());
    assertFalse(redacted.toString().contains("abcdef"));
  }

  @Test
  void nameVariantsAreCaughtWhateverTheirCaseOrSeparator() throws Exception {
    JsonNode redacted =
        AuditRedactor.redact(
            json("{\"E_MAIL\":\"x\",\"Phone-Number\":\"x\",\"deliveryAddress\":\"x\",\"api_token\":\"x\"}"));

    redacted.fields().forEachRemaining(e -> assertEquals(AuditRedactor.REDACTED, e.getValue().asText(), e.getKey()));
  }

  @Test
  void aKnownSafeFieldIsKeptEvenThoughItsNameContainsAFragment() throws Exception {
    JsonNode redacted = AuditRedactor.redact(json("{\"pinned\":true,\"quantity\":3}"));

    assertTrue(redacted.get("pinned").asBoolean());
    assertEquals(3, redacted.get("quantity").asInt());
  }

  @Test
  void theInputIsNeverModified() throws Exception {
    JsonNode original = json("{\"email\":\"a@b.lk\"}");

    AuditRedactor.redact(original);

    assertEquals("a@b.lk", original.get("email").asText());
  }

  @Test
  void scalarsAndNullsPassThrough() throws Exception {
    JsonNode scalar = json("42");
    assertSame(scalar, AuditRedactor.redact(scalar));
    assertEquals(null, AuditRedactor.redact(null));
  }
}
