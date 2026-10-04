package com.waypoint.dispatch.identity.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.identity.domain.policy.Condition;
import com.waypoint.dispatch.identity.domain.policy.ConditionOperator;
import com.waypoint.dispatch.identity.domain.policy.Effect;
import com.waypoint.dispatch.identity.domain.policy.Pattern;
import com.waypoint.dispatch.identity.domain.policy.PolicyDocument;
import com.waypoint.dispatch.identity.domain.policy.Statement;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Turns a stored JSON policy into domain statements.
 *
 * <p>Parsing lives here rather than in the domain because the domain carries no
 * Jackson. It is also where a malformed document is rejected: a policy that
 * cannot be understood must fail loudly at authoring time, never be skipped at
 * evaluation time, since a silently ignored statement is a silent grant or a
 * silent denial depending on which one it was.
 */
@Component
public class PolicyDocumentParser {
  private final ObjectMapper mapper;

  public PolicyDocumentParser(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public PolicyDocument parse(String json) {
    try {
      return parse(mapper.readTree(json));
    } catch (DomainException e) {
      throw e;
    } catch (Exception e) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "Policy is not valid JSON");
    }
  }

  public PolicyDocument parse(JsonNode root) {
    JsonNode statements = root.path("Statement");
    if (!statements.isArray() || statements.isEmpty()) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "A policy needs at least one Statement");
    }
    List<Statement> parsed = new ArrayList<>();
    for (JsonNode node : statements) {
      parsed.add(statement(node));
    }
    return new PolicyDocument(root.path("Version").asText("unversioned"), parsed);
  }

  private Statement statement(JsonNode node) {
    String sid = node.path("Sid").asText("");
    if (sid.isBlank()) {
      // Without a Sid a denial cannot name what denied it, which is the whole
      // reason denials carry an explanation.
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "Every Statement needs a Sid");
    }
    String effect = node.path("Effect").asText("");
    if (effect.isBlank()) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "Statement " + sid + " has no Effect");
    }
    List<Pattern> actions = patterns(node.path("Action"));
    if (actions.isEmpty()) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "Statement " + sid + " names no Action");
    }
    try {
      return new Statement(
          sid,
          Effect.parse(effect),
          actions,
          patterns(node.path("Resource")),
          conditions(node.path("Condition"), sid));
    } catch (IllegalArgumentException e) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "Statement " + sid + ": " + e.getMessage());
    }
  }

  /** Accepts either a single string or an array, as policy authors write both. */
  private static List<Pattern> patterns(JsonNode node) {
    List<Pattern> out = new ArrayList<>();
    if (node.isTextual()) {
      out.add(new Pattern(node.asText()));
    } else if (node.isArray()) {
      node.forEach(n -> out.add(new Pattern(n.asText())));
    }
    return out;
  }

  private static List<Condition> conditions(JsonNode node, String sid) {
    List<Condition> out = new ArrayList<>();
    if (!node.isObject()) {
      return out;
    }
    Iterator<Map.Entry<String, JsonNode>> operators = node.fields();
    while (operators.hasNext()) {
      Map.Entry<String, JsonNode> operatorEntry = operators.next();
      ConditionOperator operator;
      try {
        operator = ConditionOperator.parse(operatorEntry.getKey());
      } catch (IllegalArgumentException e) {
        throw new DomainException(
            ErrorCode.VALIDATION_FAILED,
            "Statement " + sid + ": " + e.getMessage());
      }
      Iterator<Map.Entry<String, JsonNode>> keys = operatorEntry.getValue().fields();
      while (keys.hasNext()) {
        Map.Entry<String, JsonNode> keyEntry = keys.next();
        List<String> values = new ArrayList<>();
        if (keyEntry.getValue().isArray()) {
          keyEntry.getValue().forEach(v -> values.add(v.asText()));
        } else {
          values.add(keyEntry.getValue().asText());
        }
        out.add(new Condition(operator, keyEntry.getKey(), values));
      }
    }
    return out;
  }
}
