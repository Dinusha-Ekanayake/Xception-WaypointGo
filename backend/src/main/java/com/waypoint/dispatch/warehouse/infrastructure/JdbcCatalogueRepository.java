package com.waypoint.dispatch.warehouse.infrastructure;

import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.warehouse.contract.CatalogueViews.ProductView;
import com.waypoint.dispatch.warehouse.domain.CatalogueEntry;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code warehouse.products} and {@code warehouse.catalogue_syncs}.
 *
 * <p>The current catalogue is the rows carrying the last good sync's version. A
 * product the warehouse stops listing keeps its old version and so drops out of
 * the picker without being deleted (the role has no DELETE).
 */
@Component
public class JdbcCatalogueRepository {
  private final Database database;

  public JdbcCatalogueRepository(Database database) {
    this.database = database;
  }

  public record LastSync(String catalogueVersion, Instant finishedAt, int productCount) {}

  public Optional<LastSync> lastGood() {
    Map<String, Object> row =
        database.queryOne(
            "SELECT catalogue_version, finished_at, product_count FROM warehouse.catalogue_syncs"
                + " WHERE outcome <> 'failed' ORDER BY finished_at DESC LIMIT 1");
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(new LastSync(
        (String) row.get("catalogue_version"),
        ((Timestamp) row.get("finished_at")).toInstant(),
        ((Number) row.get("product_count")).intValue()));
  }

  public void upsert(List<CatalogueEntry> entries, String version, Instant at) {
    for (CatalogueEntry e : entries) {
      database.update(
          "INSERT INTO warehouse.products (product_id, brand, temperature, unit_weight_kg, unit_volume_m3,"
              + " base_product_id, basis, verified_real_sku, catalogue_version, synced_at)"
              + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
              + " ON CONFLICT (product_id) DO UPDATE SET brand = EXCLUDED.brand,"
              + " temperature = EXCLUDED.temperature, unit_weight_kg = EXCLUDED.unit_weight_kg,"
              + " unit_volume_m3 = EXCLUDED.unit_volume_m3, base_product_id = EXCLUDED.base_product_id,"
              + " basis = EXCLUDED.basis, verified_real_sku = EXCLUDED.verified_real_sku,"
              + " catalogue_version = EXCLUDED.catalogue_version, synced_at = EXCLUDED.synced_at",
          e.productId(), e.brand(), e.temperature(), e.unitWeightKg(), e.unitVolumeM3(),
          e.baseProductId(), e.basis(), e.verifiedRealSku(), version, Timestamp.from(at));
    }
  }

  /** Confirms the current rows are still what the warehouse lists. */
  public void touch(String version, Instant at) {
    database.update(
        "UPDATE warehouse.products SET synced_at = ? WHERE catalogue_version = ?",
        Timestamp.from(at), version);
  }

  public void recordSync(
      UUID syncId, Instant started, Instant finished, String outcome, String version,
      Integer productCount, String error) {
    database.update(
        "INSERT INTO warehouse.catalogue_syncs (sync_id, started_at, finished_at, outcome,"
            + " catalogue_version, product_count, error) VALUES (?, ?, ?, ?, ?, ?, ?)",
        syncId, Timestamp.from(started), Timestamp.from(finished), outcome, version, productCount, error);
  }

  /** Keyset page on {@code product_id}; never OFFSET. */
  public List<ProductView> page(
      String version, Optional<String> brand, Optional<String> search, Optional<String> after, int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT product_id, brand, temperature, unit_weight_kg, unit_volume_m3, verified_real_sku, basis"
                + " FROM warehouse.products WHERE catalogue_version = ?");
    List<Object> params = new ArrayList<>(List.of(version));
    brand.ifPresent(b -> {
      sql.append(" AND lower(brand) = lower(?)");
      params.add(b);
    });
    search.ifPresent(q -> {
      sql.append(" AND product_id ILIKE ?");
      params.add("%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
    });
    after.ifPresent(a -> {
      sql.append(" AND product_id > ?");
      params.add(a);
    });
    sql.append(" ORDER BY product_id LIMIT ?");
    params.add(limit);
    return database.query(sql.toString(), params.toArray()).stream().map(JdbcCatalogueRepository::view).toList();
  }

  public Optional<ProductView> product(String productId) {
    Map<String, Object> row =
        database.queryOne(
            "SELECT product_id, brand, temperature, unit_weight_kg, unit_volume_m3, verified_real_sku, basis"
                + " FROM warehouse.products WHERE product_id = ?",
            productId);
    return Optional.ofNullable(row).map(JdbcCatalogueRepository::view);
  }

  private static ProductView view(Map<String, Object> row) {
    return new ProductView(
        (String) row.get("product_id"),
        (String) row.get("brand"),
        (BigDecimal) row.get("unit_weight_kg"),
        (BigDecimal) row.get("unit_volume_m3"),
        Optional.ofNullable((String) row.get("temperature")),
        Boolean.TRUE.equals(row.get("verified_real_sku")),
        (String) row.get("basis"));
  }
}
