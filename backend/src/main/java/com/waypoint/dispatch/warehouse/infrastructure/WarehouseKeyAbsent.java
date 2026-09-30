package com.waypoint.dispatch.warehouse.infrastructure;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * True when {@code app.warehouse.api-key} is missing or blank.
 *
 * <p>Spring's {@code @ConditionalOnProperty} treats an empty value as present,
 * and the key defaults to empty in {@code application.properties}, so blank
 * has to be tested explicitly.
 */
public class WarehouseKeyAbsent implements Condition {

  @Override
  public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
    String key = context.getEnvironment().getProperty("app.warehouse.api-key");
    return key == null || key.isBlank();
  }
}
