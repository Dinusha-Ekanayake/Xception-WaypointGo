package com.waypoint.dispatch.sync.contract;

import com.waypoint.dispatch.sync.contract.SyncViews.OperationView;
import java.util.List;
import java.util.UUID;

/** The only way another module reads offline operations. */
public interface SyncQuery {

  /**
   * Operations from this device not yet applied, in sequence order. Read as the device's user,
   * because row-level security shows each account only its own operations.
   */
  List<OperationView> pendingFor(UUID userId, UUID deviceId);

  /** The actor's own conflicts and rejections. Reviewing someone else's waits on decision D-O. */
  List<OperationView> conflictsFor(UUID userId);
}
