package com.waypoint.dispatch.loading.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.loading.contract.LoadingViews.CheckStatus;
import com.waypoint.dispatch.loading.contract.LoadingViews.SessionStatus;
import com.waypoint.dispatch.loading.domain.LoadingSession.Phase;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LoadingSessionTest {

  @Test
  void loaderIssuesAreShortDamagedDoesNotFitAndMissing() {
    for (CheckStatus kind :
        List.of(CheckStatus.SHORT, CheckStatus.MISSING, CheckStatus.DAMAGED, CheckStatus.DOES_NOT_FIT)) {
      assertTrue(taken().flag(ISURU, STOP2, Optional.of(1), kind, 1).changed().get(0).flagged());
    }
    assertThrows(
        DomainException.class,
        () -> taken().flag(ISURU, STOP2, Optional.of(1), CheckStatus.PENDING, 1));
  }

  @Test
  void aShortItemKeepsTheUnitsThatArrived() {
    ItemLine line = taken().flag(ISURU, STOP2, Optional.of(1), CheckStatus.SHORT, 1).changed().get(0);
    assertEquals(CheckStatus.SHORT, line.status());
    assertEquals(3, line.loadedUnits(), "4 picked, 1 short: 3 go on the vehicle");
  }

  @Test
  void shortIsForOneItemWithSomeUnitsArrived() {
    assertEquals(ErrorCode.VALIDATION_FAILED,
        refused(() -> taken().flag(ISURU, STOP1, Optional.empty(), CheckStatus.SHORT, 1)).code(),
        "a whole order cannot be short; report it missing");
    assertEquals(ErrorCode.VALIDATION_FAILED,
        refused(() -> taken().flag(ISURU, STOP2, Optional.of(1), CheckStatus.SHORT, 4)).code(),
        "none arrived is missing, not short");
  }

  private static final UUID TRIP = UUID.randomUUID();
  private static final UUID ISURU = UUID.randomUUID();
  private static final UUID KASUN = UUID.randomUUID();
  private static final UUID STOP1 = UUID.randomUUID();
  private static final UUID STOP2 = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2027-03-03T21:00:00Z");
  private static final ReleaseChecklist GOOD =
      new ReleaseChecklist(true, true, true);

  private static LoadingSession fresh(boolean chilled) {
    return new LoadingSession(
        TRIP,
        Phase.NOT_STARTED,
        Optional.empty(),
        chilled,
        List.of(
            ItemLine.unchecked(STOP1, 1, 1, "P-1", 2),
            ItemLine.unchecked(STOP1, 2, 1, "P-2", 3),
            ItemLine.unchecked(STOP2, 1, 2, "P-3", 4)),
        1);
  }

  private static LoadingSession taken() {
    return fresh(true).take(ISURU, "Isuru", Optional.of("LDR-00038"), NOW).session();
  }

  private static DomainException refused(Runnable action) {
    return assertThrows(DomainException.class, action::run);
  }

  @Test
  void takingATripLocksItToOneLoader() {
    LoadingSession s = taken();
    assertEquals(Phase.IN_PROGRESS, s.phase());
    assertEquals(ISURU, s.holder().orElseThrow().userId());

    DomainException e = refused(() -> s.take(KASUN, "Kasun", Optional.empty(), NOW));
    assertEquals(ErrorCode.CONFLICT, e.code());
    assertTrue(e.getMessage().contains("Isuru"));
    assertEquals(List.of("R-LOD-11"), e.rules());
  }

  @Test
  void aTripIdleForThirtyMinutesCanBeTakenOver() {
    LoadingSession s = taken();
    refused(() -> s.take(KASUN, "Kasun", Optional.empty(), NOW.plus(LoadingSession.IDLE_RELEASE).minusSeconds(1)));

    LoadingSession next = s.take(KASUN, "Kasun", Optional.empty(), NOW.plus(LoadingSession.IDLE_RELEASE)).session();
    assertEquals(KASUN, next.holder().orElseThrow().userId());
    assertEquals(NOW.plus(LoadingSession.IDLE_RELEASE), next.holder().orElseThrow().since());
  }

  @Test
  void idlenessCountsFromTheHoldersLastActivityNotFromTheTake() {
    LoadingSession s = taken().active(NOW.plusSeconds(20 * 60));
    assertTrue(s.holder().orElseThrow().isIdle(NOW.plusSeconds(50 * 60)));
    assertFalse(s.holder().orElseThrow().isIdle(NOW.plusSeconds(49 * 60)));
    refused(() -> s.take(KASUN, "Kasun", Optional.empty(), NOW.plusSeconds(40 * 60)));
  }

  @Test
  void retakingYourOwnTripChangesNothing() {
    LoadingSession s = taken();
    assertEquals(s, s.take(ISURU, "Isuru", Optional.empty(), NOW.plusSeconds(60)).session());
  }

  @Test
  void onlyTheHolderMayCheck() {
    LoadingSession s = taken();
    assertEquals(ErrorCode.CONFLICT,
        refused(() -> s.check(KASUN, STOP1, Optional.of(1), CheckStatus.LOADED)).code());
    assertEquals(ErrorCode.CONFLICT,
        refused(() -> fresh(false).check(ISURU, STOP1, Optional.of(1), CheckStatus.LOADED)).code());
  }

  @Test
  void handingBackKeepsTheChecksAndFreesTheTrip() {
    LoadingSession s = taken().check(ISURU, STOP2, Optional.of(1), CheckStatus.LOADED).session();
    LoadingSession back = s.handBack(ISURU).session();
    assertTrue(back.holder().isEmpty());
    assertEquals(CheckStatus.LOADED, back.items().get(2).status());
    // Another loader can now take it, and the earlier tick is still there.
    LoadingSession next = back.take(KASUN, "Kasun", Optional.empty(), NOW).session();
    assertEquals(KASUN, next.holder().orElseThrow().userId());
    assertEquals(CheckStatus.LOADED, next.items().get(2).status());
  }

  @Test
  void aCheckIsANewAttemptAndAnUndoIsAnother() {
    var loaded = taken().check(ISURU, STOP1, Optional.of(1), CheckStatus.LOADED);
    assertEquals(1, loaded.changed().size());
    assertEquals(1, loaded.changed().get(0).attempt());
    assertEquals(2, loaded.changed().get(0).loadedUnits());

    var undone = loaded.session().check(ISURU, STOP1, Optional.of(1), CheckStatus.PENDING);
    assertEquals(2, undone.changed().get(0).attempt());
    assertEquals(CheckStatus.PENDING, undone.changed().get(0).status());
    assertEquals(0, undone.changed().get(0).loadedUnits());
  }

  @Test
  void undoingAnUncheckedItemIsRefused() {
    assertEquals(ErrorCode.CONFLICT,
        refused(() -> taken().check(ISURU, STOP1, Optional.of(1), CheckStatus.PENDING)).code());
  }

  @Test
  void aRetriedTickChangesNothing() {
    LoadingSession s = taken().check(ISURU, STOP1, Optional.of(1), CheckStatus.LOADED).session();
    assertTrue(s.check(ISURU, STOP1, Optional.of(1), CheckStatus.LOADED).changed().isEmpty());
  }

  @Test
  void aCheckCannotCarryAFlag() {
    assertEquals(ErrorCode.VALIDATION_FAILED,
        refused(() -> taken().check(ISURU, STOP1, Optional.of(1), CheckStatus.DAMAGED)).code());
  }

  @Test
  void tickingAWholeOrderTicksItsUncheckedLinesButNeverClearsAFlag() {
    LoadingSession s = taken().flag(ISURU, STOP1, Optional.of(2), CheckStatus.MISSING, 3).session();
    var whole = s.check(ISURU, STOP1, Optional.empty(), CheckStatus.LOADED);
    assertEquals(1, whole.changed().size());
    assertEquals(1, whole.changed().get(0).lineNo());
    assertEquals(CheckStatus.MISSING, whole.session().items().get(1).status());
  }

  @Test
  void aFlaggedItemIsNotLoadedAndLoadingCarriesOn() {
    var flagged = taken().flag(ISURU, STOP1, Optional.of(2), CheckStatus.DAMAGED, 3);
    ItemLine line = flagged.changed().get(0);
    assertEquals(CheckStatus.DAMAGED, line.status());
    assertEquals(0, line.loadedUnits());
    assertEquals(3, LoadingSession.unitsAffected(flagged.changed()));
    // The loader keeps going.
    var next = flagged.session().check(ISURU, STOP1, Optional.of(1), CheckStatus.LOADED);
    assertEquals(1, next.changed().size());
  }

  @Test
  void aDamagedLineKeepsTheUnitsThatArrived() {
    ItemLine line = taken().flag(ISURU, STOP2, Optional.of(1), CheckStatus.DAMAGED, 1).changed().get(0);
    assertEquals(3, line.loadedUnits());
  }

  @Test
  void aFlagCannotExceedTheLine() {
    assertEquals(ErrorCode.VALIDATION_FAILED,
        refused(() -> taken().flag(ISURU, STOP1, Optional.of(1), CheckStatus.MISSING, 3)).code());
    assertEquals(ErrorCode.VALIDATION_FAILED,
        refused(() -> taken().flag(ISURU, STOP1, Optional.of(1), CheckStatus.LOADED, 1)).code());
  }

  @Test
  void flaggingAWholeOrderFlagsEveryLineInFull() {
    var flagged = taken().flag(ISURU, STOP1, Optional.empty(), CheckStatus.MISSING, 1);
    assertEquals(2, flagged.changed().size());
    assertEquals(5, LoadingSession.unitsAffected(flagged.changed()));
  }

  @Test
  void anItemOffTheCurrentPlanIsNotFound() {
    DomainException e = refused(() -> taken().check(ISURU, STOP1, Optional.of(9), CheckStatus.LOADED));
    assertEquals(ErrorCode.NOT_FOUND, e.code());
    assertEquals(List.of("R-LOD-03"), e.rules());
  }

  @Test
  void releaseIsRefusedWhileAnyItemIsUnchecked() {
    LoadingSession s = taken().check(ISURU, STOP1, Optional.empty(), CheckStatus.LOADED).session();
    DomainException e = refused(() -> s.release(ISURU, GOOD));
    assertEquals(ErrorCode.CONFLICT, e.code());
    assertEquals(List.of("R-LOD-07"), e.rules());
    assertTrue(e.getMessage().contains("1 item in 1 order"));
  }

  @Test
  void flaggedItemsDoNotBlockRelease() {
    LoadingSession s =
        taken()
            .check(ISURU, STOP1, Optional.empty(), CheckStatus.LOADED).session()
            .flag(ISURU, STOP2, Optional.of(1), CheckStatus.MISSING, 4).session();
    assertEquals(SessionStatus.READY, s.status());
    LoadingSession released = s.release(ISURU, GOOD);
    assertEquals(Phase.RELEASED, released.phase());
    assertTrue(released.holder().isEmpty());
    assertEquals(SessionStatus.COMPLETED, released.status());
  }

  @Test
  void aReleasedTripTakesNoMoreWork() {
    LoadingSession released =
        taken().check(ISURU, STOP1, Optional.empty(), CheckStatus.LOADED).session()
            .check(ISURU, STOP2, Optional.empty(), CheckStatus.LOADED).session()
            .release(ISURU, GOOD);
    assertEquals(ErrorCode.CONFLICT, refused(() -> released.take(ISURU, "Isuru", Optional.empty(), NOW)).code());
  }

  @Test
  void theChecklistNamesEveryFailure() {
    ReleaseChecklist bad = new ReleaseChecklist(false, false, false);
    DomainException e = refused(bad::requireSatisfied);
    assertEquals(ErrorCode.VALIDATION_FAILED, e.code());
    assertTrue(e.getMessage().contains("doors"));
    assertTrue(e.getMessage().contains("secured"));
    assertTrue(e.getMessage().contains("driver"));
    assertEquals(List.of("R-LOD-10"), e.rules());
  }

  @Test
  void theThreeChecksAlsoReleaseAChilledTrip() {
    LoadingSession ready = fresh(true).take(ISURU, "Isuru", Optional.empty(), NOW).session()
        .check(ISURU, STOP1, Optional.empty(), CheckStatus.LOADED).session()
        .check(ISURU, STOP2, Optional.empty(), CheckStatus.LOADED).session();
    assertEquals(Phase.RELEASED, ready.release(ISURU, GOOD).phase());
  }

  @Test
  void statusFollowsTheWork() {
    assertEquals(SessionStatus.NOT_STARTED, fresh(false).status());
    assertEquals(SessionStatus.IN_PROGRESS, taken().status());
    assertEquals(SessionStatus.BLOCKED,
        taken().flag(ISURU, STOP1, Optional.of(1), CheckStatus.MISSING, 2).session().status());
    assertFalse(taken().neverStarted());
    assertTrue(fresh(false).neverStarted());
  }

  @Test
  void theLastStopLoadsFirst() {
    assertEquals(List.of(3, 2, 1), LoadingSession.loadOrder(List.of(1, 3, 2, 3)));
  }
}
