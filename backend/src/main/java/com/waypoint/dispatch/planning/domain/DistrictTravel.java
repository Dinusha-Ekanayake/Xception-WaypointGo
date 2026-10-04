package com.waypoint.dispatch.planning.domain;

import java.math.BigDecimal;

/** Inputs to the published trip time formula, keyed by district alone (R-PLN-12). */
public record DistrictTravel(
    String district,
    BigDecimal outboundMinutes,
    BigDecimal interStopMinutes,
    BigDecimal outboundKm,
    BigDecimal interStopKm) {}
