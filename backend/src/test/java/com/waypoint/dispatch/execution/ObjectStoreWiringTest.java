package com.waypoint.dispatch.execution;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.waypoint.dispatch.execution.domain.ProofStore;
import com.waypoint.dispatch.execution.infrastructure.S3ProofStore;
import com.waypoint.dispatch.support.TestDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** M-01: with OBJECT_STORE=s3 the proof store is the object store, the database adapter kept behind it. */
@SpringBootTest(properties = {
    "app.storage.object-store=s3",
    "app.storage.endpoint=https://example.invalid",
    "app.storage.bucket=proofs-test",
    "app.storage.access-key-id=test",
    "app.storage.secret-access-key=test"})
@ExtendWith(TestDatabase.class)
class ObjectStoreWiringTest {
  @Autowired ProofStore proofs;

  @DynamicPropertySource
  static void databaseUrl(DynamicPropertyRegistry registry) {
    registry.add("app.database-url", TestDatabase::url);
  }

  @Test
  void theObjectStoreTakesTheProofs() {
    assertInstanceOf(S3ProofStore.class, proofs);
  }
}
