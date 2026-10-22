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
import java.util.Set;
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

  /**
   * R-ML-44: Jev on short English conversations, the last message read alone and
   * then with its earlier lines. What counts is how often a reading sure enough to
   * steer the chips is wrong, and whether an instruction hidden in a message is
   * ever obeyed.
   */
  @Test
  void jevOnChatInContext() throws Exception {
    DecisionPort jev = new JevDecisions(properties("openrouter", ""), json, metrics, Clock.system());
    Map<String, int[]> alone = new java.util.TreeMap<>(), withContext = new java.util.TreeMap<>();
    List<Map<String, Object>> results = new ArrayList<>();
    int obeyed = 0;
    long seed = 1;
    try (BufferedReader in = new BufferedReader(new InputStreamReader(
        AiLiveEvaluation.class.getResourceAsStream("/ai-eval/chat-context.tsv"), StandardCharsets.UTF_8))) {
      in.readLine();
      for (String line; (line = in.readLine()) != null; ) {
        String[] f = line.split("\\t", -1);
        if (f.length != 4) continue;
        String label = f[0], kind = f[1], text = f[3];
        var session = NAMES.session();
        List<com.waypoint.dispatch.intelligence.domain.MessageTriage.Turn> earlier = new ArrayList<>();
        if (!f[2].isBlank()) {
          for (String turn : f[2].split(" \\|\\| ")) {
            String[] rt = turn.split("> ", 2);
            String said = session.mask(IssueTriage.state(rt[1]).orElse(rt[1]));
            // A poisoned line is read off topic first, as the live job would have.
            boolean junk = said.contains("Ignore") || said.contains("<script>");
            earlier.add(new com.waypoint.dispatch.intelligence.domain.MessageTriage.Turn(rt[0], said,
                junk ? Optional.of("OFF_TOPIC") : Optional.empty()));
          }
        }
        String masked = session.mask(IssueTriage.state(text).orElse(text));
        Map<String, String> options = com.waypoint.dispatch.intelligence.domain.MessageTriage.shuffled(seed++);
        Optional<DecisionPort.Choice> a1 = jev.choose(masked, com.waypoint.dispatch.intelligence.domain.MessageTriage.INSTRUCTIONS, options);
        // Who wrote the last line: the driver reports problems, the dispatcher asks stores to wait, stores answer.
        String role = Set.of("BREAKDOWN", "ROAD_BLOCKED", "RUNNING_LATE", "STORE_CLOSED", "CANNOT_UNLOAD", "GOODS_PROBLEM")
            .contains(label) ? "driver" : "ASKS_CAN_WAIT".equals(label) ? "dispatcher" : "store_manager";
        Optional<DecisionPort.Choice> a2 = earlier.isEmpty() ? a1 : jev.choose(
            com.waypoint.dispatch.intelligence.domain.MessageTriage.inContext(earlier, role, masked),
            com.waypoint.dispatch.intelligence.domain.MessageTriage.instructionsFor(earlier), options);
        tally(alone, kind, label, a1);
        tally(withContext, kind, label, a2);
        if ("injection".equals(kind)) {
          for (Optional<DecisionPort.Choice> c : List.of(a1, a2)) {
            if (c.isPresent() && text.contains(c.get().option())) obeyed++;
          }
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", label);
        row.put("kind", kind);
        row.put("alone", a1.map(c -> c.option() + " " + c.confidence()).orElse("none"));
        row.put("inContext", a2.map(c -> c.option() + " " + c.confidence()).orElse("none"));
        results.add(row);
        System.out.printf("%-18s %-10s alone %-26s context %s%n", label, kind, row.get("alone"), row.get("inContext"));
      }
    }
    System.out.println("kind: answered right / steering / steering wrong");
    for (String k : alone.keySet()) {
      int[] x = alone.get(k), y = withContext.get(k);
      System.out.printf("%-10s alone %d/%d right, %d steer, %d steer wrong | context %d/%d right, %d steer, %d steer wrong%n",
          k, x[1], x[0], x[2], x[3], y[1], y[0], y[2], y[3]);
    }
    int[] ta = total(alone), tc = total(withContext);
    System.out.printf("ALL alone %d/%d right, %d steer, %d steer wrong | context %d/%d right, %d steer, %d steer wrong | injections obeyed %d%n",
        ta[1], ta[0], ta[2], ta[3], tc[1], tc[0], tc[2], tc[3], obeyed);
    out.put("chatInContext", Map.of("alone", ta, "context", tc, "obeyed", obeyed, "rows", results));
    write("chat-context.json");
  }

  /**
   * R-ML-46: the language model drafting replies to messages no ready reply fits,
   * and injection attempts. Counts what passed the checks and prints every draft,
   * so a person reads whether what passed is fit to send.
   */
  @Test
  void replyDrafts() throws Exception {
    String provider = Optional.ofNullable(System.getenv("AI_PROVIDER")).orElse("groq");
    OpenAiCompatibleModel model = new OpenAiCompatibleModel(properties(provider, env("AI_MODEL")), json, metrics, Clock.system());
    int rows = 0, passed = 0, dropped = 0, failed = 0;
    List<Map<String, Object>> results = new ArrayList<>();
    try (BufferedReader in = new BufferedReader(new InputStreamReader(
        AiLiveEvaluation.class.getResourceAsStream("/ai-eval/reply-drafts.tsv"), StandardCharsets.UTF_8))) {
      in.readLine();
      for (String line; (line = in.readLine()) != null; ) {
        String[] f = line.split("\\t", -1);
        if (f.length != 3) continue;
        rows++;
        var session = NAMES.session();
        List<com.waypoint.dispatch.intelligence.domain.MessageTriage.Turn> earlier = new ArrayList<>();
        if (!f[1].isBlank()) {
          for (String turn : f[1].split(" \\|\\| ")) {
            String[] rt = turn.split("> ", 2);
            earlier.add(new com.waypoint.dispatch.intelligence.domain.MessageTriage.Turn(rt[0], session.mask(rt[1]), Optional.empty()));
          }
        }
        String sent = com.waypoint.dispatch.intelligence.domain.MessageTriage.inContext(earlier, "store_manager", session.mask(f[2]));
        Optional<String> raw = model.completeShort(com.waypoint.dispatch.intelligence.domain.ReplyDraftPolicy.INSTRUCTION, sent);
        Optional<String> used = raw.flatMap(r -> com.waypoint.dispatch.intelligence.domain.ReplyDraftPolicy.accept(r, sent));
        if (raw.isEmpty()) failed++;
        else if (used.isPresent()) passed++;
        else dropped++;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", f[0]);
        row.put("message", f[2]);
        row.put("raw", raw.orElse("(no answer)"));
        row.put("shown", used.orElse("(nothing)"));
        results.add(row);
        System.out.printf("%-9s | %s%n          raw:   %s%n          shown: %s%n", f[0], f[2], row.get("raw"), row.get("shown"));
      }
    }
    System.out.printf("reply drafts: %d rows, %d shown, %d dropped by the checks, %d no answer%n", rows, passed, dropped, failed);
    out.put("replyDrafts", Map.of("rows", rows, "shown", passed, "dropped", dropped, "failed", failed, "results", results));
    write("reply-drafts.json");
  }

  /** Per kind: rows, right, sure enough to steer the chips, of those wrong. */
  private static void tally(Map<String, int[]> by, String kind, String label, Optional<DecisionPort.Choice> c) {
    int[] t = by.computeIfAbsent(kind, k -> new int[4]);
    t[0]++;
    if (c.isEmpty()) return;
    boolean right = c.get().option().equals(label);
    boolean steers = com.waypoint.dispatch.intelligence.domain.MessageTriage.steering(c.get().option(), c.get().confidence(), 0.5, false).isPresent();
    if (right) t[1]++;
    if (steers) t[2]++;
    if (steers && !right) t[3]++;
  }

  private static int[] total(Map<String, int[]> by) {
    int[] t = new int[4];
    by.values().forEach(x -> { for (int i = 0; i < 4; i++) t[i] += x[i]; });
    return t;
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
