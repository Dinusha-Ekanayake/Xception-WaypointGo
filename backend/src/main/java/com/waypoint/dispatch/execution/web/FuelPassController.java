package com.waypoint.dispatch.execution.web;

import com.waypoint.dispatch.execution.application.FuelPasses;
import com.waypoint.dispatch.execution.application.FuelPasses.Receipt;
import com.waypoint.dispatch.execution.contract.ExecutionCommands;
import com.waypoint.dispatch.execution.contract.ExecutionViews.FuelPassStatusView;
import com.waypoint.dispatch.execution.contract.ExecutionViews.FuelPassView;
import com.waypoint.dispatch.platform.web.RequestAuthorizer;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The vehicle's National Fuel Pass (FF-06b): an administrator uploads the
 * official QR image, the driver of the vehicle reads it. Withdrawing a pass is
 * the command {@code vehicle:RevokeFuelPass} on the command endpoint.
 */
@RestController
@RequestMapping("/api/execution")
public class FuelPassController {
  private final FuelPasses passes;
  private final RequestAuthorizer authorizer;

  public FuelPassController(FuelPasses passes, RequestAuthorizer authorizer) {
    this.passes = passes;
    this.authorizer = authorizer;
  }

  /**
   * Stores the vehicle's pass, replacing the current one. The pass id is minted
   * by the screen, so the same upload sent twice is one pass. {@code expectedVersion}
   * is the vehicle's pass count the screen showed.
   */
  @PutMapping(
      path = "/vehicles/{vehicleId}/fuel-pass/{passId}",
      consumes = {MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE, "image/webp", MediaType.APPLICATION_OCTET_STREAM_VALUE})
  public Receipt upload(
      @PathVariable String vehicleId,
      @PathVariable UUID passId,
      @RequestParam long expectedVersion,
      @RequestBody byte[] content,
      HttpServletRequest request) {
    var actor = authorizer.require(request, ExecutionCommands.SET_FUEL_PASS, "wpt:execution:vehicle:" + vehicleId);
    return passes.upload(actor, vehicleId, passId, expectedVersion, content);
  }

  /** The pass for the driver's phone. Never cached by the browser or a proxy: the phone keeps it in its own store. */
  @GetMapping("/vehicles/{vehicleId}/fuel-pass")
  public ResponseEntity<FuelPassView> read(@PathVariable String vehicleId, HttpServletRequest request) {
    var actor = authorizer.require(request, ExecutionCommands.READ_FUEL_PASS, "wpt:execution:vehicle:" + vehicleId);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(passes.forDriver(actor, vehicleId));
  }

  /** Which vehicles have a pass, for the administrator's vehicle screen; never the image. */
  @GetMapping("/fuel-passes")
  public List<FuelPassStatusView> statuses(HttpServletRequest request) {
    var actor = authorizer.require(request, ExecutionCommands.SET_FUEL_PASS, "wpt:execution:vehicle:*");
    return passes.statuses(actor);
  }
}
