package com.waypoint.dispatch.intelligence.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Jev in shadow on a trip-chat report (decision suggestions PR 5). Pure. Which
 * situation of the playbooks a report's own words describe; the options in plain
 * words, shuffled per report. A report whose writer picked its type is scored
 * against that type; one sent as "other" has no label and is never scored.
 */
public final class ReportTriage {
  public static final String INSTRUCTIONS =
      "A worker on a delivery trip wrote this report to the dispatcher. Which situation is it?";

  /** The report situations of the playbooks, in plain words. Keys are the situation names. */
  public static final Map<String, String> OPTIONS = Map.of(
      "STORE_CLOSED", "The store is closed or nobody is there to receive the goods.",
      "CANNOT_UNLOAD", "The vehicle is at the store but cannot unload: no space, no access, not accepted now.",
      "BREAKDOWN", "Something is wrong with the vehicle: breakdown, flat tyre, refrigeration, engine.",
      "ROAD_BLOCKED", "The road is blocked or slow: accident, flood, closure, traffic.",
      "RUNNING_LATE", "The vehicle is or will be late for a store's delivery window.",
      "GOODS_PROBLEM", "Goods are missing, short, damaged or not what was ordered.",
      "OTHER", "None of the above.");

  private ReportTriage() {}

  /** The options in an order fixed by the report, so a retry asks the same question. */
  public static Map<String, String> shuffled(long seed) {
    List<String> keys = new ArrayList<>(OPTIONS.keySet());
    Collections.sort(keys);
    Collections.shuffle(keys, new Random(seed));
    Map<String, String> out = new LinkedHashMap<>();
    keys.forEach(k -> out.put(k, OPTIONS.get(k)));
    return out;
  }

  /** The situation the writer picked, from the report type; "OTHER" when they picked none of these. */
  public static String label(String reportType) {
    return Playbooks.ofReport(reportType).map(Enum::name).filter(OPTIONS::containsKey).orElse("OTHER");
  }
}
