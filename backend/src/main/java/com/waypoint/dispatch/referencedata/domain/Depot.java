package com.waypoint.dispatch.referencedata.domain;

import com.waypoint.dispatch.shared.domain.DepotCode;
import java.time.ZoneId;

/** A distribution centre. Identified by its natural code (decision D2). */
public record Depot(DepotCode code, String displayName, ZoneId timezone) {}
