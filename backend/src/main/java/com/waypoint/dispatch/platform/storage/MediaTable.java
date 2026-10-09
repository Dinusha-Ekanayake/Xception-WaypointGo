package com.waypoint.dispatch.platform.storage;

import com.waypoint.dispatch.platform.db.ModuleRole;

/**
 * A module's table of file bytes, as the object store backfill needs to know it
 * (roadmap M-01). A module declares its own, as it declares an event subscriber:
 * the platform job reaches the module's rows only through this, under the
 * module's own database role.
 *
 * <p>The table has {@code content bytea}, {@code object_key text} and
 * {@code moved_at timestamptz}. Every name here is a constant in code, never
 * from a request.
 */
public interface MediaTable {
  /** For logs and metrics, such as {@code issues.attachment_content}. */
  String table();

  ModuleRole moduleRole();

  /** The column whose value names the object, after {@link #prefix()}. */
  String idColumn();

  /** Where in the bucket the module's files live, such as {@code issues/}. */
  String prefix();

  /** The column holding the stored file's SHA-256 in hex, or null when the table has none. */
  default String sha256Column() {
    return "sha256";
  }

  /** Rows still holding a file worth moving: one not cleared past retention or removed. */
  String liveCondition();
}
