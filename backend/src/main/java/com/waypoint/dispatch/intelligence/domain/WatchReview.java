package com.waypoint.dispatch.intelligence.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The watcher's AI layer, in shadow (docs/issues/watch-review/PLAN.md). Pure.
 * The items the rules raised for a depot go to a language model as facts of a
 * fixed shape, under short references rather than ids; its answer is used only
 * when it orders exactly the references sent.
 */
public final class WatchReview {
  private WatchReview() {}

  /** At most this many items are sent in one review: the most urgent by the rules. */
  public static final int MAX_ITEMS = 12;

  public static final String INSTRUCTION = String.join(" ",
      "You help a delivery dispatcher. These are the problems on today's trips that the rules raised, most urgent",
      "first by the rules. Answer in JSON only: {\"order\": [the references in the order you would handle them],",
      "\"why\": one short sentence}. Use only the references given. Use no number that is not in the facts.");

  /** One raised item as the model sees it. */
  public record Item(String ref, String kind, String severity, Integer minutesLeft, String outletId, String vehicleId) {}

  /** The facts sent, in the rules' order, under references A1, A2... */
  public static List<Item> items(List<Item> rulesOrder) {
    List<Item> out = new ArrayList<>();
    for (int i = 0; i < Math.min(MAX_ITEMS, rulesOrder.size()); i++) {
      Item it = rulesOrder.get(i);
      out.add(new Item("A" + (i + 1), it.kind(), it.severity(), it.minutesLeft(), it.outletId(), it.vehicleId()));
    }
    return out;
  }

  /** The facts as a map for the model, in the order given. */
  public static Map<String, Object> facts(List<Item> items) {
    List<Map<String, Object>> list = new ArrayList<>();
    for (Item it : items) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("ref", it.ref());
      m.put("kind", it.kind());
      m.put("severity", it.severity());
      if (it.minutesLeft() != null) m.put("minutesLeft", it.minutesLeft());
      m.put("outlet", it.outletId());
      m.put("vehicle", it.vehicleId());
      list.add(m);
    }
    return Map.of("items", list);
  }

  /** @param agreesFirst the model's first item is the rules' first */
  public record Verdict(List<String> order, boolean agreesFirst) {}

  /** The model's order, accepted only as an exact reordering of the references sent. */
  public static Optional<Verdict> judge(List<Item> sent, List<String> answered) {
    if (answered == null || answered.size() != sent.size()) return Optional.empty();
    if (!new HashSet<>(answered).equals(new HashSet<>(sent.stream().map(Item::ref).toList()))) return Optional.empty();
    if (new HashSet<>(answered).size() != answered.size()) return Optional.empty();
    return Optional.of(new Verdict(List.copyOf(answered), answered.get(0).equals(sent.get(0).ref())));
  }
}
