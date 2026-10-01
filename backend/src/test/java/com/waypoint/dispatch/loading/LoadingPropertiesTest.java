package com.waypoint.dispatch.loading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.platform.config.LoadingProperties;
import jakarta.validation.Validation;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LoadingPropertiesTest {
  @Test
  void aDepotOverrideDoesNotChangeOtherDepots() {
    var properties = new LoadingProperties(4, Map.of("D01", 2), false);
    assertEquals(2, properties.docksFor("D01"));
    assertEquals(4, properties.docksFor("D02"));
  }

  @Test
  void omittedOverridesUseTheDefault() {
    assertEquals(4, new LoadingProperties(4, null, false).docksFor("D01"));
  }

  @Test
  void invalidDefaultAndOverridesFailValidation() {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var validator = factory.getValidator();
      assertTrue(validator.validate(new LoadingProperties(4, Map.of("D01", 2), false)).isEmpty());
      assertFalse(validator.validate(new LoadingProperties(0, Map.of(), false)).isEmpty());
      assertFalse(validator.validate(new LoadingProperties(4, Map.of("D01", 0), false)).isEmpty());
      assertFalse(validator.validate(new LoadingProperties(4, Map.of("D01", 21), false)).isEmpty());
    }
  }
}
