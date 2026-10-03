package com.waypoint.dispatch.notification.domain;

import com.waypoint.dispatch.notification.domain.RoutedEvent.Target;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Who hears about an event, and what they are told: the routing matrix applied
 * to one event.
 *
 * <p>Who holds a role where is Identity's answer, passed in as {@link Recipients}
 * so this decides without a database. The rules it applies on top of the table:
 *
 * <ul>
 *   <li>the person who caused the event is not told about it (R-NOT-07);
 *   <li>a driver is pushed only trip-level events, which arrive before departure;
 *       anything else reaches a driver in the inbox only (R-NOT-08);
 *   <li>a fallback rule applies only when the role it stands in for reached
 *       nobody (LOD-05);
 *   <li>one person hears about one target of one event once, however many rules
 *       would reach them.
 * </ul>
 */
public final class NotificationPolicy {

  /** The events a driver is pushed (R-NOT-08). All of them happen before departure. */
  public static final Set<String> DRIVER_PUSH_EVENTS =
      Set.of("plan.published", "plan.revised", "trip.released");

  private NotificationPolicy() {}

  /** Identity's answer to "who holds this role here", on a date for a vehicle. */
  @FunctionalInterface
  public interface Recipients {
    List<UUID> of(String role, ScopeKind scope, String scopeId, Optional<LocalDate> on);
  }

  /** One notification to write. */
  public record Addressed(
      UUID recipient,
      String role,
      Target target,
      boolean push,
      String title,
      String body,
      Set<String> missingFacts) {

    public Addressed {
      missingFacts = Set.copyOf(missingFacts);
    }
  }

  /** A rule whose scope holds nobody in that role: counted, never thrown (NOT-02). */
  public record Unrouted(String role, ScopeKind scope, String scopeId) {}

  public record Routing(List<Addressed> notifications, List<Unrouted> unrouted) {
    public Routing {
      notifications = List.copyOf(notifications);
      unrouted = List.copyOf(unrouted);
    }
  }

  public static Routing route(RoutedEvent event, RoutingTable table, Recipients recipients) {
    List<RoutingRule> rules = table.rulesFor(event.eventType());
    List<Addressed> out = new ArrayList<>();
    List<Unrouted> unrouted = new ArrayList<>();
    Set<String> reachedRoles = new HashSet<>();
    Set<String> seen = new HashSet<>();

    for (RoutingRule rule : rules) {
      if (rule.onlyIfNoneFor().isEmpty()) {
        apply(rule, event, recipients, out, unrouted, reachedRoles, seen);
      }
    }
    for (RoutingRule rule : rules) {
      if (rule.onlyIfNoneFor().isPresent() && !reachedRoles.contains(rule.onlyIfNoneFor().get())) {
        apply(rule, event, recipients, out, unrouted, reachedRoles, seen);
      }
    }
    return new Routing(out, unrouted);
  }

  private static void apply(
      RoutingRule rule,
      RoutedEvent event,
      Recipients recipients,
      List<Addressed> out,
      List<Unrouted> unrouted,
      Set<String> reachedRoles,
      Set<String> seen) {
    for (Target target : event.targets()) {
      if (target.scope() != rule.scope()) {
        continue;
      }
      Map<String, String> facts = event.factsFor(target);
      if (!rule.matches(facts)) {
        continue;
      }
      List<UUID> found = recipients.of(rule.role(), rule.scope(), target.scopeId(), event.serviceDate());
      if (found.isEmpty()) {
        unrouted.add(new Unrouted(rule.role(), rule.scope(), target.scopeId()));
        continue;
      }
      reachedRoles.add(rule.role());
      Template.Rendered title = Template.render(rule.titleTemplate(), facts);
      Template.Rendered body = Template.render(rule.bodyTemplate(), facts);
      Set<String> missing = new LinkedHashSet<>(title.missing());
      missing.addAll(body.missing());
      boolean push = rule.push() && (!"driver".equals(rule.role()) || DRIVER_PUSH_EVENTS.contains(event.eventType()));
      for (UUID person : new LinkedHashSet<>(found)) {
        if (event.actorId().filter(person::equals).isPresent()) {
          continue;
        }
        if (seen.add(person + "|" + target.key())) {
          out.add(new Addressed(person, rule.role(), target, push, title.text(), body.text(), missing));
        }
      }
    }
  }
}
