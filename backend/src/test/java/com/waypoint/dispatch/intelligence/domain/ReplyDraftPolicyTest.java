package com.waypoint.dispatch.intelligence.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** R-ML-46: a drafted reply is shown only when it is short, plain and adds no figure of its own. */
class ReplyDraftPolicyTest {
  private static final String SENT = "Dispatcher: Driver is on the way\nLast message, from the store: can the driver bring the 3 empty crates back?";

  @Test
  void aShortPlainReplyThatAddsNothingIsShown() {
    assertEquals(Optional.of("Yes, I will ask the driver to bring the 3 empty crates back."),
        ReplyDraftPolicy.accept(" Yes, I will ask the driver to bring the 3 empty crates back. ", SENT));
    assertEquals(Optional.of("I will check and come back to you."), ReplyDraftPolicy.accept("\"I will check and come back to you.\"", SENT));
  }

  @Test
  void aReplyThatInventsOrLeaksOrIsTooLongIsDropped() {
    assertTrue(ReplyDraftPolicy.accept("The driver will be there at 4.30 with the crates.", SENT).isEmpty(), "a time nobody said");
    assertTrue(ReplyDraftPolicy.accept("Call me on 0771234567.", SENT).isEmpty(), "a phone number");
    assertTrue(ReplyDraftPolicy.accept("See https://example.com", SENT).isEmpty(), "a link");
    assertTrue(ReplyDraftPolicy.accept("Thanks [person-1], noted.", SENT).isEmpty(), "a masked name");
    assertTrue(ReplyDraftPolicy.accept("<b>Noted</b>", SENT).isEmpty(), "markup");
    assertTrue(ReplyDraftPolicy.accept("Noted.\nThanks.", SENT).isEmpty(), "more than one line");
    assertTrue(ReplyDraftPolicy.accept("x".repeat(201), SENT).isEmpty(), "too long");
    assertTrue(ReplyDraftPolicy.accept("NONE", SENT).isEmpty(), "nothing to answer");
    assertTrue(ReplyDraftPolicy.accept("Noted " + (char) 0x2014 + " thanks", SENT).isEmpty(), "a long dash");
    assertTrue(ReplyDraftPolicy.accept(null, SENT).isEmpty());
  }

  @Test
  void onlyADriversOrAStoresClearEnglishWordsReadAsNoneGetADraft() {
    assertTrue(ReplyDraftPolicy.worthDrafting("store_manager", "OTHER", 0.8, 0.5, false, "Please send the invoice"));
    assertTrue(ReplyDraftPolicy.worthDrafting("driver", "OTHER", 0.8, 0.5, false, "Where do I put the frozen items?"));
    assertFalse(ReplyDraftPolicy.worthDrafting("dispatcher", "OTHER", 0.8, 0.5, false, "Please send the invoice"), "the dispatcher's own");
    assertFalse(ReplyDraftPolicy.worthDrafting("store_manager", "SAYS_NO", 0.9, 0.5, false, "no"), "a ready reply fits");
    assertFalse(ReplyDraftPolicy.worthDrafting("store_manager", "OTHER", 0.3, 0.5, false, "hmm"), "unsure");
    assertFalse(ReplyDraftPolicy.worthDrafting("store_manager", "OTHER", 0.8, 0.5, true, "send invoice"), "heard unclearly");
    assertFalse(ReplyDraftPolicy.worthDrafting("store_manager", "OTHER", 0.8, 0.5, false, "ඉන්වොයිසිය එවන්න"), "not in Latin letters");
  }
}
