package com.waypoint.dispatch.identity.contract;

import java.util.UUID;

/**
 * Who is acting, and from which device. Device identity is separate from user
 * identity because a shared dock tablet is one device used by many people, and
 * audit has to tell them apart.
 */
public record CurrentActor(UUID userId, UUID deviceId) {}
