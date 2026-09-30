package com.waypoint.dispatch.platform.db;

/**
 * The database role a transaction runs as. The pool connects as waypoint_app,
 * which is NOINHERIT and owns nothing, so every transaction must adopt a module
 * role before it can touch a table. Forgetting is a permission error, which is
 * the intended failure.
 *
 * <p>One role per module, each granted only its own schema plus read on the
 * {@code ref} kernel (decision D-B). The shared {@code waypoint_ops} role that
 * migration 007 created is retired: it stays in the database, unused, because
 * roles are cluster-wide and dropping one that another database still grants to
 * would fail.
 */
public enum ModuleRole {
  REF("waypoint_ref"),
  IAM("waypoint_iam"),
  ORDERING("waypoint_ordering"),
  PLANNING("waypoint_planning"),
  LOADING("waypoint_loading"),
  EXECUTION("waypoint_execution"),
  RECEIPT("waypoint_receipt"),
  ISSUES("waypoint_issues"),
  NOTIFICATION("waypoint_notification"),
  SYNC("waypoint_sync"),
  WAREHOUSE("waypoint_warehouse"),
  ML("waypoint_ml"),
  INTEGRATION("waypoint_integration");

  private final String roleName;

  ModuleRole(String roleName) {
    this.roleName = roleName;
  }

  public String roleName() {
    return roleName;
  }
}
