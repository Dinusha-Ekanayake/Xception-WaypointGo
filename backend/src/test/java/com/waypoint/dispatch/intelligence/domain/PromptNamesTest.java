package com.waypoint.dispatch.intelligence.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** R-ML-31: our people's names never go to a language model. */
class PromptNamesTest {
  private final PromptNames names = PromptNames.of(List.of("Dilan Perera", "Dilan R.", "Nimal Silva", "Ai", "නිමල් පෙරේරා"));

  @Test
  void aFullNameAndAFirstNameBecomeTheSameTokenAndComeBack() {
    PromptNames.Session s = names.session();
    String masked = s.mask("Dilan Perera called. Tell dilan perera and Nimal to wait; Nimal Silva knows.");
    // "Nimal" is Nimal Silva's first name: the same person, the same token, put back in full.
    assertEquals("[person-1] called. Tell [person-1] and [person-2] to wait; [person-2] knows.", masked);
    assertEquals("Dilan Perera called. Tell Dilan Perera and Nimal Silva to wait; Nimal Silva knows.",
        PromptNames.restore(masked, s.tokens()));
  }

  @Test
  void onlyWholeWordsAndNamesLongEnoughAreMasked() {
    PromptNames.Session s = names.session();
    // "Dilanka" is another word; "Ai" is one word, so only the whole name masks, never "ai" inside a word.
    assertEquals("Dilanka waits for the chair at [person-1].", s.mask("Dilanka waits for the chair at Ai."));
  }

  @Test
  void aNameInSinhalaIsMaskedAsWell() {
    assertEquals("[person-1] ආවා", names.session().mask("නිමල් පෙරේරා ආවා"));
  }

  @Test
  void aTokenTheModelInventedIsLeftAsItIs() {
    PromptNames.Session s = names.session();
    s.mask("Dilan Perera");
    assertEquals("Dilan Perera and [person-9]", PromptNames.restore("[person-1] and [person-9]", s.tokens()));
  }

  @Test
  void noNamesMasksNothing() {
    assertEquals("Dilan Perera", PromptNames.of(List.of()).session().mask("Dilan Perera"));
  }
}
