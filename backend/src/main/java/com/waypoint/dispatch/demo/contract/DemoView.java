package com.waypoint.dispatch.demo.contract;

import java.time.Instant;

public record DemoView(boolean enabled, Instant now, long offsetSeconds, boolean banner,
    int simPointIntervalMs, int positionFlushMs, int speed, long rowVersion) {}
