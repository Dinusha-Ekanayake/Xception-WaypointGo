package com.waypoint.dispatch.intelligence.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * The question Jev is asked in shadow (roadmap AI-J1, R-ML-11): which kind of
 * problem a report's own words describe. Pure. The options are the issue types,
 * each in plain words, offered in an order shuffled per issue so the model's
 * known lean towards the first option cannot pass for agreement. The text is
 * masked as everything else that leaves (R-ML-10).
 */
public final class IssueTriage {
  public static final String INSTRUCTIONS =
      "A delivery worker reported a problem in their own words. Which kind of problem is it?";

  /** Every issue type, in plain words. Keys are the issue types in lower case. */
  public static final Map<String, String> OPTIONS = Map.of(
      "loading_shortfall", "Goods were missing or short when the vehicle was loaded at the depot.",
      "damaged_goods", "Goods arrived or were found damaged, broken, leaking or spoiled.",
      "failed_delivery", "The delivery could not be made: store closed, no one to receive, refused, no access.",
      "late_delivery", "The vehicle is or was late for the store's delivery window.",
      "vehicle_fault", "Something is wrong with the vehicle: breakdown, flat tyre, refrigeration, engine.",
      "road_disruption", "The road is blocked or slow: accident, flood, closure, traffic.",
      "receipt_dispute", "The store disagrees with what was handed over or signed for.",
      "stock_discrepancy", "The counted stock does not match what was ordered or invoiced.",
      "other", "None of the above.");

  private IssueTriage() {}

  /** The options in an order fixed by the issue, so a retry asks the same question. */
  public static Map<String, String> shuffled(long seed) {
    List<String> keys = new ArrayList<>(OPTIONS.keySet());
    Collections.sort(keys);
    Collections.shuffle(keys, new Random(seed));
    Map<String, String> out = new LinkedHashMap<>();
    keys.forEach(k -> out.put(k, OPTIONS.get(k)));
    return out;
  }

  /** The words worth asking about, masked; empty when there are none (a report made from an event). */
  public static Optional<String> state(String description) {
    if (description == null) return Optional.empty();
    String text = description.strip();
    if (text.length() < 8 || text.equalsIgnoreCase("Reported a problem")) return Optional.empty();
    return Optional.of(PromptFacts.mask(text));
  }
}
