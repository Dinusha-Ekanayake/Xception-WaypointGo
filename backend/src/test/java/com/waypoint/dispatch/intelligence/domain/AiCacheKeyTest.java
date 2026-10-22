package com.waypoint.dispatch.intelligence.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** R-ML-47: an answer is reused only for the same words asked the same way. */
class AiCacheKeyTest {
  private static String reading(String words, List<String> earlier) {
    return AiCacheKey.reading("v1", "jev-1.13", "store_manager", words, earlier);
  }

  @Test
  void caseSpacingAndPunctuationAreSetAside() {
    assertEquals("no", AiCacheKey.normalise(" No!! "));
    assertEquals("can u wait", AiCacheKey.normalise("Can  u   wait???"));
    assertEquals("call [phone] at [person-1]", AiCacheKey.normalise("Call [phone], at [person-1]."));
    assertEquals("ඔව් හරි", AiCacheKey.normalise("ඔව්,  හරි!"));
    assertEquals(reading("No.", List.of("ASKS_CAN_WAIT")), reading("  no ", List.of("ASKS_CAN_WAIT")));
  }

  @Test
  void otherWordsAnotherContextAnotherPromptOrModelOrWriterIsAnotherKey() {
    String base = reading("we can wait", List.of());
    assertNotEquals(base, reading("we can't wait", List.of()), "never similar, only the same");
    assertNotEquals(reading("no", List.of("ASKS_CAN_WAIT")), reading("no", List.of("OTHER")), "what it answers counts");
    assertNotEquals(base, AiCacheKey.reading("v2", "jev-1.13", "store_manager", "we can wait", List.of()), "a new prompt");
    assertNotEquals(base, AiCacheKey.reading("v1", "jev-1.14", "store_manager", "we can wait", List.of()), "a new model");
    assertNotEquals(base, AiCacheKey.reading("v1", "jev-1.13", "driver", "we can wait", List.of()), "another writer");
  }

  @Test
  void aDraftIsKeptPerDepotAndApartFromReadings() {
    String kandy = AiCacheKey.draft("v1", "qwen", "Kandy", "store_manager", "send the invoice", List.of());
    assertNotEquals(kandy, AiCacheKey.draft("v1", "qwen", "Peliyagoda", "store_manager", "send the invoice", List.of()));
    assertNotEquals(kandy, AiCacheKey.reading("v1", "qwen", "store_manager", "send the invoice", List.of()));
    assertEquals(64, kandy.length());
  }
}
