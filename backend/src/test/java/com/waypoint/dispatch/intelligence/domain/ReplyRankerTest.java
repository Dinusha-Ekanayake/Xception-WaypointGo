package com.waypoint.dispatch.intelligence.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ReplyRankerTest {

  @Test
  void belowTheMinimumNothingIsLearnedAndTheRuleOrderStays() {
    List<ReplyRanker.Choice> few = new ArrayList<>();
    for (int i = 0; i < ReplyRanker.MIN_POSTS - 1; i++) {
      few.add(new ReplyRanker.Choice(List.of("driver.a", "driver.b"), 1, false));
    }
    ReplyRanker.Ranking r = ReplyRanker.fit(few);
    assertFalse(r.trained());
    assertTrue(r.scores().isEmpty());
    assertEquals(1.0, r.topAcceptance());
  }

  /**
   * The rule order puts "a" first, and first place pulls by itself; dispatchers
   * still prefer "b" whenever it is shown. The swapped posts let the ranker see
   * "b" in first place, so it learns "b" over "a" and that place has a pull.
   */
  @Test
  void theReplyTakenMoreIsRankedFirstWithThePullOfPlaceLearnedApart() {
    Random random = new Random(7);
    List<ReplyRanker.Choice> choices = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
      boolean explored = random.nextDouble() < 0.1;
      List<String> shown = explored ? List.of("store.x", "store.b", "store.a") : List.of("store.x", "store.a", "store.b");
      double[] appeal = new double[3];
      for (int p = 0; p < 3; p++) {
        double reply = switch (shown.get(p)) { case "store.b" -> 1.5; case "store.a" -> 0.5; default -> 0.0; };
        appeal[p] = Math.exp(reply + (p == 0 ? 1.0 : 0.0));
      }
      double total = appeal[0] + appeal[1] + appeal[2];
      double u = random.nextDouble() * total;
      int pick = u < appeal[0] ? 1 : u < appeal[0] + appeal[1] ? 2 : 3;
      choices.add(new ReplyRanker.Choice(shown, pick, explored));
    }
    ReplyRanker.Ranking r = ReplyRanker.fit(choices);
    assertTrue(r.trained());
    assertTrue(r.scores().get("store.b") > r.scores().get("store.a"), r.toString());
    assertTrue(r.scores().get("store.a") > r.scores().get("store.x") - 1.0, r.toString());
    assertTrue(r.placeEffect().get(0) < 0, "a later place pulls less than the first: " + r);
    // Same record, same answer.
    assertEquals(r, ReplyRanker.fit(choices));
  }

  @Test
  void aPickOutsideTheShownListIsNotCounted() {
    List<ReplyRanker.Choice> choices = List.of(new ReplyRanker.Choice(List.of("driver.a"), 3, false));
    assertEquals(0, ReplyRanker.fit(choices).posts());
  }
}
