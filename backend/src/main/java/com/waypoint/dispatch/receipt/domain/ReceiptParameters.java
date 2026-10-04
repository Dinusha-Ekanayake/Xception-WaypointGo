package com.waypoint.dispatch.receipt.domain;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Receipt's thresholds, read from effective-dated {@code receipt.parameters}
 * rows. A missing value refuses rather than falling back to a compiled-in one
 * (POL-10): a silent default would reintroduce an old window unnoticed.
 */
public record ReceiptParameters(Map<String, BigDecimal> values) {
  /** P-10. */
  public static final String AUTO_CLOSE_HOURS = "auto_close.hours";

  public ReceiptParameters {
    values = Map.copyOf(values);
  }

  public BigDecimal require(String key) {
    BigDecimal value = values.get(key);
    if (value == null) {
      throw new DomainException(
          ErrorCode.CONSTRAINT_VIOLATED,
          "receipt parameter " + key + " has no value in force; receipts refuse rather than guess",
          List.of("POL-10"));
    }
    return value;
  }

  public AutoClosePolicy autoClose() {
    BigDecimal hours = require(AUTO_CLOSE_HOURS);
    long minutes = hours.multiply(BigDecimal.valueOf(60)).longValueExact();
    return new AutoClosePolicy(Duration.ofMinutes(minutes));
  }
}
