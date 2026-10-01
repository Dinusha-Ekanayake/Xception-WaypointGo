package com.waypoint.dispatch.warehouse.web;

import com.waypoint.dispatch.platform.web.RequestAuthorizer;
import com.waypoint.dispatch.shared.domain.Page;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.warehouse.contract.CatalogueQuery;
import com.waypoint.dispatch.warehouse.contract.CatalogueViews.CatalogueStatusView;
import com.waypoint.dispatch.warehouse.contract.CatalogueViews.ProductView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The cached product catalogue, for the store's picker (#18) and the admin
 * console (#22). Reads only; the catalogue is never edited here.
 *
 * <p>The status endpoint is how a screen degrades visibly (rule 9): it carries
 * the catalogue's age, whether it is stale, and the warehouse circuit state.
 */
@RestController
@RequestMapping("/api/warehouse")
public class WarehouseController {
  private static final String READ = "warehouse:ReadCatalogue";

  private final CatalogueQuery catalogue;
  private final RequestAuthorizer authorizer;

  public WarehouseController(CatalogueQuery catalogue, RequestAuthorizer authorizer) {
    this.catalogue = catalogue;
    this.authorizer = authorizer;
  }

  @GetMapping("/catalogue")
  public Page<ProductView> catalogue(
      @RequestParam(required = false) String brand,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String after,
      @RequestParam(required = false) Integer limit,
      HttpServletRequest request) {
    authorizer.require(request, READ, "wpt:warehouse:catalogue:*");
    return catalogue.catalogue(
        Optional.ofNullable(brand), Optional.ofNullable(q), Optional.ofNullable(after), Page.limit(limit));
  }

  @GetMapping("/catalogue/status")
  public CatalogueStatusView status(HttpServletRequest request) {
    authorizer.require(request, READ, "wpt:warehouse:catalogue:*");
    return catalogue.status();
  }

  @GetMapping("/catalogue/{productId}")
  public ProductView product(@PathVariable String productId, HttpServletRequest request) {
    authorizer.require(request, READ, "wpt:warehouse:product:" + productId);
    return catalogue
        .product(productId)
        .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No product " + productId));
  }
}
