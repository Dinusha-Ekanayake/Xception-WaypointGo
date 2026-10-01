package com.waypoint.dispatch.loading.contract;

import com.waypoint.dispatch.loading.contract.LoadingViews.CheckStatus;
import java.util.Optional;
import java.util.UUID;

/**
 * Payloads of Loading's commands. Every one records device and user, because a
 * dock tablet is shared and the custody chain must name who touched the goods
 * (R-RCP-08).
 *
 * <p>Every command carries the trip's rowVersion as expectedVersion, and every
 * accepted command moves it on by exactly one.
 */
public final class LoadingCommands {
  private LoadingCommands() {}

  public static final String START = "loading:Start";
  public static final String CHECK = "loading:Check";
  public static final String SHORTFALL = "loading:Shortfall";
  public static final String HAND_BACK = "loading:HandBack";
  public static final String REQUEST_INTERCHANGE = "loading:RequestInterchange";
  public static final String RELEASE = "loading:Release";
  public static final String HANDOVER = "loading:Handover";

  /** Take the trip: it locks to the signed-in loader until release or hand back (R-LOD-11). */
  public record StartLoading(UUID tripId) {}

  /**
   * Tick an item line as loaded, or undo a tick with {@code PENDING}. Either is a
   * new attempt; nothing is overwritten.
   *
   * @param lineNo the item line; absent ticks every unchecked line of the order
   */
  public record RecordCheck(
      UUID tripId,
      UUID orderId,
      Optional<Integer> lineNo,
      CheckStatus status,
      int loadedUnits,
      Optional<String> reason) {}

  /**
   * Missing, damaged or doesn't fit, before departure (R-LOD-02). The
   * item is not loaded, the dispatcher and store are told, and loading carries
   * on (R-LOD-07).
   *
   * @param lineNo the item line; absent flags the whole order
   */
  public record FlagShortfall(
      UUID tripId,
      UUID orderId,
      Optional<Integer> lineNo,
      CheckStatus kind,
      int missingUnits,
      String reason,
      Optional<UUID> photoAttachmentId) {}

  /** The holder lets the trip go. Their checks keep their name and time (R-LOD-11). */
  public record HandBack(UUID tripId) {}

  /**
   * Asks Planning for a substitute vehicle. Loading never changes the trip's
   * vehicle itself; Planning revalidates and publishes a revision (decision B11).
   */
  public record RequestInterchange(UUID tripId, String replacementVehicleId, String reason) {}

  /**
   * Refused while an item is still unchecked on the current plan version
   * (R-LOD-07), or the three-item checklist fails (R-LOD-10).
   */
  public record ReleaseTrip(
      UUID tripId,
      boolean doorsSealed,
      boolean ordersSecured,
      boolean driverPresent) {}

  /** A dispatcher takes a trip over from a loader who cannot hand it back. Not built yet. */
  public record HandoverSession(UUID tripId) {}
}
