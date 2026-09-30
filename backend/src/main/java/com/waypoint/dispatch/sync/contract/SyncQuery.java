package com.waypoint.dispatch.sync.contract;

import com.waypoint.dispatch.sync.contract.SyncViews.OperationView;
import java.util.List;
import java.util.UUID;

/** The only way another module reads offline operations. */
public interface SyncQuery {

  /** Operations from this device not yet applied, in sequence order. */
  List<OperationView> pendingFor(UUID deviceId);

  /** Conflicts and rejections the actor may review. */
  List<OperationView> conflictsFor(UUID userId);
}
