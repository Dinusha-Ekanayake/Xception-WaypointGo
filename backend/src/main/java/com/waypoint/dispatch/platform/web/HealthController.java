package com.waypoint.dispatch.platform.web;

import com.waypoint.dispatch.platform.db.Database;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness and readiness are separate on purpose: a slow or absent database must
 * not get the process killed, it must stop receiving traffic.
 */
@RestController
@RequestMapping("/health")
public class HealthController {
  private final Database database;

  public HealthController(Database database) {
    this.database = database;
  }

  @GetMapping("/live")
  public Map<String, String> live() {
    return Map.of("status", "up");
  }

  @GetMapping("/ready")
  public ResponseEntity<Map<String, String>> ready() {
    try {
      database.unscopedQuery("SELECT 1");
      return ResponseEntity.ok(Map.of("status", "up", "database", "up"));
    } catch (RuntimeException e) {
      return ResponseEntity.status(503).body(Map.of("status", "down", "database", "unreachable"));
    }
  }
}
