package com.waypoint.dispatch.platform.observability;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;

/**
 * Minimal counters until a metrics backend is wired. Every edge case in
 * EDGE-CASES.md names a detection signal; this is where those signals live, so
 * that a case handled in code is also visible in production.
 */
@Component
public class Metrics {
  private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();

  public void increment(String name) {
    counters.computeIfAbsent(name, k -> new LongAdder()).increment();
  }

  public long count(String name) {
    LongAdder adder = counters.get(name);
    return adder == null ? 0L : adder.sum();
  }

  public Map<String, Long> snapshot() {
    Map<String, Long> out = new ConcurrentHashMap<>();
    counters.forEach((k, v) -> out.put(k, v.sum()));
    return out;
  }
}
