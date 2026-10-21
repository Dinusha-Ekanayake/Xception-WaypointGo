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

  /** At most this many ids in one {@link #names} read. */
  int MAX_NAMES = 200;

  /** The warehouse's names of these products; one it has not named is absent. */
  java.util.Map<String, String> names(java.util.Collection<String> productIds);

  CatalogueStatusView status();
}
