package com.waypoint.dispatch.messaging.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** R-MSG-10: what of a message is spoken, and in which language. */
class SpeechTextTest {

  @Test
  void theLanguageComesFromTheScript() {
    assertEquals("en", SpeechText.languageOf("Running 20 min late"));
    assertEquals("si", SpeechText.languageOf("OUT001 විනාඩි 20"));
    assertEquals("ta", SpeechText.languageOf("20 நிமிடம்"));
  }

  @Test
  void separatorsBecomePausesAndTheSameTextIsKeptUnderOneKey() {
    assertEquals(Optional.of("Expected 08:20, On time"), SpeechText.spoken("  Expected 08:20 · On time "));
    assertEquals(Optional.empty(), SpeechText.spoken("   "));
    assertEquals(SpeechText.MAX, SpeechText.spoken("a".repeat(2_000)).orElseThrow().length());
    assertEquals(SpeechText.key("On the way"), SpeechText.key("On the way"));
    assertNotEquals(SpeechText.key("On the way"), SpeechText.key("On the way."));
  }
}
