package com.waypoint.dispatch.platform.db;

/**
 * The database role a transaction runs as. The pool connects as waypoint_app,
 * which is NOINHERIT and owns nothing, so every transaction must adopt a module
 * role before it can touch a table. Forgetting is a permission error, which is
 * the intended failure.
 */
public enum ModuleRole {
  REF("waypoint_ref"),
  IAM("waypoint_iam"),
  OPS("waypoint_ops"),
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
