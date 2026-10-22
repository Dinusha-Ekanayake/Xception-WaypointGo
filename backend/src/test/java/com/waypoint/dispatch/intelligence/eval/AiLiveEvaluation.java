package com.waypoint.dispatch.intelligence.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.intelligence.application.DecisionPort;
import com.waypoint.dispatch.intelligence.domain.IssueTriage;
import com.waypoint.dispatch.intelligence.domain.PromptFacts;
import com.waypoint.dispatch.intelligence.domain.PromptNames;
import com.waypoint.dispatch.intelligence.domain.ReportTriage;
import com.waypoint.dispatch.intelligence.domain.WatchReview;
import com.waypoint.dispatch.intelligence.infrastructure.JevDecisions;
import com.waypoint.dispatch.intelligence.infrastructure.OpenAiCompatibleModel;
import com.waypoint.dispatch.platform.config.AiProperties;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.util.Clock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The AI parts measured against the real providers, by hand only: {@code AI_EVAL=1}
 * with the keys of {@code .env} exported. Never in CI, which must not call a paid
 * model. Drives the same prompt code the jobs use (masking, options, judging) over
 * a labelled set, and writes {@code target/ai-eval/result.json}. Asserts nothing:
 * it measures, and the numbers are read against the gate in R-ML-11.
 */
@EnabledIfEnvironmentVariable(named = "AI_EVAL", matches = "1")
class AiLiveEvaluation {
  /** Accounts the masking must hide, as KnownNames would read them. */
  static final PromptNames NAMES = PromptNames.of(List.of("Kasun Perera", "Nimali Silva", "Dilan Rajapaksha"));
  /** Anything personal that must never reach a provider. */
  static final List<Pattern> LEAKS = List.of(
      Pattern.compile("\\d{7,}"), Pattern.compile("\\d{2,3}[ -]\\d{3}[ -]\\d{4}"), Pattern.compile("@"),
      Pattern.compile("(?i)kasun|perera|nimali|silva|dilan|rajapaksha"), Pattern.compile("DRV-\\d"));

  final ObjectMapper json = new ObjectMapper();
  final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  final Metrics metrics = new Metrics(registry);
  final Map<String, Object> out = new LinkedHashMap<>();

  AiProperties properties(String provider, String model) {
    return new AiProperties(true, provider, env("GROQ_API_KEY"), env("OPENROUTER_API_KEY"), model, 100_000,
        Duration.ofSeconds(30), true, Optional.ofNullable(System.getenv("JEV_MODEL")).orElse("typesafe/jev-1.13"), true, false, 0.5);
  }

  static String env(String name) {
    return Optional.ofNullable(System.getenv(name)).orElse("");
  }

  record Row(String label, String lang, String kind, String text) {}

  static List<Row> rows() throws Exception {
    List<Row> rows = new ArrayList<>();
    try (BufferedReader in = new BufferedReader(new InputStreamReader(
        AiLiveEvaluation.class.getResourceAsStream("/ai-eval/reports.tsv"), StandardCharsets.UTF_8))) {
      in.readLine();
      for (String line; (line = in.readLine()) != null; ) {
        String[] f = line.split("\t", 4);
        if (f.length == 4) rows.add(new Row(f[0], f[1], f[2], f[3]));
      }
    }
    return rows;
  }

  @Test
  void jevOnTripReports() throws Exception {
    DecisionPort jev = new JevDecisions(properties("openrouter", ""), json, metrics, Clock.system());
    List<Map<String, Object>> results = new ArrayList<>();
    Map<String, Map<String, Integer>> confusion = new TreeMap<>();
    int answered = 0, correct = 0, leaks = 0;
    long seed = 1;
    for (Row r : rows()) {
      String masked = NAMES.session().mask(IssueTriage.state(r.text()).orElse(r.text()));
      List<String> leaked = LEAKS.stream().filter(p -> p.matcher(masked).find()).map(Pattern::pattern).toList();
      leaks += leaked.isEmpty() ? 0 : 1;
      Map<String, String> options = ReportTriage.shuffled(seed++);
      long started = System.nanoTime();
      Optional<DecisionPort.Choice> c = jev.choose(masked, ReportTriage.INSTRUCTIONS, options);
      long ms = (System.nanoTime() - started) / 1_000_000;
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("label", r.label());
      row.put("lang", r.lang());
      row.put("kind", r.kind());
      row.put("sent", masked);
      row.put("leaked", leaked);
      row.put("ms", ms);
      if (c.isPresent()) {
        answered++;
        String choice = c.get().option();
        boolean ok = choice.equals(r.label());
        correct += ok ? 1 : 0;
        row.put("choice", choice);
        row.put("inOptions", options.containsKey(choice));
        row.put("confidence", c.get().confidence());
        row.put("correct", ok);
        confusion.computeIfAbsent(r.label(), k -> new TreeMap<>()).merge(choice, 1, Integer::sum);
      } else {
        row.put("choice", null);
      }
      results.add(row);
      System.out.printf("%-14s %-9s %-7s -> %-14s %s%n", r.label(), r.lang(), r.kind(), row.get("choice"), ms + " ms");
    }
    out.put("jev", Map.of("model", env("JEV_MODEL"), "total", results.size(), "answered", answered,
        "correct", correct, "leaks", leaks, "confusion", confusion, "rows", results));
    write("jev.json");
  }

  static List<Row> chatRows() throws Exception {
    List<Row> rows = new ArrayList<>();
    try (BufferedReader in = new BufferedReader(new InputStreamReader(
        AiLiveEvaluation.class.getResourceAsStream("/ai-eval/chat.tsv"), StandardCharsets.UTF_8))) {
      in.readLine();
      for (String line; (line = in.readLine()) != null; ) {
        String[] f = line.split("\t", 4);
        if (f.length == 4) rows.add(new Row(f[0], f[1], f[2], f[3]));
      }
    }
    return rows;
  }

  /**
   * R-ML-43: Jev on chat messages, written by us in English, Sinhala, Tamil and
   * Singlish, with off-topic, spam, abuse, markup and instruction attempts. What
   * counts is the accuracy of the readings sure enough to steer the replies.
   */
  @Test
  void jevOnChat() throws Exception {
    DecisionPort jev = new JevDecisions(properties("openrouter", ""), json, metrics, Clock.system());
    List<Map<String, Object>> results = new ArrayList<>();
    int answered = 0, correct = 0, sure = 0, sureCorrect = 0;
    long seed = 1;
    for (Row r : chatRows()) {
      String masked = NAMES.session().mask(IssueTriage.state(r.text()).orElse(r.text()));
      Map<String, String> options = com.waypoint.dispatch.intelligence.domain.MessageTriage.shuffled(seed++);
      Optional<DecisionPort.Choice> c = jev.choose(masked, com.waypoint.dispatch.intelligence.domain.MessageTriage.INSTRUCTIONS, options);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("label", r.label());
      row.put("lang", r.lang());
      row.put("kind", r.kind());
      if (c.isPresent()) {
        answered++;
        boolean ok = c.get().option().equals(r.label());
        boolean steers = c.get().confidence() >= 0.5;
        correct += ok ? 1 : 0;
        sure += steers ? 1 : 0;
        sureCorrect += steers && ok ? 1 : 0;
        row.put("choice", c.get().option());
        row.put("confidence", c.get().confidence());
        row.put("correct", ok);
      }
      results.add(row);
      System.out.printf("%-18s %-9s %-10s -> %-18s %s%n", r.label(), r.lang(), r.kind(), row.get("choice"), row.get("confidence"));
    }
    System.out.printf("chat: %d answered, %d right; %d sure, %d of those right%n", answered, correct, sure, sureCorrect);
    out.put("chat", Map.of("total", results.size(), "answered", answered, "correct", correct, "sure", sure,
        "sureCorrect", sureCorrect, "rows", results));
    write("chat.json");
  }

  record Scenario(String name, List<WatchReview.Item> rulesOrder, String expectedFirstOutlet) {}

  static WatchReview.Item item(String kind, String severity, Integer minutes, String outlet, String vehicle) {
    return new WatchReview.Item(null, kind, severity, minutes, outlet, vehicle);
  }

  /** Cases where a dispatcher would agree on what comes first; the rules' order is as the watch sorts. */
  static List<Scenario> scenarios() {
    return List.of(
        new Scenario("failed stop beats a window 90 min out", List.of(
            item("FAILED_STOP", "CRITICAL", null, "OUT010", "VEH030"),
            item("WINDOW_AT_RISK", "MEDIUM", 90, "OUT020", "VEH031")), "OUT010"),
        new Scenario("window closing in 5 beats one in 60", List.of(
            item("WINDOW_AT_RISK", "HIGH", 5, "OUT011", "VEH030"),
            item("WINDOW_AT_RISK", "HIGH", 60, "OUT012", "VEH032")), "OUT011"),
        new Scenario("proof owed is last", List.of(
            item("RUNNING_LATE", "HIGH", 15, "OUT013", "VEH033"),
            item("PROOF_OWED", "MEDIUM", null, "OUT014", "VEH034"),
            item("WINDOW_AT_RISK", "MEDIUM", 120, "OUT015", "VEH035")), "OUT013"),
        new Scenario("six items, one critical failure in the middle", List.of(
            item("WINDOW_AT_RISK", "HIGH", 10, "OUT021", "VEH040"),
            item("FAILED_STOP", "CRITICAL", null, "OUT022", "VEH041"),
            item("RUNNING_LATE", "HIGH", 25, "OUT023", "VEH042"),
            item("WINDOW_AT_RISK", "MEDIUM", 70, "OUT024", "VEH043"),
            item("PROOF_OWED", "MEDIUM", null, "OUT025", "VEH044"),
            item("RUNNING_LATE", "MEDIUM", 50, "OUT026", "VEH045")), "OUT022"),
        new Scenario("twelve items", twelve(), "OUT050"));
  }

  static List<WatchReview.Item> twelve() {
    List<WatchReview.Item> l = new ArrayList<>();
    l.add(item("FAILED_STOP", "CRITICAL", null, "OUT050", "VEH050"));
    for (int i = 1; i < 12; i++) {
      l.add(item(i % 3 == 0 ? "PROOF_OWED" : "WINDOW_AT_RISK", "MEDIUM", i % 3 == 0 ? null : 30 + i * 10,
          "OUT" + (50 + i), "VEH" + (50 + i)));
    }
    return l;
  }

  @Test
  void watcherOrdersWhatTheRulesRaised() throws Exception {
    String provider = Optional.ofNullable(System.getenv("AI_PROVIDER")).orElse("groq");
    OpenAiCompatibleModel model =
        new OpenAiCompatibleModel(properties(provider, env("AI_MODEL")), json, metrics, Clock.system());
    List<Map<String, Object>> results = new ArrayList<>();
    for (Scenario s : scenarios()) {
      for (String order : List.of("rules", "shuffled")) {
        List<WatchReview.Item> given = new ArrayList<>(s.rulesOrder());
        if (order.equals("shuffled")) Collections.shuffle(given, new Random(s.name().hashCode()));
        List<WatchReview.Item> sent = WatchReview.items(given);
        String expected = sent.stream().filter(i -> i.outletId().equals(s.expectedFirstOutlet())).findFirst()
            .map(WatchReview.Item::ref).orElseThrow();
        String facts = json.writeValueAsString(WatchReview.facts(sent));
        List<String> firsts = new ArrayList<>();
        int valid = 0;
        String lastRaw = null, lastWhy = null;
        List<String> raws = new ArrayList<>();
        for (int run = 0; run < 3; run++) {
          Optional<String> raw = model.complete(WatchReview.INSTRUCTION, facts);
          lastRaw = raw.orElse(null);
          raws.add(lastRaw);
          Optional<List<String>> parsed = raw.flatMap(this::orderOf);
          String why = raw.flatMap(this::whyOf).orElse("");
          lastWhy = why;
          boolean faithful = why.isBlank() || PromptFacts.faithful(why, facts).isPresent();
          Optional<WatchReview.Verdict> v = parsed.flatMap(o -> WatchReview.judge(sent, o)).filter(x -> faithful);
          if (v.isPresent()) {
            valid++;
            firsts.add(v.get().order().get(0));
          } else {
            firsts.add(parsed.isPresent() && !faithful ? "unfaithful" : "invalid");
          }
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("scenario", s.name());
        row.put("given", order);
        row.put("expectedFirst", expected);
        row.put("firsts", firsts);
        row.put("valid", valid);
        row.put("rightFirst", firsts.stream().filter(expected::equals).count());
        row.put("stable", firsts.stream().distinct().count() == 1);
        row.put("facts", facts);
        row.put("lastWhy", lastWhy);
        row.put("raws", raws);
        results.add(row);
        System.out.printf("%-48s %-8s expected %-3s got %s%n", s.name(), order, expected, firsts);
      }
    }
    Map<String, Long> statuses = new TreeMap<>();
    registry.getMeters().stream().filter(m -> m instanceof io.micrometer.core.instrument.Timer
            && m.getId().getName().equals("waypoint.ai.call"))
        .forEach(m -> statuses.merge(m.getId().getTag("status"),
            (long) ((io.micrometer.core.instrument.Timer) m).count(), Long::sum));
    System.out.println("call statuses: " + statuses);
    out.put("watch", Map.of("provider", provider, "model", model.model(), "statuses", statuses, "rows", results));
    write("watch.json");
  }

  Optional<JsonNode> root(String answer) {
    int from = answer.indexOf('{');
    int to = answer.lastIndexOf('}');
    if (from < 0 || to <= from) return Optional.empty();
    try {
      return Optional.of(json.readTree(answer.substring(from, to + 1)));
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  Optional<List<String>> orderOf(String answer) {
    return root(answer).map(r -> {
      List<String> l = new ArrayList<>();
      r.path("order").forEach(n -> l.add(n.asText()));
      return l;
    });
  }

  Optional<String> whyOf(String answer) {
    return root(answer).map(r -> r.path("why").asText(""));
  }

  void write(String name) throws Exception {
    Path dir = Path.of("target", "ai-eval");
    Files.createDirectories(dir);
    Files.writeString(dir.resolve(name), json.writerWithDefaultPrettyPrinter().writeValueAsString(out));
  }
}
