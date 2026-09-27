package com.waypoint.dispatch.referencedata.domain;

import java.math.BigDecimal;

/**
 * Handling time budgeted per stop, by brand and dock type.
 *
 * <p>Assumption A-16: this is a planning allowance, not an observed duration.
 * Using it as a predicted service time would bias every estimate.
 */
public record ServiceAllowance(String brandCode, DockType dockType, BigDecimal minutes) {}
