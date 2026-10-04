package com.waypoint.dispatch.identity.web;

import com.waypoint.dispatch.identity.application.DeviceRegistry;
import com.waypoint.dispatch.platform.web.RequestAuthorizer;
import com.waypoint.dispatch.shared.domain.Page;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reading the device registry.
 *
 * <p>Reads only. A device is registered and retired by command
 * ({@code iam:RegisterDevice}, {@code iam:RetireDevice}) through
 * {@code POST /api/commands}. Listing devices is reading the shape of the
 * operation, so it needs the same permission as registering one, as the account
 * list needs the permission to change an account.
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {
  private final DeviceRegistry devices;
  private final RequestAuthorizer authorizer;

  public DeviceController(DeviceRegistry devices, RequestAuthorizer authorizer) {
    this.devices = devices;
    this.authorizer = authorizer;
  }

  @GetMapping
  public Page<DeviceRegistry.DeviceView> list(
      @RequestParam(required = false) String after,
      @RequestParam(required = false) Integer limit,
      HttpServletRequest request) {
    authorizer.require(request, "iam:RegisterDevice", "wpt:iam:device:*");
    return devices.page(after, limit);
  }
}
