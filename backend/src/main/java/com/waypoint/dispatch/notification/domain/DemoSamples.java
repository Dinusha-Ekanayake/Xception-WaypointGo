package com.waypoint.dispatch.notification.domain;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * The sample notices a demo driver is sent on request, so judges can see the
 * driver's inbox without a plan or a loading under way. Worded as the routing
 * table words the real ones, each under the event type whose label the inbox
 * shows, and with no subject, so none opens a trip or a thread that does not
 * exist. Every body says it is a sample.
 */
public final class DemoSamples {
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

  private DemoSamples() {}

  /** One sample: the event type it is shown as, a key unique within one request, and its words. */
  public record Sample(String eventType, String key, String title, String body) {}

  public static List<Sample> forDay(LocalDate day) {
    String date = DAY.format(day);
    return List.of(
        new Sample("plan.published", "demo:plan", "Your trip for " + date,
            "Trip 1 departing 07:30, 6 stops. Sample notice for the demo."),
        new Sample("trip.released", "demo:released", "Your vehicle is loaded",
            "Released for " + date + " with 6 stops. Sample notice for the demo."),
        new Sample("road.disruption_reported", "demo:road", "Road closed near stop 3",
            "Use the Kelani bridge route; allow 10 more minutes. Sample notice for the demo."),
        new Sample("message.posted", "demo:message", "Dispatch",
            "Call the store manager before unloading at stop 2. Sample notice for the demo."));
  }
}
