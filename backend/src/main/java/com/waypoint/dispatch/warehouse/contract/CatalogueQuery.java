package com.waypoint.dispatch.warehouse.contract;

import com.waypoint.dispatch.shared.domain.Page;
import com.waypoint.dispatch.warehouse.contract.CatalogueViews.CatalogueStatusView;
import com.waypoint.dispatch.warehouse.contract.CatalogueViews.ProductView;
import java.util.Optional;

/** Reads of the cached catalogue, for the store's product picker and the admin console. */
public interface CatalogueQuery {

  Page<ProductView> catalogue(
      Optional<String> brandCode, Optional<String> search, Optional<String> cursor, int limit);

  Optional<ProductView> product(String productId);

  CatalogueStatusView status();
}
