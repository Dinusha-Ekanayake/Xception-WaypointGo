package com.waypoint.dispatch.messaging.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.messaging.domain.MessagePolicy.Membership;
import com.waypoint.dispatch.messaging.domain.MessagePolicy.Post;
import com.waypoint.dispatch.messaging.domain.MessagePolicy.ReportOf;
import com.waypoint.dispatch.messaging.domain.MessagePolicy.Role;
import com.waypoint.dispatch.messaging.domain.MessagePolicy.To;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** R-MSG-02 to R-MSG-06 (issue #136). */
class MessagePolicyTest {
  private static final LocalDate DAY = LocalDate.of(2026, 10, 5);
  private static final List<String> OUTLETS = List.of("OUT061", "OUT063");

  private static Post post(To to) {
    return new Post("Running late", to, Optional.empty(), Optional.empty(), false);
  }

  private static Post toOutlet(String outlet) {
    return new Post("Running late", To.OUTLET, Optional.of(outlet), Optional.empty(), false);
  }

  private static Post report(To to, String type) {
    return new Post("2 units missing", to, Optional.empty(), Optional.of(type), false);
  }

  private static String check(Role role, Post post) {
    return MessagePolicy.check(role, post, OUTLETS, true, DAY, DAY);
  }

  private static void refused(Role role, Post post, String rule) {
    DomainException e = assertThrows(DomainException.class, () -> check(role, post));
    assertEquals(ErrorCode.VALIDATION_FAILED, e.code());
    assertTrue(e.violations().stream().anyMatch(v -> v.rule().equals(rule)), e.getMessage());
  }

  @Test
  void theDispatcherWritesToTheDriverTheLoadersOneOutletOrEveryone() {
    for (To to : List.of(To.DRIVER, To.LOADER, To.ALL)) {
      assertEquals("Running late", check(Role.DISPATCHER, post(to)));
    }
    assertEquals("Running late", check(Role.DISPATCHER, toOutlet("OUT063")));
    refused(Role.DISPATCHER, post(To.DISPATCH), "R-MSG-02");
  }

  @Test
  void theChannelIsTheDispatchersAndTheStoresStillWaiting() {
    // R-MSG-08 (FF-04)
    assertEquals("Running late", MessagePolicy.check(Role.DISPATCHER, post(To.CHANNEL), OUTLETS, true, true, false, DAY, DAY));
    assertEquals("Running late", MessagePolicy.check(Role.STORE_MANAGER, post(To.CHANNEL), OUTLETS, true, true, true, DAY, DAY));
    DomainException done =
        assertThrows(
            DomainException.class,
            () -> MessagePolicy.check(Role.STORE_MANAGER, post(To.CHANNEL), OUTLETS, true, true, false, DAY, DAY));
    assertEquals(ErrorCode.CONFLICT, done.code());
    assertEquals("R-MSG-08", done.violations().get(0).rule());
    // Before the driver starts the trip there is no channel, for anyone.
    for (Role role : List.of(Role.DISPATCHER, Role.STORE_MANAGER)) {
      DomainException early =
          assertThrows(
              DomainException.class,
              () -> MessagePolicy.check(role, post(To.CHANNEL), OUTLETS, true, false, true, DAY, DAY));
      assertEquals(ErrorCode.CONFLICT, early.code());
    }
    // The driver is driving and the loaders are not on it.
    refused(Role.DRIVER, post(To.CHANNEL), "R-MSG-02");
    refused(Role.LOADER, post(To.CHANNEL), "R-MSG-02");
    // A report stays the dispatcher's alone, even from a store in the channel.
    refused(Role.STORE_MANAGER, report(To.CHANNEL, "receipt_dispute"), "R-MSG-03");
  }

  @Test
  void aRedirectGivesItsReason() {
    assertEquals("Names another store's order", MessagePolicy.redirectReason("  Names another store's order "));
    for (String bad : new String[] {null, "", "ok", "x".repeat(501)}) {
      DomainException e = assertThrows(DomainException.class, () -> MessagePolicy.redirectReason(bad));
      assertEquals("R-MSG-08", e.violations().get(0).rule());
    }
  }

  @Test
  void anOutletMustBeOnTheTrip() {
    refused(Role.DISPATCHER, toOutlet("OUT099"), "R-MSG-02");
  }

  @Test
  void theDriverWritesToTheDispatcherOrAStopButNeverToEveryone() {
    assertEquals("Running late", check(Role.DRIVER, post(To.DISPATCH)));
    assertEquals("Running late", check(Role.DRIVER, toOutlet("OUT061")));
    refused(Role.DRIVER, post(To.ALL), "R-MSG-02");
    refused(Role.DRIVER, post(To.LOADER), "R-MSG-02");
  }

  @Test
  void aLoaderAndAStoreManagerWriteToTheDispatcher() {
    for (Role role : List.of(Role.LOADER, Role.STORE_MANAGER)) {
      assertEquals("Running late", check(role, post(To.DISPATCH)));
      refused(role, post(To.ALL), "R-MSG-02");
      refused(role, toOutlet("OUT061"), "R-MSG-02");
    }
    refused(Role.STORE_MANAGER, post(To.DRIVER), "R-MSG-02");
  }

  @Test
  void aLoaderLeavesTheDriverANoteAboutTheLoadWhenTheTripHasAVehicle() {
    assertEquals("Running late", check(Role.LOADER, post(To.DRIVER)));
    DomainException e = assertThrows(
        DomainException.class, () -> MessagePolicy.check(Role.LOADER, post(To.DRIVER), OUTLETS, false, DAY, DAY));
    assertTrue(e.violations().stream().anyMatch(v -> v.rule().equals("R-MSG-02")), e.getMessage());
  }

  @Test
  void aReportIsForTheDispatcherAloneAndNeverTheDispatchers() {
    for (Role role : List.of(Role.LOADER, Role.DRIVER, Role.STORE_MANAGER)) {
      assertEquals("2 units missing", check(role, report(To.DISPATCH, "loading_shortfall")));
      refused(role, report(To.ALL, "loading_shortfall"), "R-MSG-03");
    }
    refused(Role.DISPATCHER, report(To.DISPATCH, "other"), "R-MSG-03");
    refused(Role.DRIVER, report(To.DISPATCH, "not_a_type"), "R-MSG-02");
  }

  @Test
  void aReadyReplyIsNamedByItsIdVersionAndRankAndNeverOnAReport() {
    assertEquals(Optional.empty(), MessagePolicy.replyUse(null, null, null, false, false));
    assertEquals(
        Optional.of(new MessagePolicy.ReplyUse("driver.bring_back", 1, 2, true)),
        MessagePolicy.replyUse("driver.bring_back", 1, 2, true, false));
    for (Runnable bad : List.<Runnable>of(
        () -> MessagePolicy.replyUse("Bring back", 1, 1, false, false),
        () -> MessagePolicy.replyUse("driver.bring_back", 0, 1, false, false),
        () -> MessagePolicy.replyUse("driver.bring_back", 1, 11, false, false),
        () -> MessagePolicy.replyUse("driver.bring_back", null, 1, false, false),
        () -> MessagePolicy.replyUse("driver.bring_back", 1, 1, false, true))) {
      assertThrows(DomainException.class, bad::run);
    }
  }

  /** AI-J5: the replies on screen are kept beside the pick, only when they hold the pick at its rank. */
  @Test
  void theRepliesOnScreenAreKeptOnlyWhenTheyHoldThePickAtItsRank() {
    MessagePolicy.ReplyUse use =
        MessagePolicy.replyUse("store.late", 1, 2, false, false, "store.thanks, store.late,store.next", true).orElseThrow();
    assertEquals(List.of("store.thanks", "store.late", "store.next"), use.shown());
    assertTrue(use.explored());
    assertEquals(List.of(), MessagePolicy.replyUse("store.late", 1, 2, false, false, null, false).orElseThrow().shown());
    for (Runnable bad : List.<Runnable>of(
        () -> MessagePolicy.replyUse("store.late", 1, 2, false, false, "store.late,store.thanks", false),
        () -> MessagePolicy.replyUse("store.late", 1, 3, false, false, "store.a,store.late", false),
        () -> MessagePolicy.replyUse("store.late", 1, 1, false, false, "store.late,Not an id", false))) {
      assertThrows(DomainException.class, bad::run);
    }
  }

  @Test
  void theThreadTakesPostsUntilTheEndOfTheNextDay() {
    assertTrue(MessagePolicy.open(DAY, DAY));
    assertTrue(MessagePolicy.open(DAY, DAY.plusDays(1)));
    assertFalse(MessagePolicy.open(DAY, DAY.plusDays(2)));
    DomainException e =
        assertThrows(
            DomainException.class,
            () -> MessagePolicy.check(Role.DRIVER, post(To.DISPATCH), OUTLETS, true, DAY, DAY.plusDays(2)));
    assertEquals(ErrorCode.CONFLICT, e.code());
  }

  @Test
  void aBodyIsTrimmedNeverEmptyAndAtMostAThousandCharacters() {
    assertEquals("ok", MessagePolicy.body("  ok \n"));
    assertThrows(DomainException.class, () -> MessagePolicy.body("   "));
    assertThrows(DomainException.class, () -> MessagePolicy.body("x".repeat(1001)));
  }

  @Test
  void aVoiceNoteStandsInForTheText() {
    Post voice = new Post("", To.DISPATCH, Optional.empty(), Optional.of("vehicle_fault"), true);
    assertEquals("", check(Role.DRIVER, voice));
    assertEquals("Voice report", MessagePolicy.voiceExcerpt("", true));
    assertEquals("Voice message", MessagePolicy.voiceExcerpt(" ", false));
  }

  @Test
  void aVoiceNoteIsARecordersAudioOfAtMostTwoMinutesAndTwoMegabytes() {
    assertEquals("audio/webm", MessagePolicy.voiceType("audio/webm;codecs=opus", 1000, Optional.of(4000)));
    assertEquals("audio/mp4", MessagePolicy.voiceType("audio/mp4", 1000, Optional.empty()));
    assertThrows(DomainException.class, () -> MessagePolicy.voiceType("image/png", 1000, Optional.empty()));
    assertThrows(
        DomainException.class, () -> MessagePolicy.voiceType("audio/webm", 2 * 1024 * 1024 + 1, Optional.empty()));
    assertThrows(DomainException.class, () -> MessagePolicy.voiceType("audio/webm", 1000, Optional.of(120_001)));
  }

  @Test
  void anExcerptIsTheFirstLineCutAtAWord() {
    assertEquals("Short note", MessagePolicy.excerpt("Short note\nsecond line"));
    String cut = MessagePolicy.excerpt("word ".repeat(60));
    assertTrue(cut.length() <= MessagePolicy.EXCERPT_MAX + 1, cut);
    assertTrue(cut.endsWith(MessagePolicy.ELLIPSIS), cut);
  }

  @Test
  void anIssueFromAnEventIsReportedByTheRoleItsTypeNames() {
    assertEquals(
        Optional.of(new ReportOf("loading_shortfall", Role.LOADER)),
        MessagePolicy.reportOf("LOADING_SHORTFALL", Optional.empty()));
    assertEquals(
        Optional.of(new ReportOf("vehicle_fault", Role.DRIVER)), MessagePolicy.reportOf("VEHICLE_FAULT", Optional.empty()));
    assertEquals(
        Optional.of(new ReportOf("receipt_dispute", Role.STORE_MANAGER)),
        MessagePolicy.reportOf("RECEIPT_DISPUTE", Optional.empty()));
    assertEquals(Optional.empty(), MessagePolicy.reportOf("OTHER", Optional.empty()));
  }

  @Test
  void anIssueAPersonRaisedIsTheirsAndADispatchersIsNoReport() {
    assertEquals(
        Optional.of(new ReportOf("damaged_goods", Role.DRIVER)),
        MessagePolicy.reportOf("DAMAGED_GOODS", Optional.of(Role.DRIVER)));
    assertEquals(Optional.empty(), MessagePolicy.reportOf("FAILED_DELIVERY", Optional.of(Role.DISPATCHER)));
  }

  @Test
  void theStrongestWayOfBelongingIsTheOneTheyWriteAs() {
    assertEquals(Optional.of(Role.DISPATCHER), new Membership(true, false, true, List.of()).role());
    assertEquals(Optional.of(Role.DRIVER), new Membership(false, true, true, List.of()).role());
    assertEquals(Optional.of(Role.LOADER), new Membership(false, true, false, List.of("OUT061")).role());
    assertEquals(Optional.of(Role.STORE_MANAGER), new Membership(false, false, false, List.of("OUT061")).role());
    assertEquals(Optional.empty(), new Membership(false, false, false, List.of()).role());
  }

  @Test
  void aResolutionNoteIsOptionalAndShort() {
    assertEquals(Optional.empty(), MessagePolicy.resolutionNote(null));
    assertEquals(Optional.empty(), MessagePolicy.resolutionNote("   "));
    assertEquals(Optional.of("Spare fitted"), MessagePolicy.resolutionNote("  Spare fitted "));
    org.junit.jupiter.api.Assertions.assertThrows(
        com.waypoint.dispatch.shared.error.DomainException.class, () -> MessagePolicy.resolutionNote("x".repeat(501)));
  }

  @Test
  void aWaveformFromThePhoneIsClampedAndAnythingOddIsDropped() {
    assertEquals(List.of(0, 50, 100), MessagePolicy.peaks("-4, 50,250"));
    assertEquals(List.of(), MessagePolicy.peaks(null));
    assertEquals(List.of(), MessagePolicy.peaks("1,two,3"));
    assertEquals(List.of(), MessagePolicy.peaks(String.join(",", java.util.Collections.nCopies(65, "1"))));
  }
}
