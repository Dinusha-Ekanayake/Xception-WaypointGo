package com.waypoint.dispatch.shared.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PageTest {

  @Test
  void aCursorRoundTripsAndIsOpaque() {
    String cursor = Cursor.encode("VEH001", "2027-05-03", "0190a1b2-0000-7000-8000-000000000000");
    assertFalse(cursor.contains("VEH001"), "not readable as plain text");
    assertFalse(cursor.contains("="), "url safe, unpadded");
    assertEquals(
        List.of("VEH001", "2027-05-03", "0190a1b2-0000-7000-8000-000000000000"),
        Cursor.decode(cursor, 3));
  }

  @Test
  void anAbsentCursorIsTheFirstPage() {
    assertTrue(Cursor.decode(null, 1).isEmpty());
    assertTrue(Cursor.decode("", 1).isEmpty());
  }

  @Test
  void aForgedCursorIs400() {
    DomainException e = assertThrows(DomainException.class, () -> Cursor.decode("@@not base64", 1));
    assertEquals(ErrorCode.BAD_REQUEST, e.code());
    assertThrows(DomainException.class, () -> Cursor.decode(Cursor.encode("a", "b"), 1));
  }

  @Test
  void theLimitIsDefaultedAndCapped() {
    assertEquals(Page.DEFAULT_LIMIT, Page.limit(null));
    assertEquals(Page.DEFAULT_LIMIT, Page.limit(0));
    assertEquals(Page.MAX_LIMIT, Page.limit(1_000_000));
    assertEquals(7, Page.limit(7));
  }

  @Test
  void slicingWalksEveryRowOnceAndEndsWithANullCursor() {
    List<String> ids = IntStream.rangeClosed(1, 25).mapToObj("OUT%03d"::formatted).toList();
    List<String> shuffled = new ArrayList<>(ids);
    java.util.Collections.reverse(shuffled);

    List<String> seen = new ArrayList<>();
    String after = null;
    int pages = 0;
    do {
      Page<String> page = Page.slice(shuffled, after, 10, Function.identity());
      seen.addAll(page.items());
      after = page.nextCursor().orElse(null);
      pages++;
    } while (after != null);

    assertEquals(ids, seen);
    assertEquals(3, pages);
  }

  @Test
  void anExactlyFullLastPageHasNoNextCursor() {
    Page<String> page = Page.slice(List.of("a", "b"), null, 2, Function.identity());
    assertEquals(List.of("a", "b"), page.items());
    assertTrue(page.nextCursor().isEmpty(), "no cursor that leads to an empty page");
  }
}
