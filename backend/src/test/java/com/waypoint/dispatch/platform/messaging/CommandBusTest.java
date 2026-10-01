package com.waypoint.dispatch.platform.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
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

  @Test
  void anUnknownKindIsNotFoundRatherThanForbidden() {
    CommandBus bus = bus(Optional.of(allowEverything()));

    DomainException thrown =
        assertThrows(
            DomainException.class,
            () ->
                bus.dispatch(
                    ACTOR,
                    new Command(UUID.randomUUID(), "vehicle:Teleport", null, null, null)));

    assertEquals(ErrorCode.NOT_FOUND, thrown.code());
  }

  // ---- fixtures ----

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

  @SuppressWarnings("unchecked")
  private static CommandBus bus(Optional<CommandAuthorizer> authorizer, CommandHandler handler) {
    ObjectMapper mapper = new ObjectMapper();
    Database database = mock(Database.class);
    // Run the work rather than pretending to: the point is what the bus decides
    // before and after it, not what PostgreSQL does.
    when(database.asModule(any(ModuleRole.class), any(), any(Supplier.class)))
        .thenAnswer(call -> ((Supplier<Object>) call.getArgument(2)).get());
    when(database.queryOne(anyString(), any(Object[].class))).thenReturn(null);

    return new CommandBus(
        List.of(handler),
        authorizer,
        new IdempotencyGuard(mapper),
        database,
        mock(AuditLog.class),
        new Metrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
        mapper);
  }

  private static final class RecordingHandler implements CommandHandler {
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
