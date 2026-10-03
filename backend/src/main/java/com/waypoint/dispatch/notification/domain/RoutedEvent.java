package com.waypoint.dispatch.notification.domain;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What the routing policy needs to know about one event, with nothing of the
 * event's own type left: the facts its templates may name, and where it
 * happened.
 *
 * @param actorId who caused it, never told about their own action (R-NOT-07)
 * @param serviceDate the day a vehicle scope is resolved on, so tomorrow's plan
 *     reaches tomorrow's driver; empty means today
 * @param facts template values shared by every target
 * @param targets where the event happened, one per outlet, depot or vehicle it concerns
 */
public record RoutedEvent(
    UUID eventId,
    String eventType,
    Optional<UUID> actorId,
    Optional<LocalDate> serviceDate,
    Map<String, String> facts,
    List<Target> targets) {

  public RoutedEvent {
    facts = Map.copyOf(facts);
    targets = List.copyOf(targets);
  }

  /** What a notification links to, so the UI can open it. */
  public record Subject(String type, String id) {}

  /**
   * One place the event concerns.
   *
   * @param key distinguishes two targets of one event in the same scope, for
   *     example two trips of one vehicle in a plan
   * @param facts template values for this target only, over the event's
   */
  public record Target(
      ScopeKind scope, String scopeId, String key, Map<String, String> facts, Optional<Subject> subject) {

    public Target {
      facts = Map.copyOf(facts);
    }

    public static Target of(ScopeKind scope, String scopeId, Optional<Subject> subject) {
      return new Target(scope, scopeId, scope.code() + ":" + scopeId, Map.of(), subject);
    }
  }

  Map<String, String> factsFor(Target target) {
    Map<String, String> merged = new HashMap<>(facts);
    merged.putAll(target.facts());
    return merged;
  }
}
