package com.waypoint.dispatch.intelligence.contract;

import java.util.List;
import java.util.Optional;

/**
 * Issue #269 and docs/issues/decision-suggestions/PLAN.md: what to do about a
 * situation on the road, read by Live's "Needs you" and by the trip's chat.
 */
public final class PlaybookViews {
  private PlaybookViews() {}

  /** The dispatcher records which suggestions were shown and which, if any, was taken. */
  public static final String RECORD = "ml:RecordSuggestion";

  /** The dispatcher takes a message read automatically off the trip's Next step card, with a reason (R-ML-35). */
  public static final String DISMISS_SIGNAL = "ml:DismissSignal";

  public record MessageView(String to, String template) {}

  /** One situation's steps and ready message; {@code message} is empty when there is nobody useful to write to. */
  public record PlaybookView(String situation, List<String> steps, Optional<MessageView> message) {}

  /**
   * @param chance 0 to 1, summing to 1 over the suggestions offered
   * @param chosenTimes once chances are learned: how often this was taken here, of {@code shownTimes} times shown
   */
  public record SuggestionView(
      String action, double chance, Optional<String> warning, Optional<Integer> chosenTimes,
      Optional<Integer> shownTimes) {}

  /**
   * The suggestions for one stop in one situation.
   *
   * @param basis {@code rules} until learned chances exist (PLAN, "How it works", 3)
   * @param version the catalogue's version, sent back when the dispatcher decides
   */
  public record SuggestionsView(
      String situation, String deliveryId, String basis, int version, List<SuggestionView> suggestions,
      PlaybookView playbook) {}

  /**
   * One trip's outlook (decision suggestions part 2, R-ML-34): the chance of each
   * decision about the trip and of each outcome for every stop still waiting.
   *
   * @param situation the trip's situation, when a breakdown, a blocked road or running late is on it
   * @param trip End trip, Wait and Carry on, most likely first; empty with no trip situation
   * @param signals what it was made from, so the screen can say where a chip came from
   * @param messagesRead whether drivers' and stores' own words are read for it (R-ML-35); false, it stands on reports alone
   * @param unreadMessages words that could not be read: waiting over a minute, or given up on (rule 9)
   * @param room the trip's problem room (R-ML-36): each store's state and the one next step; empty with no problem open
   */
  public record TripOutlookView(
      String tripId, int version, Optional<String> situation, List<ChanceView> trip, List<SignalView> signals,
      List<StopOutlookView> stops, boolean messagesRead, int unreadMessages, Optional<RoomView> room) {}

  /** R-ML-36: the problem, each store's row and the one next step. */
  public record RoomView(String situation, List<RowView> rows, StepView next) {}

  /**
   * @param state NOT_ASKED, MALL_CANNOT_WAIT, IN_WINDOW, ASKED, YES, NO, NO_ANSWER, DOCK_ASKED, DOCK_YES, DOCK_NO, SKIPPED, DELIVERED
   * @param at the time the state names: the deadline, the time agreed, the expected arrival
   */
  public record RowView(String deliveryId, String outletId, int sequence, String state, Optional<java.time.Instant> at,
      Optional<String> because) {}

  /**
   * @param kind ASK, END_TRIP, SKIP, CALL, ASK_DOCK, TELL_DRIVER_DOCK, WAIT, DONE
   * @param about for the dock, the skipped store whose goods are in the way
   * @param extraMinutes the extra time the ask used, with the other choices in {@code extras}
   * @param endTripToo a breakdown also offers "the driver can't go on: end trip"
   */
  public record StepView(String kind, List<TargetView> stops, Optional<TargetView> about, String why,
      Optional<Integer> extraMinutes, List<Integer> extras, boolean endTripToo) {}

  public record TargetView(String deliveryId, String outletId, Optional<java.time.Instant> receiveAt, long rowVersion) {}

  /** @param chance 0 to 1, summing to 1 over the list it is in */
  public record ChanceView(String action, double chance, Optional<String> warning) {}

  /**
   * @param action Skip to next day, Skip and cancel, Ask to receive later or Deliver as planned
   * @param learned the stop's own chances came from what dispatchers chose before
   * @param because the message ids of the signals behind it
   */
  public record StopOutlookView(
      String deliveryId, String outletId, int sequence, long rowVersion, String situation, List<ChanceView> chances,
      boolean learned, List<String> because) {}

  /**
   * @param source {@code report} for a report picked by its writer; {@code message} for one read automatically
   * @param authorRole who wrote a message read automatically (driver, store_manager), so the card can say so
   */
  public record SignalView(String messageId, String situation, String source, Optional<String> outletId,
      Optional<String> authorRole) {}
}
