package com.waypoint.dispatch.warehouse.infrastructure;

import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** The opposite of {@link WarehouseKeyAbsent}, so exactly one {@code StockPort} exists. */
public class WarehouseKeyPresent extends WarehouseKeyAbsent {

  @Override
  public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
    return !super.matches(context, metadata);
  }
}
