package com.waypoint.dispatch.warehouse.application;

import com.waypoint.dispatch.platform.config.WarehouseProperties;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.domain.Cursor;
import com.waypoint.dispatch.shared.domain.Page;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.warehouse.contract.CatalogueQuery;
import com.waypoint.dispatch.warehouse.contract.CatalogueViews.CatalogueStatusView;
import com.waypoint.dispatch.warehouse.contract.CatalogueViews.ProductView;
import com.waypoint.dispatch.warehouse.infrastructure.JdbcCatalogueRepository;
import com.waypoint.dispatch.warehouse.infrastructure.JdbcCatalogueRepository.LastSync;
import com.waypoint.dispatch.warehouse.infrastructure.WarehouseHttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The cached catalogue, for the store's picker and for Ordering's temperature
 * check. Reads the last good copy; when it is old the status says so rather than
 * presenting it as current (CAT-01). Every product is reconstructed, so the view
 * carries {@code verifiedRealSku} for the UI to label it inferred (CAT-06).
 *
 * <p>Answered in a read-only transaction of its own as the warehouse role, so
 * Ordering can call it from inside its command.
 */
@Component
public class WarehouseCatalogueQuery implements CatalogueQuery {
  private final Database database;
  private final JdbcCatalogueRepository catalogue;
  private final WarehouseHttpClient client;
  private final WarehouseProperties properties;
  private final CatalogueSyncJob sync;
  private final Clock clock;

  public WarehouseCatalogueQuery(
      Database database,
      JdbcCatalogueRepository catalogue,
      WarehouseHttpClient client,
      WarehouseProperties properties,
      CatalogueSyncJob sync,
      Clock clock) {
    this.database = database;
    this.catalogue = catalogue;
    this.client = client;
    this.properties = properties;
    this.sync = sync;
    this.clock = clock;
  }

  @Override
  public Page<ProductView> catalogue(
      Optional<String> brandCode, Optional<String> search, Optional<String> cursor, int limit) {
    int size = Page.limit(limit);
    List<String> after = Cursor.decode(cursor.orElse(null), 1);
    return read(() -> {
      Optional<LastSync> last = catalogue.lastGood();
      if (last.isEmpty()) {
        return Page.<ProductView>last(List.of());
      }
      List<ProductView> rows =
          catalogue.page(
              last.get().catalogueVersion(), brandCode.filter(b -> !b.isBlank()),
              search.filter(q -> !q.isBlank()), after.isEmpty() ? Optional.empty() : Optional.of(after.get(0)),
              size + 1);
      return Page.fromOverfetch(rows, size, p -> Cursor.encode(p.productId()));
    });
  }

  @Override
  public Optional<ProductView> product(String productId) {
    return read(() -> catalogue.product(productId));
  }

  @Override
  public CatalogueStatusView status() {
    Optional<LastSync> last = read(catalogue::lastGood);
    Instant now = clock.now();
    last.ifPresent(l -> sync.remember(l.finishedAt()));
    long age = last.map(l -> Duration.between(l.finishedAt(), now).toSeconds()).orElse(-1L);
    boolean stale = last.isEmpty() || age > properties.catalogueStaleAfter().toSeconds();
    return new CatalogueStatusView(
        last.map(LastSync::finishedAt),
        Math.max(age, 0),
        stale,
        last.map(LastSync::productCount).orElse(0),
        client.circuitState().code());
  }

  private <T> T read(java.util.function.Supplier<T> work) {
    return database.readAs(ModuleRole.WAREHOUSE, database.ambientActor().orElse(null), work);
  }
}
