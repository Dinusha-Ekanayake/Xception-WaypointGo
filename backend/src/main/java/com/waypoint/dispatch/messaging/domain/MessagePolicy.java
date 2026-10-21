package com.waypoint.dispatch.messaging.domain;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Who may write what to whom on a trip's thread (issue #136). Pure: the caller
 * says how the actor belongs to the thread, and the clock's date is a parameter.
 *
 * <ul>
 *   <li>R-MSG-01: the dispatcher reads every message; anyone else reads messages
 *       for everyone, those addressed to them, and their own (row-level security).
 *   <li>R-MSG-02: the dispatcher writes to the driver, the loaders, one outlet or
 *       everyone. The driver writes to the dispatcher or to an outlet on its trip.
 *       A loader writes to the dispatcher or, when the trip has a vehicle, to its
 *       driver (a note about the load, R-MSG-12). A store manager writes to the
 *       dispatcher only.
 *   <li>R-MSG-03: every report, from whoever, is for the dispatcher alone.
 *   <li>R-MSG-04: a trip's thread takes posts until the end of the day after its
 *       service date, for late replies; then it is read only.
 *   <li>R-MSG-08: the trip's live channel (FF-04) opens when the driver starts
 *       the trip, after loading, and is the dispatcher's and the stores' whose
 *       orders on the trip are not all done. The dispatcher writes
 *       to it; a store writes to it only while its outlet is active. The driver
 *       and the loaders neither write to it nor read it.
 * </ul>
 */
public final class MessagePolicy {
  public static final int BODY_MAX = 1000;
  public static final int EXCERPT_MAX = 120;

  private MessagePolicy() {}

  public enum Role {
    DISPATCHER,
    LOADER,
    DRIVER,
    STORE_MANAGER;

    public String code() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  public enum To {
    DISPATCH,
    DRIVER,
    LOADER,
    OUTLET,
    ALL,
    CHANNEL;

    public String code() {
      return name().toLowerCase(Locale.ROOT);
    }

    public static To parse(String value) {
      try {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
      } catch (RuntimeException e) {
        throw invalid("to must be one of dispatch, driver, loader, outlet, all, channel");
      }
    }
  }

  /** The kinds of report a thread carries, as stored. */
  public static final Set<String> REPORT_TYPES =
      Set.of(
          "loading_shortfall", "vehicle_fault", "road_disruption", "failed_delivery", "damaged_goods",
          "receipt_dispute", "stock_discrepancy", "late", "other", "store_closed", "cannot_unload");

  /**
   * How the actor belongs to the thread. A person may belong more than one way
   * (an admin overseeing a depot is also its dispatcher); the strongest part is
   * the one they write as.
   */
  public record Membership(boolean dispatcher, boolean loader, boolean driver, List<String> outlets) {
    public Membership {
      outlets = List.copyOf(outlets);
    }

    public Optional<Role> role() {
      if (dispatcher) return Optional.of(Role.DISPATCHER);
      if (driver) return Optional.of(Role.DRIVER);
      if (loader) return Optional.of(Role.LOADER);
      if (!outlets.isEmpty()) return Optional.of(Role.STORE_MANAGER);
      return Optional.empty();
    }
  }

  /** A post the caller wants to make, already parsed. A voice note may stand in for the text. */
  public record Post(String body, To to, Optional<String> outletId, Optional<String> reportType, boolean voice) {}

  /**
   * Checks a post against R-MSG-02 to R-MSG-04 and returns the cleaned body.
   *
   * @param threadOutlets the subject's outlets
   * @param hasVehicle the subject has a vehicle, so a driver to write to
   */
  public static String check(
      Role role, Post post, List<String> threadOutlets, boolean hasVehicle, LocalDate serviceDate, LocalDate today) {
    return check(role, post, threadOutlets, hasVehicle, false, false, serviceDate, today);
  }

  /**
   * As above, for a thread with a live channel.
   *
   * @param channelOpen the driver has started the trip, so its channel takes posts
   * @param inChannel one of the store's outlets is still active in the trip's channel
   */
  public static String check(
      Role role, Post post, List<String> threadOutlets, boolean hasVehicle, boolean channelOpen, boolean inChannel,
      LocalDate serviceDate, LocalDate today) {
    String body = post.voice() ? optionalBody(post.body()) : body(post.body());
    if (!open(serviceDate, today)) {
      throw new DomainException(
          ErrorCode.CONFLICT, "the trip's thread closed at the end of " + serviceDate.plusDays(1), List.of("R-MSG-04"));
    }
    if (post.to() == To.OUTLET) {
      String outlet = post.outletId().orElseThrow(() -> invalid("outletId is required when writing to an outlet"));
      if (!threadOutlets.contains(outlet)) {
        throw invalid(outlet + " is not on this trip");
      }
    } else if (post.outletId().isPresent()) {
      throw invalid("outletId is only for writing to an outlet");
    }
    if (post.reportType().isPresent()) {
      String type = post.reportType().get();
      if (!REPORT_TYPES.contains(type)) {
        throw invalid("report must be one of " + REPORT_TYPES);
      }
      if (role == Role.DISPATCHER) {
        throw rule("the dispatcher reads reports; a report comes from the loader, the driver or a store", "R-MSG-03");
      }
      if (post.to() != To.DISPATCH) {
        throw rule("a report is for the dispatcher only", "R-MSG-03");
      }
      return body;
    }
    boolean allowed =
        switch (role) {
          case DISPATCHER -> post.to() != To.DISPATCH && (post.to() != To.DRIVER || hasVehicle);
          case DRIVER -> post.to() == To.DISPATCH || post.to() == To.OUTLET;
          case LOADER -> post.to() == To.DISPATCH || (post.to() == To.DRIVER && hasVehicle);
          // R-MSG-16: a store writes to the dispatcher, the common room, or its own driver.
          case STORE_MANAGER -> post.to() == To.DISPATCH || post.to() == To.CHANNEL || (post.to() == To.DRIVER && hasVehicle);
        };
    if (allowed && post.to() == To.CHANNEL && !channelOpen) {
      throw new DomainException(
          ErrorCode.CONFLICT, "the trip's channel opens when the driver starts the trip", List.of("R-MSG-08"));
    }
    if (allowed && role == Role.STORE_MANAGER && post.to() == To.DRIVER && !inChannel) {
      throw new DomainException(
          ErrorCode.CONFLICT, "your deliveries on this trip are done, so you no longer write to its driver",
          List.of("R-MSG-16"));
    }
    if (allowed && role == Role.STORE_MANAGER && post.to() == To.CHANNEL && !inChannel) {
      throw new DomainException(
          ErrorCode.CONFLICT, "your deliveries on this trip are done, so its channel is read only for you",
          List.of("R-MSG-08"));
    }
    if (!allowed) {
      throw rule("a " + role.code().replace('_', ' ') + " cannot write to " + post.to().code(), "R-MSG-02");
    }
    return body;
  }

  /** R-MSG-04: open from publication to the end of the day after the service date. */
  public static boolean open(LocalDate serviceDate, LocalDate today) {
    return !today.isAfter(serviceDate.plusDays(1));
  }

  /** With a voice note the text is a caption, and may be empty. */
  public static String optionalBody(String raw) {
    String body = raw == null ? "" : raw.strip();
    if (body.length() > BODY_MAX) {
      throw invalid("body is longer than " + BODY_MAX + " characters");
    }
    return body;
  }

  public static final int VOICE_MAX_BYTES = 2 * 1024 * 1024;
  public static final int VOICE_MAX_MS = 120_000;
  public static final java.util.Set<String> VOICE_TYPES =
      java.util.Set.of("audio/webm", "audio/ogg", "audio/mp4", "audio/aac", "audio/mpeg");

  /** A voice note's audio: a recorder's own format, at most two minutes and 2 MB (R-MSG-06). */
  public static String voiceType(String contentType, int sizeBytes, Optional<Integer> durationMs) {
    String type = contentType == null ? "" : contentType.split(";")[0].strip().toLowerCase(java.util.Locale.ROOT);
    if (!VOICE_TYPES.contains(type)) {
      throw invalid("a voice note is audio: one of " + VOICE_TYPES);
    }
    if (sizeBytes < 1 || sizeBytes > VOICE_MAX_BYTES) {
      throw invalid("a voice note is at most " + (VOICE_MAX_BYTES / 1024 / 1024) + " MB");
    }
    if (durationMs.isPresent() && (durationMs.get() < 1 || durationMs.get() > VOICE_MAX_MS)) {
      throw invalid("a voice note is at most " + (VOICE_MAX_MS / 1000) + " seconds");
    }
    return type;
  }

  /** The text of a notification about a voice note with no caption. */
  public static String voiceExcerpt(String body, boolean report) {
    return body.isBlank() ? (report ? "Voice report" : "Voice message") : excerpt(body);
  }

  public static String body(String raw) {
    String body = raw == null ? "" : raw.strip();
    if (body.isEmpty()) {
      throw invalid("body is empty");
    }
    if (body.length() > BODY_MAX) {
      throw invalid("body is longer than " + BODY_MAX + " characters");
    }
    return body;
  }

  /** A dispatcher's reason for moving a post off the channel (R-MSG-08): 3 to 500 characters. */
  public static String redirectReason(String raw) {
    String reason = raw == null ? "" : raw.strip();
    if (reason.length() < 3 || reason.length() > 500) {
      throw rule("reason is 3 to 500 characters", "R-MSG-08");
    }
    return reason;
  }

  /** Longest note a dispatcher may give when resolving a report (R-MSG-07). */
  public static final int RESOLUTION_MAX = 500;

  /** FF-08: the ready reply a post came from, and whether its words were changed before sending. */
  /**
   * @param shown the ready replies on screen, in order, when the screen said (AI-J5); the picked one is at {@code rank}
   * @param explored the second and third were swapped on purpose, to measure the pull of position
   */
  public record ReplyUse(String templateId, int version, int rank, boolean edited, List<String> shown, boolean explored) {
    public ReplyUse {
      shown = List.copyOf(shown);
    }

    public ReplyUse(String templateId, int version, int rank, boolean edited) {
      this(templateId, version, rank, edited, List.of(), false);
    }
  }

  private static final java.util.regex.Pattern TEMPLATE_ID = java.util.regex.Pattern.compile("^[a-z_]+(\\.[a-z_]+)+$");

  /**
   * The ready reply named on a post, or empty when none is. A report never
   * comes from one: its words are the person's own.
   */
  public static Optional<ReplyUse> replyUse(
      String templateId, Integer version, Integer rank, boolean edited, boolean report) {
    if (templateId == null && version == null && rank == null) {
      return Optional.empty();
    }
    if (report) {
      throw invalid("a report is written, not taken from a ready reply");
    }
    if (templateId == null || templateId.length() > 64 || !TEMPLATE_ID.matcher(templateId).matches()) {
      throw invalid("templateId must name a ready reply, such as driver.bring_back");
    }
    if (version == null || version < 1 || version > 1000) {
      throw invalid("templateVersion must be between 1 and 1000");
    }
    if (rank == null || rank < 1 || rank > 10) {
      throw invalid("templateRank must be between 1 and 10");
    }
    return Optional.of(new ReplyUse(templateId, version, rank, edited));
  }

  /**
   * As {@link #replyUse(String, Integer, Integer, boolean, boolean)}, with the
   * replies that were on screen (comma separated, in order). A list that does not
   * hold the picked reply at its rank is not a record of what happened, so it is
   * refused rather than stored.
   */
  public static Optional<ReplyUse> replyUse(
      String templateId, Integer version, Integer rank, boolean edited, boolean report, String shown, boolean explored) {
    Optional<ReplyUse> use = replyUse(templateId, version, rank, edited, report);
    if (use.isEmpty() || shown == null || shown.isBlank()) {
      return use;
    }
    List<String> ids = List.of(shown.split(",")).stream().map(String::strip).toList();
    if (ids.size() > 10 || ids.stream().anyMatch(id -> id.length() > 64 || !TEMPLATE_ID.matcher(id).matches())) {
      throw invalid("templateShown must list at most 10 ready replies");
    }
    if (ids.size() < rank || !ids.get(rank - 1).equals(templateId)) {
      throw invalid("templateShown must hold the picked reply at its rank");
    }
    ReplyUse r = use.get();
    return Optional.of(new ReplyUse(r.templateId(), r.version(), r.rank(), r.edited(), ids, explored));
  }

  /** The note on a resolved report: optional, trimmed, at most {@link #RESOLUTION_MAX}. */
  public static Optional<String> resolutionNote(String raw) {
    String note = raw == null ? "" : raw.strip();
    if (note.length() > RESOLUTION_MAX) {
      throw rule("note is longer than " + RESOLUTION_MAX + " characters", "R-MSG-07");
    }
    return note.isEmpty() ? Optional.empty() : Optional.of(note);
  }

  /** A voice note's waveform from the phone: at most 64 bars, each 0 to 100. Anything else is dropped. */
  public static List<Integer> peaks(String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    String[] parts = raw.split(",");
    if (parts.length > 64) {
      return List.of();
    }
    List<Integer> out = new java.util.ArrayList<>(parts.length);
    for (String p : parts) {
      try {
        out.add(Math.max(0, Math.min(100, Integer.parseInt(p.strip()))));
      } catch (NumberFormatException e) {
        return List.of();
      }
    }
    return List.copyOf(out);
  }

  /** The first line, cut at a word, for a notification. Never the whole message. */
  public static String excerpt(String body) {
    String line = body.strip().lines().findFirst().orElse("");
    if (line.length() <= EXCERPT_MAX) {
      return line;
    }
    int cut = line.lastIndexOf(' ', EXCERPT_MAX - 1);
    return line.substring(0, cut > EXCERPT_MAX / 2 ? cut : EXCERPT_MAX - 1).strip() + ELLIPSIS;
  }

  /** U+2026, written as an escape so no file encoding can change it. */
  public static final String ELLIPSIS = "\u2026";

  /** What a raised issue becomes on the trip's thread: its report type and who reported it. */
  public record ReportOf(String reportType, Role reporter) {}

  /**
   * An issue raised from an event names no person, so its type says who
   * reported it: a shortfall is the loader's, a fault, a road delay or a failed
   * delivery the driver's, a dispute or a stock problem the store's. An issue a
   * person raised is theirs, whatever its type.
   */
  /**
   * R-EXE-31: what a store reads when the dispatcher takes it off a trip, in
   * plain words with the dispatcher's whole reason (at most 500 characters, so
   * well inside {@link #BODY_MAX}).
   */
  public static String skipNotice(String then, boolean wholeTrip, String reason) {
    String what =
        "CANCEL".equals(then)
            ? "Today's delivery is cancelled and the goods go back to the depot"
            : wholeTrip
                ? "The trip has ended before your stop. Your delivery moves to the next delivery day and goes first"
                : "Today's delivery moves to the next delivery day and goes first";
    return what + ". Reason: " + reason;
  }

  /**
   * R-EXE-32: the driver's report of a closed store or a stop that cannot unload,
   * as the dispatcher reads it (no words of the driver's travel with it).
   */
  public static String stopProblemReport(String problem) {
    return "CANNOT_UNLOAD".equals(problem) ? "Cannot unload here" : "Store closed, nobody to receive";
  }

  /** R-EXE-32: what the store reads when the driver reports at its door. */
  public static String stopProblemNotice(String problem) {
    String what =
        "CANNOT_UNLOAD".equals(problem)
            ? "The driver cannot unload at your store right now"
            : "The driver is at your store and found it closed";
    return what + ". The dispatcher decides what happens to today's delivery and will tell you here.";
  }

  public static Optional<ReportOf> reportOf(String issueType, Optional<Role> raisedBy) {
    String type =
        switch (issueType) {
          case "LOADING_SHORTFALL" -> "loading_shortfall";
          case "VEHICLE_FAULT" -> "vehicle_fault";
          case "ROAD_DISRUPTION" -> "road_disruption";
          case "FAILED_DELIVERY" -> "failed_delivery";
          case "LATE_DELIVERY" -> "late";
          case "DAMAGED_GOODS" -> "damaged_goods";
          case "RECEIPT_DISPUTE" -> "receipt_dispute";
          case "STOCK_DISCREPANCY" -> "stock_discrepancy";
          default -> "other";
        };
    Optional<Role> byType =
        switch (issueType) {
          case "LOADING_SHORTFALL" -> Optional.of(Role.LOADER);
          case "VEHICLE_FAULT", "ROAD_DISRUPTION", "FAILED_DELIVERY", "LATE_DELIVERY" -> Optional.of(Role.DRIVER);
          case "DAMAGED_GOODS", "RECEIPT_DISPUTE", "STOCK_DISCREPANCY" -> Optional.of(Role.STORE_MANAGER);
          default -> Optional.empty();
        };
    // The dispatcher raising an issue is a decision, not a report from the field.
    Optional<Role> reporter = raisedBy.isPresent() ? raisedBy.filter(r -> r != Role.DISPATCHER) : byType;
    return reporter.map(r -> new ReportOf(type, r));
  }

  private static DomainException invalid(String message) {
    return new DomainException(ErrorCode.VALIDATION_FAILED, message, List.of("R-MSG-02"));
  }

  private static DomainException rule(String message, String rule) {
    return new DomainException(ErrorCode.VALIDATION_FAILED, message, List.of(rule));
  }
}
