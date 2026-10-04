package com.waypoint.dispatch.platform.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds a configured directory, tolerating where the process was launched from.
 *
 * <p>Running from the repository root and from {@code backend/} are both normal,
 * and a command that fails only because of the working directory wastes
 * someone's morning. The configured path wins; the obvious alternatives are
 * tried after it; and if none exists the error names every path that was tried
 * rather than only the last.
 */
public final class DirectoryLocator {
  private DirectoryLocator() {}

  public static Path resolve(String configured, String... alternatives) {
    List<Path> candidates = new ArrayList<>();
    candidates.add(Path.of(configured));
    for (String alternative : alternatives) {
      candidates.add(Path.of(alternative));
      candidates.add(Path.of("..", alternative));
    }
    for (Path candidate : candidates) {
      if (Files.isDirectory(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException(
        "Directory not found. Tried: "
            + candidates.stream().map(p -> p.toAbsolutePath().normalize().toString()).toList());
  }
}
