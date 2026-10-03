package com.waypoint.dispatch.notification.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.notification.domain.Delivery.Outcome;
import com.waypoint.dispatch.notification.domain.Delivery.PushResult;
import com.waypoint.dispatch.notification.domain.Delivery.RetryPolicy;
import com.waypoint.dispatch.notification.domain.Delivery.Status;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** What happens to a push after each attempt, and the template filling, with no clock read. */
class DeliveryTest {
  private static final Instant NOW = Instant.parse("2026-10-02T08:00:00Z");
  private static final RetryPolicy POLICY = new RetryPolicy(3, Duration.ofSeconds(30), Duration.ofMinutes(2));

  @Test
  void anAcceptedPushIsSent() {
    Outcome o = Delivery.after(PushResult.ACCEPTED, 0, POLICY, NOW, 0.5);

    assertEquals(Status.SENT, o.status());
    assertEquals(1, o.attempts());
    assertEquals(Optional.empty(), o.nextAttemptAt());
  }

  @Test
  void aRetryableFailureWaitsLongerEachTimeThenDies() {
    Outcome first = Delivery.after(PushResult.RETRYABLE, 0, POLICY, NOW, 0.5);
    Outcome second = Delivery.after(PushResult.RETRYABLE, 1, POLICY, NOW, 0.5);
    Outcome third = Delivery.after(PushResult.RETRYABLE, 2, POLICY, NOW, 0.5);

    assertEquals(Status.FAILED, first.status());
    assertEquals(Optional.of(NOW.plusSeconds(30)), first.nextAttemptAt());
    assertEquals(Optional.of(NOW.plusSeconds(60)), second.nextAttemptAt());
    assertEquals(Status.DEAD, third.status(), "PLT-03: dead after the last attempt, never retried forever");
    assertEquals(3, third.attempts());
    assertEquals(Optional.empty(), third.nextAttemptAt());
  }

  @Test
  void theBackoffIsCappedAndJittered() {
    assertEquals(Duration.ofMinutes(2), POLICY.backoff(10, 0.5));
    assertEquals(Duration.ofSeconds(24), POLICY.backoff(1, 0.0));
    assertEquals(Duration.ofSeconds(36), POLICY.backoff(1, 1.0));
  }

  @Test
  void aGoneSubscriptionIsDeadAndExpiredAtOnce() {
    Outcome o = Delivery.after(PushResult.GONE, 0, POLICY, NOW, 0.5);

    assertEquals(Status.DEAD, o.status());
    assertTrue(o.expireSubscription(), "NOT-01: never tried again");
  }

  @Test
  void aRejectedRequestIsDeadWithoutExpiringTheSubscription() {
    Outcome o = Delivery.after(PushResult.REJECTED, 0, POLICY, NOW, 0.5);

    assertEquals(Status.DEAD, o.status());
    assertFalse(o.expireSubscription());
  }

  @Test
  void httpStatusesClassifyAsTheRfcsMeanThem() {
    assertEquals(PushResult.ACCEPTED, Delivery.classify(201));
    assertEquals(PushResult.GONE, Delivery.classify(404));
    assertEquals(PushResult.GONE, Delivery.classify(410));
    assertEquals(PushResult.RETRYABLE, Delivery.classify(429));
    assertEquals(PushResult.RETRYABLE, Delivery.classify(503));
    assertEquals(PushResult.REJECTED, Delivery.classify(400));
    assertEquals(PushResult.REJECTED, Delivery.classify(413));
  }

  @Test
  void aRetryPolicyNeedsAtLeastOneAttempt() {
    assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(0, Duration.ofSeconds(1), Duration.ofSeconds(1)));
    assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, Duration.ofSeconds(2), Duration.ofSeconds(1)));
  }

  @Test
  void aTemplateFillsKnownFactsAndReportsTheRest() {
    Template.Rendered r = Template.render("{a} and {b}, not {c}", Map.of("a", "one", "b", "$2 \\ x", "c", " "));

    assertEquals("one and $2 \\ x, not -", r.text());
    assertEquals(Set.of("c"), r.missing());
  }
}
