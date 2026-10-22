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
    assertTrue(MessageTriage.shuffled(7).keySet().containsAll(ReportTriage.OPTIONS.keySet()));
    assertTrue(MessageTriage.shuffled(7).keySet().containsAll(MessageTriage.ACTS.keySet()));
    assertTrue(MessageTriage.shuffled(7).containsKey(MessageTriage.OFF_TOPIC), "the off-topic guard is an answer");
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

  @Test
  void anActReadSureFromClearWordsSteersTheRepliesAndNeverOpensAProblem() {
    // R-ML-43.
    assertEquals(Optional.of("ASKS_CAN_WAIT"), MessageTriage.steering("ASKS_CAN_WAIT", 0.8, 0.5, false));
    assertEquals(Optional.of("OFF_TOPIC"), MessageTriage.steering("OFF_TOPIC", 0.9, 0.5, false), "off topic asks for no reply");
    assertTrue(MessageTriage.steering("SAYS_NO", 0.4, 0.5, false).isEmpty(), "unsure: the rules steer");
    assertTrue(MessageTriage.steering("OFF_TOPIC", 0.6, 0.5, false).isEmpty(), "off topic needs more certainty: the rules steer");
    assertTrue(MessageTriage.steering("SAYS_NO", 0.9, 0.5, true).isEmpty(), "heard unclearly: the rules steer");
    assertTrue(MessageTriage.steering(MessageTriage.NONE, 0.99, 0.5, false).isEmpty());
    assertEquals(MessageTriage.Reading.NOTHING, MessageTriage.reading("SAYS_NO", 0.99, 0.5, false, Optional.empty()));
    assertEquals(MessageTriage.Reading.NOTHING, MessageTriage.reading("OFF_TOPIC", 0.99, 0.5, false, Optional.empty()));
    assertTrue(MessageTriage.signal("ASKS_CAN_WAIT", 0.99, 0.5).isEmpty(), "an act is never a problem on the room");
  }
}
