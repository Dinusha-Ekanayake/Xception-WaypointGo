package com.waypoint.dispatch.execution.infrastructure;

import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.storage.MediaTable;
import org.springframework.stereotype.Component;

/**
 * The proofs' bytes table, for the object store backfill (M-01). Moved proofs
 * sit under the same key {@link S3ProofStore} writes new ones to, so one read
 * path serves both.
 */
@Component
class ProofMedia implements MediaTable {
  @Override
  public String table() {
    return "execution.proof_content";
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.EXECUTION;
  }

  @Override
  public String idColumn() {
    return "storage_key";
  }

  @Override
  public String prefix() {
    return S3ProofStore.PREFIX;
  }

  @Override
  public String sha256Column() {
    return null;
  }

  @Override
  public String liveCondition() {
    return "purged_at IS NULL";
  }
}
