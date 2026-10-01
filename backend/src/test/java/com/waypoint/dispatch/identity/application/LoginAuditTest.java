package com.waypoint.dispatch.identity.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.waypoint.dispatch.identity.infrastructure.Argon2PasswordHasher;
import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.error.DomainException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * A failed sign-in is audited, and an audit row is kept for years. It must name
 * the account by id, never by the email that was typed.
 */
class LoginAuditTest {
  private static final String EMAIL = "someone@example.com";

  private final Database database = mock(Database.class);
  private final Argon2PasswordHasher hasher = mock(Argon2PasswordHasher.class);
  private final AuditLog audit = mock(AuditLog.class);
  private final LoginHandler handler =
      new LoginHandler(
          database,
          hasher,
          mock(LoginThrottle.class),
          mock(SessionRegistry.class),
          audit,
          new Metrics(new SimpleMeterRegistry()));

  @SuppressWarnings("unchecked")
  private void runTransactionsInline() {
    when(database.asModule(eq(ModuleRole.IAM), any(), any(Supplier.class)))
        .thenAnswer(call -> ((Supplier<Object>) call.getArgument(2)).get());
  }

  @Test
  void anUnknownEmailIsAuditedWithoutTheEmail() {
    runTransactionsInline();
    when(database.queryOne(anyString(), any())).thenReturn(null);

    assertThrows(DomainException.class, () -> handler.login(EMAIL, "wrong", null, "127.0.0.1"));

    AuditEntry entry = captured();
    assertEquals("wpt:iam:user:unknown", entry.resource());
    assertFalse(entry.toString().contains(EMAIL), entry.toString());
  }

  @Test
  void aWrongPasswordIsAuditedByUserId() {
    runTransactionsInline();
    UUID userId = UUID.randomUUID();
    when(database.queryOne(anyString(), any()))
        .thenReturn(Map.of("user_id", userId, "password_hash", "h", "is_active", true));
    when(hasher.matches(anyString(), anyString())).thenReturn(false);

    assertThrows(DomainException.class, () -> handler.login(EMAIL, "wrong", null, "127.0.0.1"));

    AuditEntry entry = captured();
    assertEquals("wpt:iam:user:" + userId, entry.resource());
    assertFalse(entry.toString().contains(EMAIL), entry.toString());
  }

  private AuditEntry captured() {
    ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).record(captor.capture());
    return captor.getValue();
  }
}
