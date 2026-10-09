package com.waypoint.dispatch.execution.infrastructure;

import com.waypoint.dispatch.execution.domain.ProofStore;
import com.waypoint.dispatch.platform.storage.S3Objects;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Proof artifacts in the object store (roadmap M-01), chosen with
 * {@code OBJECT_STORE=s3}. New artifacts go to the store; one stored before the
 * switch is still read from the database, so switching loses no evidence, and
 * purging clears both (expand before contract: the database copies are moved
 * and dropped later, after every object's SHA-256 is checked).
 *
 * <p>When the store is down an upload fails with {@code DEPENDENCY_UNAVAILABLE},
 * the phone keeps it queued and says so (EXE-10, rule 9).
 */
@Component
@Primary
@ConditionalOnProperty(name = "app.storage.object-store", havingValue = "s3")
public class S3ProofStore implements ProofStore {
  static final String PREFIX = "proofs/";

  private final S3Objects objects;
  private final ObjectProvider<DatabaseProofStore> earlier;

  public S3ProofStore(S3Objects objects, ObjectProvider<DatabaseProofStore> earlier) {
    this.objects = objects;
    this.earlier = earlier;
  }

  @Override
  public void put(String key, byte[] content, String contentType) {
    objects.put(PREFIX + key, content, contentType);
  }

  @Override
  public Optional<byte[]> get(String key) {
    Optional<byte[]> stored = objects.get(PREFIX + key);
    if (stored.isPresent()) return stored;
    DatabaseProofStore database = earlier.getIfAvailable();
    return database == null ? Optional.empty() : database.get(key);
  }

  @Override
  public void purge(String key) {
    objects.delete(PREFIX + key);
    DatabaseProofStore database = earlier.getIfAvailable();
    if (database != null) database.purge(key);
  }
}
