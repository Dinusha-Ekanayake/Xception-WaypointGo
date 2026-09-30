package com.waypoint.dispatch.loading.contract;

import com.waypoint.dispatch.loading.contract.LoadingViews.CheckStatus;
import java.util.Optional;
import java.util.UUID;

/**
 * Payloads of Loading's commands. Every one records device and user, because a
 * dock tablet is shared and the custody chain must name who touched the goods
 * (R-RCP-08).
 */
public final class LoadingCommands {
  private LoadingCommands() {}

  public static final String START = "loading:Start";
  public static final String CHECK = "loading:Check";
  public static final String SHORTFALL = "loading:Shortfall";
  public static final String REQUEST_INTERCHANGE = "loading:RequestInterchange";
  public static final String RELEASE = "loading:Release";
  public static final String HANDOVER = "loading:Handover";

  public record StartLoading(UUID tripId) {}

  public record RecordCheck(
      UUID tripId, UUID orderId, CheckStatus status, int loadedUnits, Optional<String> reason) {}

  /** Short, missing or damaged, before departure (R-LOD-02). */
  public record FlagShortfall(
      UUID tripId,
      UUID orderId,
      CheckStatus kind,
      int missingUnits,
      String reason,
      Optional<UUID> photoAttachmentId) {}

  /**
   * Asks Planning for a substitute vehicle. Loading never changes the trip's
   * vehicle itself; Planning revalidates and publishes a revision (decision B11).
   */
  public record RequestInterchange(UUID tripId, String replacementVehicleId, String reason) {}

  /** Refused until every check on the current plan version passes (R-LOD-07). */
  public record ReleaseTrip(UUID tripId) {}

  /** The signed-in loader takes over the session on a shared device. */
  public record HandoverSession(UUID tripId) {}
}
