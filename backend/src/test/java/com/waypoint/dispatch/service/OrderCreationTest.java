package com.waypoint.dispatch.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.domain.ReferenceLoader;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class OrderCreationTest {
  @Test void newOrderUsesInsertOnlyAndUuid() {
    var jdbc = mock(JdbcTemplate.class);
    var loader = new ReferenceLoader("../data");
    loader.init();
    var service = new DispatchService(jdbc, mock(TransactionTemplate.class), loader,
        new ObjectMapper(), "1", "2026-02-13T15:30:00+05:30", "Waypoint2026!");
    var result = service.apply(new DispatchService.User("store@waypoint.local", "store", "OUT001"),
        Map.of("kind", "order", "temp", "ambient", "units", 1, "weight", 1, "volume", 1));
    String id = (String) result.get("order_id");
    assertDoesNotThrow(() -> UUID.fromString(id.substring(4)));
    verify(jdbc).update(eq("INSERT INTO orders(id,body) VALUES(?,?::jsonb)"), eq(id), anyString());
  }
}
