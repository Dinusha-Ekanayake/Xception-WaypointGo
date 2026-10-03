package com.waypoint.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.waypoint.dispatch.identity.application.AccountAdminUseCase;
import com.waypoint.dispatch.identity.application.OperatorRegistry;
import com.waypoint.dispatch.intelligence.application.ForecastJob;
import com.waypoint.dispatch.loading.application.LoadingFixture;
import com.waypoint.dispatch.platform.config.AppProperties;
import com.waypoint.dispatch.platform.config.LoadingProperties;
import com.waypoint.dispatch.platform.db.Migrator;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import com.waypoint.dispatch.platform.scheduling.ScheduledJobRunner;
import com.waypoint.dispatch.referencedata.application.ImportReferenceDataHandler;
import com.waypoint.dispatch.referencedata.application.ReferenceBootstrap;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.core.env.Environment;

class WaypointApplicationLoadingFixtureTest {
  @Test
  void fixtureCommandLoadsThePublishedReferenceVersionBeforeBuildingTrips() {
    ReferenceBootstrap references = mock(ReferenceBootstrap.class);
    LoadingFixture fixture = mock(LoadingFixture.class);
    LocalDate day = LocalDate.of(2026, 10, 1);
    when(fixture.build("Peliyagoda", day)).thenReturn(1);
    WaypointApplication application = new WaypointApplication(
        mock(Migrator.class), mock(ImportReferenceDataHandler.class),
        mock(AccountAdminUseCase.class), mock(OperatorRegistry.class),
        mock(AppProperties.class), fixture, mock(LoadingProperties.class),
        mock(Environment.class), references,
        mock(com.waypoint.dispatch.ordering.application.DeliveryDaySeed.class),
        mock(com.waypoint.dispatch.referencedata.application.FleetDaySeed.class),
        mock(com.waypoint.dispatch.referencedata.contract.ReferenceQuery.class),
        mock(ForecastJob.class), Optional.empty());

    assertEquals(1, application.buildLoadingFixture("Peliyagoda", day));
    InOrder order = inOrder(references, fixture);
    order.verify(references).loadCurrentVersion();
    order.verify(fixture).build("Peliyagoda", day);
  }

  @Test
  void forecastRunLoadsTheReferenceVersionThenRunsTheJobOnceUnderItsLease() {
    ReferenceBootstrap references = mock(ReferenceBootstrap.class);
    ForecastJob forecast = mock(ForecastJob.class);
    ScheduledJob now = mock(ScheduledJob.class);
    when(forecast.immediately()).thenReturn(now);
    ScheduledJobRunner runner = mock(ScheduledJobRunner.class);
    when(runner.runOnce(now)).thenReturn(true);
    WaypointApplication application = new WaypointApplication(
        mock(Migrator.class), mock(ImportReferenceDataHandler.class),
        mock(AccountAdminUseCase.class), mock(OperatorRegistry.class),
        mock(AppProperties.class), mock(LoadingFixture.class), mock(LoadingProperties.class),
        mock(Environment.class), references,
        mock(com.waypoint.dispatch.ordering.application.DeliveryDaySeed.class),
        mock(com.waypoint.dispatch.referencedata.application.FleetDaySeed.class),
        mock(com.waypoint.dispatch.referencedata.contract.ReferenceQuery.class),
        forecast, Optional.of(runner));

    assertTrue(application.runForecast());
    InOrder order = inOrder(references, runner);
    order.verify(references).loadCurrentVersion();
    order.verify(runner).runOnce(now);
  }
}
