package com.waypoint.dispatch.planning.domain;

import static com.waypoint.dispatch.planning.domain.PlanningFixtures.CONTEXT;
import static com.waypoint.dispatch.planning.domain.PlanningFixtures.order;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.waypoint.dispatch.shared.error.DomainException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PriorityPolicyTest {
  static final PriorityPolicy DEFAULT = new PriorityPolicy(UUID.randomUUID(), PriorityPolicy.DEFAULT_KEYS);

  @Test
  void priorSkipOutranksFreshAndFreshOutranksStyle() {
    PlanOrder style = order().brand("Style").dock("rear_dock").ref("A").build();
    PlanOrder fresh = order().ref("B").build();
    PlanOrder skippedStyle = order().brand("Style").dock("rear_dock").deferrals(1).ref("C").build();

    assertEquals(List.of(skippedStyle, fresh, style), DEFAULT.rank(List.of(style, fresh, skippedStyle), CONTEXT));
  }

  @Test
  void chilledBeforeAmbientThenStrictWindowThenEarliestClose() {
    PlanOrder ambient = order().ref("A").build();
    PlanOrder ambientMall = order().mall().ref("B").build();
    PlanOrder chilled = order().chilled().ref("C").build();
    PlanOrder closesFirst = order().window("03:00", "07:30").ref("D").build();

    assertEquals(
        List.of(chilled, ambientMall, closesFirst, ambient),
        DEFAULT.rank(List.of(ambient, ambientMall, chilled, closesFirst), CONTEXT));
  }

  @Test
  void weeklyCadenceOutranksDailyAmongEquals() {
    PlanOrder tech = order().brand("Tech").ref("A").build();
    PlanOrder style = order().brand("Style").ref("B").build();
    assertEquals(List.of(style, tech), DEFAULT.rank(List.of(tech, style), CONTEXT));
  }

  @Test
  void distanceBreaksTiesAfterWindowsAndOrderRefIsFinal() {
    PlanOrder near = order().ref("A").build();
    PlanOrder far = order().district("Puttalam").ref("B").build();
    PlanOrder twin = order().ref("C").build();
    assertEquals(List.of(far, near, twin), DEFAULT.rank(List.of(twin, near, far), CONTEXT));
  }

  @Test
  void rankingIsDeterministic() {
    List<PlanOrder> orders = List.of(order().build(), order().chilled().build(), order().brand("Tech").build());
    List<PlanOrder> reversed = new java.util.ArrayList<>(orders);
    java.util.Collections.reverse(reversed);
    assertEquals(DEFAULT.rank(orders, CONTEXT), DEFAULT.rank(reversed, CONTEXT));
  }

  @Test
  void unknownOrDuplicateKeysAreRejected() {
    assertThrows(DomainException.class, () -> PriorityPolicy.parse(UUID.randomUUID(), List.of("FRESH", "NEAREST")));
    assertThrows(DomainException.class, () -> PriorityPolicy.parse(UUID.randomUUID(), List.of("FRESH", "FRESH")));
  }
}
