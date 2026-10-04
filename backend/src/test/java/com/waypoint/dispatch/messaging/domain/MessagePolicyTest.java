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
  void aLoaderAndAStoreManagerWriteToTheDispatcherOnly() {
    for (Role role : List.of(Role.LOADER, Role.STORE_MANAGER)) {
      assertEquals("Running late", check(role, post(To.DISPATCH)));
      refused(role, post(To.ALL), "R-MSG-02");
      refused(role, post(To.DRIVER), "R-MSG-02");
      refused(role, toOutlet("OUT061"), "R-MSG-02");
    }
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
}
