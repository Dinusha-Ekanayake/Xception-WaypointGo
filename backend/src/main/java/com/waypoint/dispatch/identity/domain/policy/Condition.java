package com.waypoint.dispatch.identity.domain.policy;

import java.util.Map;

/**
 * One condition clause: an operator, a context key, and the values that satisfy
 * it. Several values for one key are an OR; separate clauses are an AND.
 */
public record Condition(ConditionOperator operator, String contextKey, java.util.List<String> values) {

  public boolean isSatisfiedBy(Map<String, String> context) {
    String actual = context.get(contextKey);
    return values.stream().anyMatch(expected -> operator.test(actual, expected));
  }

  @Override
  public String toString() {
    return operator.wireName() + "(" + contextKey + ")";
  }
}
