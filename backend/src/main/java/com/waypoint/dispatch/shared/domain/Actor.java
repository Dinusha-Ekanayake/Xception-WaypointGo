package com.waypoint.dispatch.shared.domain;

import java.util.UUID;

/**
 * Who is acting, and from which device.
 *
 * <p>This lives in the shared kernel rather than in the identity module even
 * though identity is what produces it. The command bus, the audit log and every
 * module need to name the caller, and platform is not allowed to depend on a
 * business module. It carries identity only: no roles, no scope, no policy.
 * Those are answered by asking the identity module, not by reading this.
 *
 * <p>Device identity is separate from user identity because a shared dock tablet
 * is one device used by many people, and audit has to tell them apart.
 */
public record Actor(UUID userId, UUID deviceId) {

  /**
   * The process itself: the outbox relay, a scheduled job. Its id is the same
   * constant {@code app.actor_is_system()} compares against in SQL. The version
   * nibble is zero, so no generated user id (v4 or v7) can ever equal it.
   */
  public static final UUID SYSTEM_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  public static final Actor SYSTEM = new Actor(SYSTEM_ID, null);

  public static Actor user(UUID userId) {
    return new Actor(userId, null);
  }

  public boolean isSystem() {
    return SYSTEM_ID.equals(userId);
  }
}
