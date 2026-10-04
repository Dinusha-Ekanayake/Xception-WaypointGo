package com.waypoint.dispatch.platform.time;

/** Optional business time extension. Infrastructure and security keep the base clock. */
public interface TimeAdjustment {
  long offsetSeconds();
}
