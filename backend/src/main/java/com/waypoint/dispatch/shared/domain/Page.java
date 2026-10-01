package com.waypoint.dispatch.shared.domain;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * One page of a keyset-paginated read.
 *
 * <p>Reads are never paginated with {@code OFFSET} (AGENTS.md, API and Error
 * Contract). The cursor is opaque to the caller and must never carry personal
 * data such as an email, because it travels in a query string and lands in
 * access logs. See {@link Cursor}.
 *
 * @param nextCursor empty when there are no more rows; serialised as {@code null}
 */
public record Page<T>(List<T> items, Optional<String> nextCursor) {
  public static final int DEFAULT_LIMIT = 50;
  public static final int MAX_LIMIT = 200;

  public Page {
    items = List.copyOf(items);
  }

  public static <T> Page<T> last(List<T> items) {
    return new Page<>(items, Optional.empty());
  }

  /** A requested page size, defaulted and capped so no caller can ask for everything. */
  public static int limit(Integer requested) {
    if (requested == null || requested <= 0) {
      return DEFAULT_LIMIT;
    }
    return Math.min(requested, MAX_LIMIT);
  }

  /**
   * Builds a page from a query that fetched {@code limit + 1} rows. The extra row
   * only proves there is a next page; it is not returned. This is how a last page
   * gets a {@code null} cursor instead of one that leads to an empty page.
   */
  public static <T> Page<T> fromOverfetch(List<T> rows, int limit, Function<T, String> cursorOf) {
    if (rows.size() <= limit) {
      return last(rows);
    }
    List<T> items = rows.subList(0, limit);
    return new Page<>(items, Optional.of(cursorOf.apply(items.get(limit - 1))));
  }

  /**
   * Pages a list already held in memory, such as a reference snapshot, on a
   * single string key. The same contract as a SQL keyset page, so a client cannot
   * tell which kind of read answered it.
   */
  public static <T> Page<T> slice(
      List<T> all, String after, Integer limit, Function<T, String> keyOf) {
    int size = limit(limit);
    List<String> cursor = Cursor.decode(after, 1);
    String afterKey = cursor.isEmpty() ? null : cursor.get(0);
    List<T> rows =
        all.stream()
            .sorted(java.util.Comparator.comparing(keyOf))
            .filter(row -> afterKey == null || keyOf.apply(row).compareTo(afterKey) > 0)
            .limit(size + 1L)
            .toList();
    return fromOverfetch(rows, size, row -> Cursor.encode(keyOf.apply(row)));
  }
}
