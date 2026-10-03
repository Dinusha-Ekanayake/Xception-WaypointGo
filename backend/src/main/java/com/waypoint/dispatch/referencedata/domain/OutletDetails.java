package com.waypoint.dispatch.referencedata.domain;

import com.waypoint.dispatch.shared.domain.PhoneNumber;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * What a store says about itself (R-REF-01): its own delivery window and dock
 * type, laid over the published reference version, and its contacts.
 *
 * <p>An empty window or dock means the published one stands. The contacts are
 * the store's alone and never reach planning.
 *
 * @param window the store's window, when it has set one
 * @param dockType the store's dock, when it has set one; never a mall bay
 */
public record OutletDetails(
    String outletId,
    Optional<DeliveryWindow> window,
    Optional<DockType> dockType,
    String contactName,
    String contactPhone,
    String receivingNotes) {

  public static final String RULE = "R-REF-01";
  public static final int NAME_MAX = 80;
  public static final int NOTES_MAX = 300;

  /** The outlet as planning, loading and the run sheet see it: the store's window and dock where it set them. */
  public Outlet applyTo(Outlet published) {
    return new Outlet(
        published.id(),
        published.brandCode(),
        published.districtName(),
        dockType.orElse(published.dockType()),
        published.parkingConstraint(),
        window.orElse(published.window()),
        published.mallWindow(), published.location());
  }

  /**
   * A change checked against the published outlet. Both ends of a window or
   * neither. A mall bay is the building's, not the store's: a mall outlet keeps
   * it and no other outlet takes one. A mall outlet's window must still overlap
   * the mall's, or the store could not be served at all (R-PLN-29).
   */
  public static OutletDetails of(
      Outlet published,
      LocalTime open,
      LocalTime close,
      String dock,
      String contactName,
      String contactPhone,
      String receivingNotes) {
    if ((open == null) != (close == null)) {
      throw invalid("Give both ends of the delivery window, or neither");
    }
    Optional<DeliveryWindow> window = open == null ? Optional.empty() : Optional.of(new DeliveryWindow(open, close));
    window.ifPresent(
        w ->
            published
                .mallWindow()
                .ifPresent(
                    mall -> {
                      if (mall.intersect(w).isEmpty()) {
                        throw new DomainException(
                            ErrorCode.VALIDATION_FAILED,
                            "The mall lets vehicles in only " + mall.open() + "-" + mall.close() + "; the window must overlap it",
                            List.of(RULE, "R-PLN-29"));
                      }
                    }));

    Optional<DockType> dockType = Optional.empty();
    if (dock != null && !dock.isBlank()) {
      DockType wanted;
      try {
        wanted = DockType.parse(dock);
      } catch (IllegalArgumentException e) {
        throw invalid("Unknown dock type " + dock);
      }
      if (wanted == DockType.MALL_BAY || published.dockType() == DockType.MALL_BAY) {
        if (wanted != published.dockType()) {
          throw invalid("A mall bay belongs to the building; a store can neither choose one nor leave one");
        }
      } else if (wanted != published.dockType()) {
        dockType = Optional.of(wanted);
      }
    }

    return new OutletDetails(
        published.id(),
        window.filter(w -> !w.equals(published.window())),
        dockType,
        text(contactName, NAME_MAX, "A contact name"),
        PhoneNumber.normalise(contactPhone, RULE),
        text(receivingNotes, NOTES_MAX, "Receiving notes"));
  }

  private static String text(String raw, int max, String what) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String value = raw.trim();
    if (value.length() > max) {
      throw invalid(what + " is at most " + max + " characters");
    }
    return value;
  }

  private static DomainException invalid(String message) {
    return new DomainException(ErrorCode.VALIDATION_FAILED, message, List.of(RULE));
  }
}
