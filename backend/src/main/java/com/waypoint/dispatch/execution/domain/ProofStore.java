package com.waypoint.dispatch.execution.domain;

import java.util.Optional;

/**
 * Where proof artifacts live. A port: the module names what it needs, and an
 * adapter supplies it: the database by default, local files, or object storage
 * later.
 *
 * <p>{@link #put} returns only once the artifact is durable. If it throws, the
 * capture has not happened and the driver is told (EXE-10); nothing is recorded
 * as stored that is not.
 */
public interface ProofStore {

  /** Stores the artifact under {@code key}. Storing the same key again replaces it with the same bytes. */
  void put(String key, byte[] content, String contentType);

  Optional<byte[]> get(String key);

  /**
   * Clears an artifact past its retention (P-14). Clearing one already cleared,
   * or never stored, does nothing.
   */
  void purge(String key);
}
