package com.waypoint.dispatch.execution.web;

import com.waypoint.dispatch.execution.application.DriverPhotos;
import com.waypoint.dispatch.execution.application.ExecutionDataQuery;
import com.waypoint.dispatch.identity.contract.ProfilePhotoQuery.PhotoView;
import com.waypoint.dispatch.platform.web.RequestAuthorizer;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The photo of the driver bringing a delivery, for the store, on the day only (FF-07). */
@RestController
@RequestMapping("/api/execution")
public class DriverPhotoController {
  private final DriverPhotos photos;
  private final RequestAuthorizer authorizer;

  public DriverPhotoController(DriverPhotos photos, RequestAuthorizer authorizer) {
    this.photos = photos;
    this.authorizer = authorizer;
  }

  /** Private and short-lived: a removed photo stops showing within five minutes. */
  @GetMapping("/deliveries/{deliveryId}/driver-photo")
  public ResponseEntity<byte[]> driverPhoto(@PathVariable UUID deliveryId, HttpServletRequest request) {
    var actor = authorizer.require(request, ExecutionDataQuery.READ, "wpt:execution:delivery:" + deliveryId);
    PhotoView photo = photos.forDelivery(actor, deliveryId);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate())
        .eTag("\"" + photo.sha256() + "\"")
        .contentType(MediaType.parseMediaType(photo.contentType()))
        .header("X-Content-Type-Options", "nosniff")
        .body(photo.content());
  }
}
