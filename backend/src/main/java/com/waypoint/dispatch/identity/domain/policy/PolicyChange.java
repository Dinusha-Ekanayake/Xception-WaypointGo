package com.waypoint.dispatch.identity.domain.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What a new version of one policy would change for someone (the permission
 * editor's "What changes", R-IAM-42). Pure: every catalogue action is decided
 * with the person's other statements plus the policy's current version, then
 * plus the draft, against each resource any of the statements name. An action
 * allowed only after is gained, allowed only before is lost, and allowed both
 * times but on different resources has its reach changed. Conditions are not
 * known ahead of a request, so a statement with one is decided as if it held.
 */
public final class PolicyChange {
  private PolicyChange() {}

  public record Delta(List<String> gained, List<String> lost, List<String> reachChanged) {
    public boolean none() {
      return gained.isEmpty() && lost.isEmpty() && reachChanged.isEmpty();
    }
  }

  public static Delta compare(
      List<Statement> others, List<Statement> before, List<Statement> after, List<String> catalogue) {
    Set<String> probes = new TreeSet<>(List.of("*"));
    for (List<Statement> group : List.of(others, before, after)) {
      for (Statement s : group) s.resources().forEach(r -> probes.add(r.raw()));
    }
    List<Statement> was = withConditionsHeld(join(others, before));
    List<Statement> will = withConditionsHeld(join(others, after));
    List<String> gained = new ArrayList<>();
    List<String> lost = new ArrayList<>();
    List<String> reach = new ArrayList<>();
    for (String action : new TreeSet<>(catalogue)) {
      Set<String> wasAllowed = allowed(was, action, probes);
      Set<String> willAllow = allowed(will, action, probes);
      if (wasAllowed.isEmpty() && !willAllow.isEmpty()) gained.add(action);
      else if (!wasAllowed.isEmpty() && willAllow.isEmpty()) lost.add(action);
      else if (!wasAllowed.equals(willAllow)) reach.add(action);
    }
    return new Delta(List.copyOf(gained), List.copyOf(lost), List.copyOf(reach));
  }

  private static Set<String> allowed(List<Statement> statements, String action, Set<String> probes) {
    Set<String> out = new TreeSet<>();
    for (String resource : probes) {
      if (PolicyEvaluator.evaluate(statements, new AccessRequest(action, resource, Map.of())).allowed()) out.add(resource);
    }
    return out;
  }

  private static List<Statement> join(List<Statement> a, List<Statement> b) {
    List<Statement> out = new ArrayList<>(a);
    out.addAll(b);
    return out;
  }

  /** A condition depends on the request; for a preview each is taken to hold. */
  private static List<Statement> withConditionsHeld(List<Statement> statements) {
    return statements.stream()
        .map(s -> new Statement(s.sid(), s.effect(), s.actions(), s.resources(), List.of()))
        .toList();
  }
}
