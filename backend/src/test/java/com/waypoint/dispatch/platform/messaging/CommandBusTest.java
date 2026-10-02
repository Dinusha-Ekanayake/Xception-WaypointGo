package com.waypoint.dispatch.platform.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Whether a command runs at all, tested without a database.
 *
 * <p>This exists because the fail-closed check was written as a chain of
 * {@code Optional.map}, and mapping to null collapses to empty: with an
 * authorizer wired and the command allowed, the bus took the fail-closed branch
 * and denied everything. It was invisible for as long as nothing dispatched a
 * command. The three cases below are the whole decision, so a future rewrite of
 * that branch cannot get it wrong silently.
 */
class CommandBusTest {

  private static final Actor ACTOR = Actor.user(UUID.randomUUID());

  @Test
  void withNoAuthorizerWiredEveryCommandIsDenied() {
    CommandBus bus = bus(Optional.empty());

    DomainException thrown =
        assertThrows(DomainException.class, () -> bus.dispatch(ACTOR, command()));

    assertEquals(ErrorCode.FORBIDDEN, thrown.code());
    assertTrue(thrown.getMessage().contains("not configured"), thrown.getMessage());
  }

  @Test
  void anAllowedCommandRuns() {
    CommandBus bus = bus(Optional.of(allowEverything()));

    CommandResult result = bus.dispatch(ACTOR, command());

    assertFalse(result.replayed());
    assertEquals(Map.of("ok", true), result.value());
  }

  @Test
  void aDeniedCommandCarriesTheReasonAndNeverReachesTheHandler() {
    RecordingHandler handler = new RecordingHandler();
    CommandBus bus = bus(Optional.of(deny("Policy WaypointDriver has no matching Allow")), handler);

    DomainException thrown =
        assertThrows(DomainException.class, () -> bus.dispatch(ACTOR, command()));

    assertEquals(ErrorCode.FORBIDDEN, thrown.code());
    assertEquals("Policy WaypointDriver has no matching Allow", thrown.getMessage());
    assertFalse(handler.ran, "a denied command must not have run");
  }

  /**
   * A scope check fails inside the transaction, which rolls back its own audit
   * row. The bus must record the denial again once the transaction is gone,
   * because a 403 that leaves no trace is how an access problem goes unseen.
   */
  @Test
  void aDenialRaisedInsideTheTransactionIsAuditedAfterTheRollback() {
    AuditLog audit = mock(AuditLog.class);
    CommandHandler outOfScope =
        new RecordingHandler() {
          @Override
          public Object handle(Actor actor, Command command) {
            throw new DomainException(ErrorCode.FORBIDDEN, "outlet OUT009 is outside your scope");
          }
        };
    CommandBus bus = bus(Optional.of(allowEverything()), outOfScope, audit);

    DomainException thrown =
        assertThrows(DomainException.class, () -> bus.dispatch(ACTOR, command()));

    assertEquals(ErrorCode.FORBIDDEN, thrown.code());
    ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).recordStandalone(entry.capture());
    assertEquals("DENY", entry.getValue().decision());
    assertEquals("outlet OUT009 is outside your scope", entry.getValue().reason());
  }

  @Test
  void aRejectionThatIsNotADenialIsNotAuditedAsOne() {
    AuditLog audit = mock(AuditLog.class);
    CommandHandler invalid =
        new RecordingHandler() {
          @Override
          public Object handle(Actor actor, Command command) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "quantity must be positive");
          }
        };
    CommandBus bus = bus(Optional.of(allowEverything()), invalid, audit);

    assertThrows(DomainException.class, () -> bus.dispatch(ACTOR, command()));

    verify(audit, never()).recordStandalone(any());
  }

  @Test
  void anUnknownKindIsRefusedAndRecordedLikeAnyOtherDenial() {
    AuditLog audit = mock(AuditLog.class);
    CommandBus bus = bus(Optional.of(allowEverything()), new RecordingHandler(), audit);

    DomainException thrown =
        assertThrows(
            DomainException.class,
            () ->
                bus.dispatch(
                    ACTOR,
                    new Command(UUID.randomUUID(), "vehicle:Teleport", null, null, null)));

    // Deny by default: 403 plus an audit row. A 404 would tell a caller which
    // kinds exist.
    assertEquals(ErrorCode.FORBIDDEN, thrown.code());
    ArgumentCaptor<AuditEntry> denial = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).recordStandalone(denial.capture());
    assertEquals("DENY", denial.getValue().decision());
    assertTrue(denial.getValue().reason().contains("vehicle:Teleport"), denial.getValue().reason());
  }

  /**
   * SEC-03. The permission held when the command arrived and was gone by the time
   * its transaction opened. The second answer is the one that counts: the handler
   * does not run, and the refusal is recorded once the transaction has rolled back.
   */
  @Test
  void aPermissionRevokedBeforeTheTransactionOpensStopsTheCommand() {
    AuditLog audit = mock(AuditLog.class);
    RecordingHandler handler = new RecordingHandler();
    CommandAuthorizer revokedInFlight =
        new CommandAuthorizer() {
          @Override
          public Optional<String> denyReason(
              Actor actor, String action, String resource, Command command) {
            return Optional.empty();
          }

          @Override
          public Optional<String> denyReasonInTransaction(
              Actor actor, String action, String resource, Command command) {
            return Optional.of("No policy allows test:Do");
          }
        };
    CommandBus bus = bus(Optional.of(revokedInFlight), handler, audit);

    DomainException thrown =
        assertThrows(DomainException.class, () -> bus.dispatch(ACTOR, command()));

    assertEquals(ErrorCode.FORBIDDEN, thrown.code());
    assertFalse(handler.ran, "the handler must not run on a permission that no longer holds");
    ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).recordStandalone(entry.capture());
    assertEquals("No policy allows test:Do", entry.getValue().reason());
  }

  /**
   * A retried command that was refused for a rule it broke gets the same refusal.
   * Running it again could answer differently for a reason that has nothing to do
   * with the command.
   */
  @Test
  void aDeterministicRejectionIsStoredAsAReceipt() {
    CommandHandler invalid =
        new RecordingHandler() {
          @Override
          public Object handle(Actor actor, Command command) {
            throw new DomainException(
                ErrorCode.VALIDATION_FAILED, "quantity must be positive", List.of("R-ORD-01"));
          }
        };
    Database database = database();
    CommandBus bus = bus(Optional.of(allowEverything()), invalid, mock(AuditLog.class), database);

    assertThrows(DomainException.class, () -> bus.dispatch(ACTOR, command()));

    Object[] insert = receiptInsert(database);
    assertEquals(422, insert[5], "the status the API answered with");
    assertTrue(String.valueOf(insert[6]).contains("R-ORD-01"), "the body keeps the rule");
  }

  @Test
  void aDenialIsNeverStoredAsAReceiptBecauseAGrantMayArriveBeforeTheRetry() {
    CommandHandler outOfScope =
        new RecordingHandler() {
          @Override
          public Object handle(Actor actor, Command command) {
            throw new DomainException(ErrorCode.FORBIDDEN, "outlet OUT009 is outside your scope");
          }
        };
    Database database = database();
    CommandBus bus = bus(Optional.of(allowEverything()), outOfScope, mock(AuditLog.class), database);

    assertThrows(DomainException.class, () -> bus.dispatch(ACTOR, command()));

    verify(database, never()).update(org.mockito.ArgumentMatchers.contains("command_receipts"), any(Object[].class));
  }

  @Test
  void aStoredRejectionIsReplayedWithoutRunningTheHandlerAndWithItsRulesIntact() {
    RecordingHandler handler = new RecordingHandler();
    Database database = database();
    UUID id = UUID.randomUUID();
    Command command = new Command(id, "test:Do", null, null, null);
    String fingerprint = new IdempotencyGuard(new ObjectMapper()).fingerprint("test:Do", null);
    when(database.queryOne(anyString(), any(Object[].class)))
        .thenReturn(
            Map.of(
                "payload_hash",
                fingerprint,
                "result_status",
                422,
                "result_body",
                "{\"code\":\"VALIDATION_FAILED\",\"message\":\"quantity must be positive\","
                    + "\"violations\":[{\"rule\":\"R-ORD-01\",\"field\":\"quantity\","
                    + "\"message\":\"must be positive\"}]}"));
    CommandBus bus = bus(Optional.of(allowEverything()), handler, mock(AuditLog.class), database);

    DomainException thrown = assertThrows(DomainException.class, () -> bus.dispatch(ACTOR, command));

    assertEquals(ErrorCode.VALIDATION_FAILED, thrown.code());
    assertEquals("quantity must be positive", thrown.getMessage());
    assertEquals(List.of("R-ORD-01"), thrown.rules());
    assertEquals("quantity", thrown.violations().get(0).field());
    assertFalse(handler.ran, "a replayed rejection must not run the handler");
    // Nothing is stored a second time.
    verify(database, never()).update(org.mockito.ArgumentMatchers.contains("command_receipts"), any(Object[].class));
  }

  @Test
  void anAppliedCommandRecordsItsTargetCommandAndARedactedOutcome() {
    AuditLog audit = mock(AuditLog.class);
    CommandHandler handler =
        new RecordingHandler() {
          @Override
          public String resource(Command command) {
            return "wpt:ref:vehicle:VEH001";
          }

          @Override
          public Object handle(Actor actor, Command command) {
            return Map.of("status", "in_workshop", "contactEmail", "driver@example.lk");
          }
        };
    CommandBus bus = bus(Optional.of(allowEverything()), handler, audit);
    Command command = command();

    bus.dispatch(ACTOR, command, "5a1c6f0e-0000-4000-8000-000000000001");

    ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(entry.capture());
    AuditEntry row = entry.getValue();
    assertEquals(command.commandId(), row.commandId());
    assertEquals("ref:vehicle", row.targetType());
    assertEquals("VEH001", row.targetId());
    assertEquals("5a1c6f0e-0000-4000-8000-000000000001", row.correlationId());
    assertTrue(row.afterState().contains("in_workshop"), row.afterState());
    assertFalse(row.afterState().contains("driver@example.lk"), "personal data is redacted");
  }

  // ---- fixtures ----

  /** The arguments of the one rejection receipt insert: sql, command, actor, kind, hash, status, body. */
  private static Object[] receiptInsert(Database database) {
    return org.mockito.Mockito.mockingDetails(database).getInvocations().stream()
        .filter(call -> call.getMethod().getName().equals("update"))
        .filter(call -> String.valueOf(call.getArguments()[0]).contains("ON CONFLICT"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no rejection receipt was written"))
        .getArguments();
  }

  @SuppressWarnings("unchecked")
  private static Database database() {
    Database database = mock(Database.class);
    when(database.asModule(any(ModuleRole.class), any(), any(Supplier.class)))
        .thenAnswer(call -> ((Supplier<Object>) call.getArgument(2)).get());
    org.mockito.Mockito.doAnswer(
            call -> {
              ((Runnable) call.getArgument(2)).run();
              return null;
            })
        .when(database)
        .asModule(any(ModuleRole.class), any(), any(Runnable.class));
    when(database.queryOne(anyString(), any(Object[].class))).thenReturn(null);
    return database;
  }

  private static Command command() {
    return new Command(UUID.randomUUID(), "test:Do", null, null, null);
  }

  private static CommandAuthorizer allowEverything() {
    return (actor, action, resource, command) -> Optional.empty();
  }

  private static CommandAuthorizer deny(String reason) {
    return (actor, action, resource, command) -> Optional.of(reason);
  }

  private static CommandBus bus(Optional<CommandAuthorizer> authorizer) {
    return bus(authorizer, new RecordingHandler());
  }

  private static CommandBus bus(Optional<CommandAuthorizer> authorizer, CommandHandler handler) {
    return bus(authorizer, handler, mock(AuditLog.class));
  }

  private static CommandBus bus(
      Optional<CommandAuthorizer> authorizer, CommandHandler handler, AuditLog audit) {
    return bus(authorizer, handler, audit, database());
  }

  private static CommandBus bus(
      Optional<CommandAuthorizer> authorizer,
      CommandHandler handler,
      AuditLog audit,
      Database database) {
    ObjectMapper mapper = new ObjectMapper();
    return new CommandBus(
        List.of(handler),
        authorizer,
        new IdempotencyGuard(mapper),
        database,
        audit,
        new Metrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
        mapper);
  }

  private static class RecordingHandler implements CommandHandler {
    private boolean ran;

    @Override
    public String kind() {
      return "test:Do";
    }

    @Override
    public String action() {
      return "test:Do";
    }

    @Override
    public ModuleRole moduleRole() {
      return ModuleRole.REF;
    }

    @Override
    public Object handle(Actor actor, Command command) {
      ran = true;
      return Map.of("ok", true);
    }
  }
}
