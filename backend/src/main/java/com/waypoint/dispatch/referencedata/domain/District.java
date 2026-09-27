package com.waypoint.dispatch.referencedata.domain;

import com.waypoint.dispatch.shared.domain.DepotCode;

/**
 * A delivery district.
 *
 * <p>Decision D8: depot is a function of district. Verified in the supplied
 * data, where all 120 outlets sit in the depot their district maps to with zero
 * exceptions, and the official validator indexes travel by district alone.
 */
public record District(String name, DepotCode depot) {}
