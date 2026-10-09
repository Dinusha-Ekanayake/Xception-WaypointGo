package com.waypoint.dispatch.execution.infrastructure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.waypoint.dispatch.platform.storage.S3Objects;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** M-01: new proofs go to the store; older ones are still read from the database; a store outage is said. */
class S3ProofStoreTest {
  final S3Objects objects = mock(S3Objects.class);
  final DatabaseProofStore database = mock(DatabaseProofStore.class);
  @SuppressWarnings("unchecked")
  final ObjectProvider<DatabaseProofStore> provider = mock(ObjectProvider.class);
  final S3ProofStore store;

  S3ProofStoreTest() {
    when(provider.getIfAvailable()).thenReturn(database);
    store = new S3ProofStore(objects, provider);
  }

  @Test
  void aNewProofGoesToTheStoreUnderItsPrefix() {
    byte[] photo = {1, 2, 3};
    store.put("d1/photo-1", photo, "image/webp");
    verify(objects).put("proofs/d1/photo-1", photo, "image/webp");
  }

  @Test
  void aProofStoredBeforeTheSwitchIsStillReadFromTheDatabase() {
    when(objects.get("proofs/old")).thenReturn(Optional.empty());
    when(database.get("old")).thenReturn(Optional.of(new byte[] {9}));
    assertArrayEquals(new byte[] {9}, store.get("old").orElseThrow());
    when(objects.get("proofs/new")).thenReturn(Optional.of(new byte[] {7}));
    assertArrayEquals(new byte[] {7}, store.get("new").orElseThrow());
  }

  @Test
  void purgingClearsBothCopies() {
    store.purge("d1/photo-1");
    verify(objects).delete("proofs/d1/photo-1");
    verify(database).purge("d1/photo-1");
  }

  @Test
  void aStoreThatIsDownFailsTheUploadVisibly() {
    when(objects.get("proofs/x")).thenThrow(new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE, "down"));
    DomainException e = assertThrows(DomainException.class, () -> store.get("x"));
    assertTrue(e.code() == ErrorCode.DEPENDENCY_UNAVAILABLE);
  }
}
