package com.waypoint.dispatch.execution.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** A phone observation; validation and time decisions belong to PositionPolicy. */
public record PositionFix(Instant recordedAt, BigDecimal latitude, BigDecimal longitude,
    BigDecimal accuracyM, BigDecimal headingDeg, BigDecimal speedKmh) {}
