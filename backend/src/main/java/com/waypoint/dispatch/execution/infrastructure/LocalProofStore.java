package com.waypoint.dispatch.execution.infrastructure;

import com.waypoint.dispatch.execution.domain.ProofStore;
import com.waypoint.dispatch.platform.config.ExecutionProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Proof artifacts as files under {@code PROOF_DIR}.
 *
 * <p>The first adapter behind {@link ProofStore}. An S3-compatible one replaces
 * it without the module noticing. A write goes to a temporary file, is forced
 * to disk, and is then renamed into place, so a reader never sees half an image
 * and {@link #put} returning means the artifact survives a crash.
 *
 * <p>Keys are minted by the module, never taken from a request, and are checked
 * here all the same: nothing outside the directory can be named.
 */
@Component
public class LocalProofStore implements ProofStore {
  private static final Pattern KEY = Pattern.compile("[0-9a-f/-]{1,120}");

  private final Path root;

  public LocalProofStore(ExecutionProperties properties) {
    this.root = Path.of(properties.proofDir()).toAbsolutePath().normalize();
  }

  @Override
  public void put(String key, byte[] content, String contentType) {
    Path target = resolve(key);
    try {
      Files.createDirectories(target.getParent());
      Path temporary = Files.createTempFile(target.getParent(), "upload-", ".part");
      try {
        try (FileChannel channel =
            FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
          ByteBuffer buffer = ByteBuffer.wrap(content);
          while (buffer.hasRemaining()) {
            channel.write(buffer);
          }
          channel.force(true);
        }
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(temporary);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Could not store proof artifact", e);
    }
  }

  @Override
  public Optional<byte[]> get(String key) {
    Path target = resolve(key);
    try {
      return Files.isRegularFile(target) ? Optional.of(Files.readAllBytes(target)) : Optional.empty();
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read proof artifact", e);
    }
  }

  private Path resolve(String key) {
    if (key == null || !KEY.matcher(key).matches() || key.contains("//") || key.startsWith("/")) {
      throw new IllegalArgumentException("Not a proof store key");
    }
    Path target = root.resolve(key).normalize();
    if (!target.startsWith(root)) {
      throw new IllegalArgumentException("Not a proof store key");
    }
    return target;
  }
}
