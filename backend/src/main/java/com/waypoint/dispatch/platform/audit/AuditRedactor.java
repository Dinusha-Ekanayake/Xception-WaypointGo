package com.waypoint.dispatch.platform.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Replaces personal and secret fields before a snapshot is written to the audit
 * log.
 *
 * <p>The audit log is append only and kept for years, so whatever lands there
 * cannot be taken back. A snapshot is therefore redacted on the way in, by field
 * name, at any depth, and the value is replaced rather than masked: a masked
 * email still says which domain, and a masked PIN still says how long it is.
 *
 * <p>The rule is deliberately a deny-list of name fragments and errs towards
 * redacting too much. A field wrongly hidden costs an auditor one lookup; a field
 * wrongly kept costs a person their privacy for good. Pure, so the rule is tested
 * without a database.
 */
public final class AuditRedactor {
  public static final String REDACTED = "[redacted]";

  /** Matched against the lower-cased field name, anywhere in it. */
  private static final Set<String> FRAGMENTS =
      Set.of(
          "password",
          "passcode",
          "pin",
          "secret",
          "token",
          "credential",
          "email",
          "phone",
          "mobile",
          "address",
          "nic",
          "passport",
          "licence",
          "license",
          "signature",
          "displayname",
          "fullname",
          "contactname",
          "recipient",
          "latitude",
          "longitude");

  /**
   * Fields that contain a fragment and carry nothing personal. Kept short and
   * exact: every entry is a field someone looked at and decided is safe.
   */
  private static final Set<String> ALLOWED =
      Set.of("pinned", "pinnedat", "pinattempts", "tokenstatus", "addressedat");

  private AuditRedactor() {}

  public static JsonNode redact(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return node;
    }
    if (node.isObject()) {
      ObjectNode copy = JsonNodeFactory.instance.objectNode();
      Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        copy.set(
            field.getKey(),
            sensitive(field.getKey())
                ? JsonNodeFactory.instance.textNode(REDACTED)
                : redact(field.getValue()));
      }
      return copy;
    }
    if (node.isArray()) {
      ArrayNode copy = JsonNodeFactory.instance.arrayNode();
      node.forEach(element -> copy.add(redact(element)));
      return copy;
    }
    return node;
  }

  static boolean sensitive(String fieldName) {
    String name = fieldName.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    if (name.equals("points")) return true;
    if (ALLOWED.contains(name)) {
      return false;
    }
    return FRAGMENTS.stream().anyMatch(name::contains);
  }
}
