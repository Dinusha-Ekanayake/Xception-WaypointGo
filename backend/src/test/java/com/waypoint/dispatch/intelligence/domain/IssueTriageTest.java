package com.waypoint.dispatch.intelligence.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** R-ML-11: the question Jev is asked in shadow. */
class IssueTriageTest {

  @Test
  void everyIssueTypeIsAnOptionInAnOrderFixedPerIssueButNotTheSameForAll() {
    assertEquals(9, IssueTriage.OPTIONS.size());
    assertEquals(List.copyOf(IssueTriage.shuffled(42).keySet()), List.copyOf(IssueTriage.shuffled(42).keySet()),
        "a retry asks the same question");
    boolean differs = false;
    for (long seed = 1; seed < 6; seed++) {
      differs |= !List.copyOf(IssueTriage.shuffled(seed).keySet()).equals(List.copyOf(IssueTriage.shuffled(seed + 100).keySet()));
    }
    assertTrue(differs, "the first option is not always the same, so a lean towards it cannot pass for agreement");
    assertEquals(IssueTriage.OPTIONS.keySet(), IssueTriage.shuffled(7).keySet());
  }

  @Test
  void onlyRealWordsAreAskedAboutAndTheyAreMasked() {
    assertEquals(Optional.empty(), IssueTriage.state("Reported a problem"));
    assertEquals(Optional.empty(), IssueTriage.state(" ok "));
    assertEquals(Optional.of("Flooded near the bridge, call [phone]"), IssueTriage.state("Flooded near the bridge, call 0771234567"));
    assertNotEquals(Optional.empty(), IssueTriage.state("Tyre burst on the A1"));
  }
}
