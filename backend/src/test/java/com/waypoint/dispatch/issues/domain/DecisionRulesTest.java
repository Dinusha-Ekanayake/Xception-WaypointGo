package com.waypoint.dispatch.issues.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.issues.domain.DecisionRules.Kind;
import com.waypoint.dispatch.issues.domain.DecisionRules.State;
import com.waypoint.dispatch.issues.domain.DecisionRules.Stop;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** R-ISS-12 to R-ISS-14 (roadmap FF-09). */
class DecisionRulesTest {
  private static final ZoneId COLOMBO = ZoneId.of("Asia/Colombo");
  private static final Instant NOW = Instant.parse("2026-10-07T01:40:00Z"); // 07:10 in Colombo
  private static final Stop OPEN = new Stop("OUT001", false, false, Optional.empty());

  private static String rule(Runnable r) {
    return assertThrows(DomainException.class, r::run).violations().get(0).rule();
  }

  @Test
  void aStoreIsAskedToReceiveLateWithinFourHoursAndNeverAMall() {
    Instant at = NOW.plus(Duration.ofMinutes(70));
    assertEquals(at, DecisionRules.receiveLate(OPEN, at, NOW));
    assertEquals("R-ISS-12", rule(() -> DecisionRules.receiveLate(new Stop("OUT015", true, false, Optional.empty()), at, NOW)));
    assertEquals("R-ISS-12", rule(() -> DecisionRules.receiveLate(OPEN, NOW.minusSeconds(60), NOW)));
    assertEquals("R-ISS-12", rule(() -> DecisionRules.receiveLate(OPEN, NOW.plus(Duration.ofHours(5)), NOW)));
    DomainException done = assertThrows(DomainException.class,
        () -> DecisionRules.receiveLate(new Stop("OUT001", false, true, Optional.empty()), at, NOW));
    assertEquals(ErrorCode.CONFLICT, done.code());
  }

  @Test
  void onlyALaterStoreOnTheSameTripIsAskedToHelpAtItsDock() {
    Stop blocked = new Stop("OUT001", false, false, Optional.empty());
    Stop helper = new Stop("OUT002", false, false, Optional.empty());
    DecisionRules.dockHelp(helper, 2, blocked, 1, true);
    assertEquals("R-ISS-12", rule(() -> DecisionRules.dockHelp(helper, 1, blocked, 2, true)));
    assertEquals("R-ISS-12", rule(() -> DecisionRules.dockHelp(helper, 2, blocked, 1, false)));
    assertEquals("R-ISS-12", rule(() -> DecisionRules.dockHelp(new Stop("OUT001", false, false, Optional.empty()), 2, blocked, 1, true)));
  }

  @Test
  void theAnswerIsDueBeforeTheVehicleArrivesBetweenFiveAndTwentyMinutesFromNow() {
    assertEquals(NOW.plus(Duration.ofMinutes(20)), DecisionRules.deadline(NOW, Optional.empty()));
    assertEquals(NOW.plus(Duration.ofMinutes(15)), DecisionRules.deadline(NOW, Optional.of(NOW.plus(Duration.ofMinutes(25)))));
    assertEquals(NOW.plus(Duration.ofMinutes(5)), DecisionRules.deadline(NOW, Optional.of(NOW.plus(Duration.ofMinutes(8)))));
    assertEquals(NOW.plus(Duration.ofMinutes(20)), DecisionRules.deadline(NOW, Optional.of(NOW.plus(Duration.ofHours(2)))));
  }

  @Test
  void oneAnswerAndOneAfterTheDeadlineIsMarkedLate() {
    Instant deadline = NOW.plus(Duration.ofMinutes(10));
    assertFalse(DecisionRules.answerIsLate(State.SENT, deadline, NOW));
    assertTrue(DecisionRules.answerIsLate(State.SENT, deadline, NOW.plus(Duration.ofMinutes(11))));
    assertTrue(DecisionRules.answerIsLate(State.EXPIRED, deadline, NOW));
    assertEquals("R-ISS-14", rule(() -> DecisionRules.answerIsLate(State.ANSWERED, deadline, NOW)));
    assertEquals("R-ISS-14", rule(() -> DecisionRules.answerIsLate(State.WITHDRAWN, deadline, NOW)));
    assertEquals(Optional.empty(), DecisionRules.reason("  "));
    assertEquals("R-ISS-14", rule(() -> DecisionRules.reason("x".repeat(501))));
  }

  @Test
  void everyNameAndNumberInTheWordsComesFromTheSystem() {
    Instant at = Instant.parse("2026-10-07T02:50:00Z"); // 08:20 in Colombo
    assertEquals("Can someone receive the delivery at about 08:20?",
        DecisionRules.question(Kind.RECEIVE_LATE, Optional.of(at), Optional.empty(), Optional.empty(), COLOMBO));
    assertEquals("Goods for OUT001 (1 unit) are in front of yours. Can the crew unload them at your dock to reach"
            + " your goods, and reload them?",
        DecisionRules.question(Kind.DOCK_HELP, Optional.empty(), Optional.of("OUT001"), Optional.of(1), COLOMBO));
    assertEquals("OUT001 will receive at about 08:20.",
        DecisionRules.driverNote(Kind.RECEIVE_LATE, "OUT001", Optional.of(at), Optional.empty(), COLOMBO));
    assertEquals("R-ISS-12", rule(() -> Kind.parse("keep_goods")));
  }
}
