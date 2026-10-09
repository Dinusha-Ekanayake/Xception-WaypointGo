package com.waypoint.dispatch.intelligence.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.intelligence.domain.PromptFacts.Kind;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** R-ML-10: what may reach a language model, and what may come back. */
class PromptFactsTest {

  @Test
  void onlyFieldsOnTheListGoAndUnknownShapesAreDropped() {
    Map<String, Object> sent = PromptFacts.allowed(Kind.PLAN, Map.of(
        "headline", "Plan for Mon 1 Mar",
        "leftOff", List.of(Map.of("label", "No room", "count", 3, "example", "OUT063", "driverPhone", "0771234567")),
        "cannotBeServed", 2,
        "driverName", "Dilan Rajapaksa",
        "raw", Map.of("anything", "else")));
    assertEquals(java.util.Set.of("headline", "leftOff", "cannotBeServed"), sent.keySet());
    assertEquals(List.of(Map.of("label", "No room", "count", 3, "example", "OUT063")), sent.get("leftOff"));
    assertTrue(PromptFacts.allowed(Kind.ORDER, Map.of("headline", "x")).containsKey("headline"));
    assertFalse(PromptFacts.allowed(Kind.ORDER, Map.of("carries", "x")).containsKey("carries"), "a plan's field is not an order's");
  }

  @Test
  void phonesIdentityNumbersEmailsAndLinksAreMaskedBeforeAnythingLeaves() {
    assertEquals("Call [phone] or [phone], ID [id], [e-mail], see [link]",
        PromptFacts.mask("Call 077 123 4567 or +94771234567, ID 199012345678, kasun@example.lk, see https://x.y/z"));
    assertEquals("Old ID [id] kept apart from OUT063 and VEH020", PromptFacts.mask("Old ID 901234567V kept apart from OUT063 and VEH020"));
    assertEquals(PromptFacts.MAX_STRING, PromptFacts.mask("a".repeat(1_000)).length());
  }

  @Test
  void anAnswerWithAFigureTheFactsDoNotHoldIsThrownAway() {
    String facts = "{\"headline\":\"3 orders left off\",\"carries\":\"12 stops\"}";
    assertEquals(Optional.of("Three orders, **3**, wait; 12 stops go."), PromptFacts.faithful("Three orders, **3**, wait; 12 stops go.", facts));
    assertEquals(Optional.empty(), PromptFacts.faithful("4 orders wait", facts), "an invented number");
    assertEquals(Optional.empty(), PromptFacts.faithful("  ", facts));
  }
}
