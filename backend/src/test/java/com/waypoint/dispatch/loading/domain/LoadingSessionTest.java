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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LoadingSessionTest {

  private static final UUID TRIP = UUID.randomUUID();
  private static final UUID ISURU = UUID.randomUUID();
  private static final UUID KASUN = UUID.randomUUID();
  private static final UUID STOP1 = UUID.randomUUID();
  private static final UUID STOP2 = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2027-03-03T21:00:00Z");
  private static final ReleaseChecklist GOOD =
      new ReleaseChecklist(true, "WP-448213", true, Optional.of(new BigDecimal("3.5")));

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
    assertEquals(List.of("R-LOD-11"), e.violations());
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
  void aShortLineKeepsTheUnitsThatArrived() {
    ItemLine line = taken().flag(ISURU, STOP2, Optional.of(1), CheckStatus.SHORT, 1).changed().get(0);
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
    assertEquals(List.of("R-LOD-03"), e.violations());
  }

  @Test
  void releaseIsRefusedWhileAnyItemIsUnchecked() {
    LoadingSession s = taken().check(ISURU, STOP1, Optional.empty(), CheckStatus.LOADED).session();
    DomainException e = refused(() -> s.release(ISURU, GOOD));
    assertEquals(ErrorCode.CONFLICT, e.code());
    assertEquals(List.of("R-LOD-07"), e.violations());
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
    ReleaseChecklist bad = new ReleaseChecklist(false, " ", false, Optional.empty());
    DomainException e = refused(() -> bad.requireSatisfied(true));
    assertEquals(ErrorCode.VALIDATION_FAILED, e.code());
    assertTrue(e.getMessage().contains("secured"));
    assertTrue(e.getMessage().contains("seal number"));
    assertTrue(e.getMessage().contains("driver"));
    assertTrue(e.getMessage().contains("reefer"));
    assertEquals(List.of("R-LOD-10"), e.violations());
  }

  @Test
  void aWarmReeferBlocksAChilledTripOnly() {
    ReleaseChecklist warm = new ReleaseChecklist(true, "WP-1", true, Optional.of(new BigDecimal("4.1")));
    assertThrows(DomainException.class, () -> warm.requireSatisfied(true));
    warm.requireSatisfied(false);
    new ReleaseChecklist(true, "WP-1", true, Optional.of(new BigDecimal("4.0"))).requireSatisfied(true);
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
