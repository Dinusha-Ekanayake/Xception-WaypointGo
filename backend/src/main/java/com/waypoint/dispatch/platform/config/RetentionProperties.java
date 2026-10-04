package com.waypoint.dispatch.platform.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * How long bookkeeping is kept before the retention jobs remove it (issue #6).
 *
 * <p>None of this is an operational record. Orders, plans, deliveries and issues
 * are never deleted; this is the infrastructure around them.
 *
 * @param receiptDays command receipts. A receipt only has to outlive the longest
 *     time a client may replay a command, which for an offline driver is a shift
 *     plus a weekend, so the default is generous
 * @param outboxDays published outbox rows. A dead event is never purged
 * @param consumedDays consumer inbox rows. Never shorter than {@code outboxDays},
 *     or a replayed event could be applied a second time
 * @param jobRunDays records of scheduled job runs
 * @param loginAttemptDays sign-in attempts. Must cover the throttle window with
 *     room to spare
 * @param sessionGraceDays how long an expired session row is kept for forensics
 */
@Validated
@ConfigurationProperties(prefix = "app.retention")
public record RetentionProperties(
    @DefaultValue("30") @Min(1) @Max(3650) int receiptDays,
    @DefaultValue("14") @Min(1) @Max(3650) int outboxDays,
    @DefaultValue("30") @Min(1) @Max(3650) int consumedDays,
    @DefaultValue("90") @Min(1) @Max(3650) int jobRunDays,
    @DefaultValue("30") @Min(1) @Max(3650) int loginAttemptDays,
    @DefaultValue("7") @Min(0) @Max(3650) int sessionGraceDays) {

  @AssertTrue(message = "app.retention.consumed-days must not be shorter than outbox-days")
  public boolean isInboxOutlivesOutbox() {
    return consumedDays >= outboxDays;
  }
}
