package com.waypoint.dispatch.execution.domain;

import java.util.Optional;

/**
 * Where proof artifacts live. A port: the module names what it needs, and an
 * adapter (local files now, object storage later) supplies it.
 *
 * <p>{@link #put} returns only once the artifact is durable. If it throws, the
 * capture has not happened and the driver is told (EXE-10); nothing is recorded
 * as stored that is not.
 */
public interface ProofStore {

  /** Stores the artifact under {@code key}. Storing the same key again replaces it with the same bytes. */
  void put(String key, byte[] content, String contentType);

  Optional<byte[]> get(String key);
}
