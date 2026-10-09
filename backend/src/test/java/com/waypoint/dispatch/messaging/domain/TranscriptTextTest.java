package com.waypoint.dispatch.messaging.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class TranscriptTextTest {

  @Test
  void theWordsAreKeptInTheirOwnScriptTrimmedAndBounded() {
    assertEquals(
        Optional.of(new TranscriptText.Heard("டயர் வெடித்தது", Optional.of("ta"))),
        TranscriptText.heard("  டயர் வெடித்தது \u0007", " TA "));
    assertEquals(TranscriptText.MAX, TranscriptText.heard("a".repeat(5000), "en").orElseThrow().text().length());
  }

  @Test
  void noSpeechIsNoTranscriptAndAnUnknownLanguageIsNotGuessed() {
    assertTrue(TranscriptText.heard("   ", "si").isEmpty());
    assertTrue(TranscriptText.heard(null, null).isEmpty());
    assertEquals(Optional.empty(), TranscriptText.heard("Bonjour", "fr").orElseThrow().language());
  }
}
