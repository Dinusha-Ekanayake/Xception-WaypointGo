package com.waypoint.dispatch.warehouse.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * One product of the warehouse catalogue, as Waypoint caches it. No stock: stock
 * is the warehouse's (R-STK-04).
 *
 * @param temperature {@code chilled} or {@code ambient}; null if the warehouse sent neither
 * @param verifiedRealSku false on every reconstructed row; shown as "inferred" (CAT-06)
 */
public record CatalogueEntry(
    String productId,
    String brand,
    String temperature,
    BigDecimal unitWeightKg,
    BigDecimal unitVolumeM3,
    String baseProductId,
    String basis,
    boolean verifiedRealSku) {

  /**
   * The catalogue's version: a hash of its content, so a sync that changed
   * nothing is recognisably the same version and publishes nothing.
   */
  public static String version(List<CatalogueEntry> entries) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      entries.stream()
          .sorted(Comparator.comparing(CatalogueEntry::productId))
          .forEach(e -> digest.update(
              String.join("\u001f",
                      e.productId(), e.brand(), String.valueOf(e.temperature()),
                      e.unitWeightKg().stripTrailingZeros().toPlainString(),
                      e.unitVolumeM3().stripTrailingZeros().toPlainString(),
                      String.valueOf(e.baseProductId()), e.basis(),
                      String.valueOf(e.verifiedRealSku()))
                  .concat("\n")
                  .getBytes(StandardCharsets.UTF_8)));
      return "cat-" + HexFormat.of().formatHex(digest.digest()).substring(0, 16);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
