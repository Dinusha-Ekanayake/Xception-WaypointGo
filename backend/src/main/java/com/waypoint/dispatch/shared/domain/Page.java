package com.waypoint.dispatch.shared.domain;

import java.util.List;
import java.util.Optional;

/**
 * One page of a keyset-paginated read.
 *
 * <p>Reads are never paginated with {@code OFFSET} (AGENTS.md, API and Error
 * Contract). The cursor is opaque to the caller and must never carry personal
 * data such as an email, because it travels in a query string and lands in
 * access logs.
 *
 * @param nextCursor empty when there are no more rows
 */
public record Page<T>(List<T> items, Optional<String> nextCursor) {

  public Page {
    items = List.copyOf(items);
  }

  public static <T> Page<T> last(List<T> items) {
    return new Page<>(items, Optional.empty());
  }
}
