package com.waypoint.dispatch.referencedata.domain;

import java.util.List;

/** Typed reference tables loaded from data/General Data/*.csv. */
public record ReferenceData(
    List<Outlet> outlets,
    List<Vehicle> vehicles,
    List<CalendarRow> calendar,
    List<DistrictTravel> districtTravel,
    List<ServiceAllowance> serviceAllowance) {

  public record Outlet(
      String outletId,
      String brand,
      String district,
      String depot,
      String dockType,
      String parkingConstraint,
      String mallWindow,
      String windowOpenTime,
      String windowCloseTime) {}

  public record Vehicle(
      String vehicleId,
      String type,
      String temp,
      double weightCapKg,
      double volumeCapM3,
      String fuelType,
      double kmPerL,
      double weeklyFuelQuotaL,
      String depot,
      String status) {
    public Vehicle withStatus(String next) {
      return new Vehicle(
          vehicleId, type, temp, weightCapKg, volumeCapM3, fuelType, kmPerL, weeklyFuelQuotaL, depot,
          next);
    }
  }

  public record CalendarRow(String date, String isOperating) {}

  public record DistrictTravel(
      String district,
      double depotToDistrictFreeflowMin,
      double interStopFreeflowMin,
      double depotToDistrictKm,
      double interStopKm) {}

  public record ServiceAllowance(String brand, String dockType, double serviceAllowanceMin) {}

  public record Reservation(double fuel, int trips, double end) {}
}
