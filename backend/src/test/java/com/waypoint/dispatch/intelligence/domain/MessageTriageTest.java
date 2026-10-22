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

  @Test
  void aSureClearReadingCountsAnUnsureOrUnclearOneIsReviewedAndAConfirmationDecides() {
    // R-ML-37.
    assertEquals(MessageTriage.Reading.SIGNAL, MessageTriage.reading("BREAKDOWN", 0.8, 0.5, false, Optional.empty()));
    assertEquals(MessageTriage.Reading.REVIEW, MessageTriage.reading("BREAKDOWN", 0.3, 0.5, false, Optional.empty()),
        "a problem said unsurely is confirmed, not dropped");
    assertEquals(MessageTriage.Reading.NOTHING, MessageTriage.reading(MessageTriage.NONE, 0.4, 0.5, false, Optional.empty()));
    assertEquals(MessageTriage.Reading.REVIEW, MessageTriage.reading("BREAKDOWN", 0.99, 0.5, true, Optional.empty()),
        "words heard unclearly are confirmed however sure the reading");
    assertEquals(MessageTriage.Reading.REVIEW, MessageTriage.reading(MessageTriage.NONE, 0.6, 0.5, true, Optional.empty()));
    assertEquals(MessageTriage.Reading.NOTHING, MessageTriage.reading(MessageTriage.NONE, 0.95, 0.5, true, Optional.empty()),
        "noisy chat Jev is very sure says nothing is left alone");
    assertEquals(MessageTriage.Reading.SIGNAL, MessageTriage.reading(MessageTriage.NONE, 0.95, 0.5, true, Optional.of("STORE_CLOSED")));
    assertEquals(MessageTriage.Reading.NOTHING, MessageTriage.reading("BREAKDOWN", 0.99, 0.5, false, Optional.of(MessageTriage.NONE)),
        "what the dispatcher confirmed decides");
    assertEquals(MessageTriage.Reading.NOTHING, MessageTriage.reading(null, 0, 0.5, true, Optional.empty()));
  }
}
