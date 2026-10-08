package com.waypoint.dispatch.identity.web;

import com.waypoint.dispatch.identity.application.ProfilePhotos;
import com.waypoint.dispatch.identity.application.ProfilePhotos.Receipt;
import com.waypoint.dispatch.identity.contract.ProfilePhotoQuery;
import com.waypoint.dispatch.identity.contract.ProfilePhotoQuery.PhotoStatusView;
import com.waypoint.dispatch.identity.contract.ProfilePhotoQuery.PhotoView;
import com.waypoint.dispatch.platform.web.RequestAuthorizer;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
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
 * Profile photos (FF-07): the administrator uploads one with the person's
 * consent, the people who work with that person see it. Removing a photo is the
 * command {@code iam:RemoveProfilePhoto} on the command endpoint.
 */
@RestController
@RequestMapping("/api/accounts")
public class ProfilePhotoController {
  private final ProfilePhotos photos;
  private final RequestAuthorizer authorizer;

  public ProfilePhotoController(ProfilePhotos photos, RequestAuthorizer authorizer) {
    this.photos = photos;
    this.authorizer = authorizer;
  }

  /**
   * Stores the person's photo, replacing the current one. The photo id is minted
   * by the screen, so the same upload sent twice is one photo. {@code consent}
   * says how the person agreed, and is kept with the photo.
   */
  @PutMapping(
      path = "/{userId}/photo/{photoId}",
      consumes = {MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE, "image/webp", MediaType.APPLICATION_OCTET_STREAM_VALUE})
  public Receipt upload(
      @PathVariable UUID userId,
      @PathVariable UUID photoId,
      @RequestParam long expectedVersion,
      @RequestParam String consent,
      @RequestBody byte[] content,
      HttpServletRequest request) {
    var actor = authorizer.require(request, ProfilePhotoQuery.SET, "wpt:iam:user:" + userId);
    return photos.upload(actor, userId, photoId, expectedVersion, consent, content);
  }

  /**
   * The photo, for someone who works with the person today. Private and short:
   * the browser may keep it for a few minutes, a shared proxy never, and a
   * removed photo stops showing within that time.
   */
  @GetMapping("/{userId}/photo")
  public ResponseEntity<byte[]> read(@PathVariable UUID userId, HttpServletRequest request) {
    var actor = authorizer.require(request, ProfilePhotoQuery.READ, "wpt:iam:user:" + userId);
    return image(photos.forViewer(actor, userId));
  }

  /** Who has a photo, for the administrator's people screen; never the image. */
  @GetMapping("/photos")
  public List<PhotoStatusView> statuses(HttpServletRequest request) {
    var actor = authorizer.require(request, ProfilePhotoQuery.SET, "wpt:iam:user:*");
    return photos.statuses(actor);
  }

  public static ResponseEntity<byte[]> image(PhotoView photo) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate())
        .eTag("\"" + photo.sha256() + "\"")
        .contentType(MediaType.parseMediaType(photo.contentType()))
        .header("X-Content-Type-Options", "nosniff")
        .body(photo.content());
  }
}
