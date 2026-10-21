package com.waypoint.dispatch.intelligence.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** docs/issues/watch-review/PLAN.md: the model's order is used only as an exact reordering of what was sent. */
class WatchReviewTest {
  private final List<WatchReview.Item> sent = WatchReview.items(List.of(
      new WatchReview.Item(null, "FAILED_STOP", "CRITICAL", null, "OUT010", "VEH030"),
      new WatchReview.Item(null, "WINDOW_AT_RISK", "HIGH", 48, "OUT063", "VEH020")));

  @Test
  void itemsGoUnderShortReferencesInTheRulesOrder() {
    assertEquals(List.of("A1", "A2"), sent.stream().map(WatchReview.Item::ref).toList());
    assertEquals(2, ((List<?>) WatchReview.facts(sent).get("items")).size());
  }

  @Test
  void anExactReorderingIsUsedAndSaysWhetherItAgreesOnTheFirst() {
    assertTrue(WatchReview.judge(sent, List.of("A1", "A2")).orElseThrow().agreesFirst());
    assertFalse(WatchReview.judge(sent, List.of("A2", "A1")).orElseThrow().agreesFirst());
  }

  @Test
  void anOrderWithAnItemNeverSentMissingOrTwiceIsRefused() {
    assertTrue(WatchReview.judge(sent, List.of("A1", "A9")).isEmpty());
    assertTrue(WatchReview.judge(sent, List.of("A1")).isEmpty());
    assertTrue(WatchReview.judge(sent, List.of("A1", "A1")).isEmpty());
    assertTrue(WatchReview.judge(sent, null).isEmpty());
  }
}
