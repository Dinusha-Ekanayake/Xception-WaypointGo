package com.waypoint.dispatch.referencedata.domain;

import java.math.BigDecimal;

/**
 * Travel assumptions for one district, keyed by district alone (decision D8).
 *
 * <p>These feed the published trip time formula:
 * {@code depotToDistrict + interStop x (stops - 1) + sum(serviceAllowance)}.
 * The return journey is deliberately excluded; the budgets already allow for it.
 */
public record TravelProfile(
    String districtName,
    String roadClass,
    BigDecimal freeFlowKmh,
    BigDecimal depotToDistrictKm,
    BigDecimal depotToDistrictFreeflowMin,
    BigDecimal interStopKm,
    BigDecimal interStopFreeflowMin) {}
