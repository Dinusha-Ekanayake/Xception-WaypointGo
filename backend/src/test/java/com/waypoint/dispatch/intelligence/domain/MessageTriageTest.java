package com.waypoint.dispatch.intelligence.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** R-ML-35: a class Jev gave a message counts on the card only at or above the live confidence. */
class MessageTriageTest {
  @Test
  void aClassAtOrAboveTheConfidenceIsASignal() {
    assertEquals(Optional.of(Playbooks.Situation.BREAKDOWN), MessageTriage.signal("BREAKDOWN", 0.5, 0.5));
    assertEquals(Optional.of(Playbooks.Situation.STORE_CLOSED), MessageTriage.signal("STORE_CLOSED", 0.93, 0.5));
  }

  @Test
  void belowTheConfidenceNoneOfTheseOrAnUnknownClassAddsNothing() {
    assertTrue(MessageTriage.signal("BREAKDOWN", 0.49, 0.5).isEmpty());
    assertTrue(MessageTriage.signal(MessageTriage.NONE, 0.99, 0.5).isEmpty());
    assertTrue(MessageTriage.signal("WINDOW", 0.99, 0.5).isEmpty(), "a situation of the board, not of the chat");
    assertTrue(MessageTriage.signal("NOT_A_SITUATION", 0.99, 0.5).isEmpty());
    assertTrue(MessageTriage.signal(null, 0.99, 0.5).isEmpty());
  }

  @Test
  void theOptionsAreTheReportsSoAClassMeansWhatAReportTypeMeans() {
    assertEquals(ReportTriage.OPTIONS.keySet(), MessageTriage.shuffled(7).keySet());
    assertEquals(MessageTriage.shuffled(7).keySet().stream().toList(), MessageTriage.shuffled(7).keySet().stream().toList(), "a retry asks the same question");
  }
}
