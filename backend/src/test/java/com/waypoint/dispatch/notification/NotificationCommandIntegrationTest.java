package com.waypoint.dispatch.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.waypoint.dispatch.notification.contract.NotificationCommands;
import com.waypoint.dispatch.planning.contract.PlanEvents.OrderDeferred;
import com.waypoint.dispatch.platform.db.ModuleRole;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * A person's own inbox through the API: reading it, marking it read, devices
 * for push, the live count, and the refusals around someone else's.
 */
class NotificationCommandIntegrationTest extends NotificationSupport {

  /** One notification for the store manager; returns its id. */
  private UUID notifyManager(String reason) {
    UUID eventId =
        deliver(
            "notification.on-order-deferred",
            new OrderDeferred(UUID.randomUUID(), UUID.randomUUID(), outlet.outletId(), LocalDate.of(2026, 10, 3),
                "R-PLN-07", reason, 1));
    return notificationIdFor(eventId, manager);
  }

  private Instant readAt(UUID notificationId) {
    Object at =
        database.asSystem(
            ModuleRole.NOTIFICATION,
            () ->
                database
                    .queryOne("SELECT read_at FROM notification.notifications WHERE notification_id = ?", notificationId)
                    .get("read_at"));
    return at == null ? null : ((java.sql.Timestamp) at).toInstant();
  }

  private static String markRead(UUID... ids) {
    StringBuilder list = new StringBuilder();
    for (UUID id : ids) {
      list.append(list.isEmpty() ? "" : ",").append('"').append(id).append('"');
    }
    return envelope(NotificationCommands.MARK_READ, null, "{\"notificationIds\":[" + list + "]}");
  }

  // ---- reading ---------------------------------------------------------------------------

  @Test
  void theInboxIsNewestFirstOnAKeysetCursor() throws Exception {
    UUID first = notifyManager("one");
    UUID second = notifyManager("two");
    UUID third = notifyManager("three");

    JsonNode page1 = read(manager, "/api/notifications?limit=2", 200);
    assertEquals(third.toString(), page1.at("/items/0/notificationId").asText());
    assertEquals(second.toString(), page1.at("/items/1/notificationId").asText());
    String cursor = page1.get("nextCursor").asText();
    JsonNode page2 = read(manager, "/api/notifications?limit=2&after=" + cursor, 200);
    assertEquals(first.toString(), page2.at("/items/0/notificationId").asText());
    assertEquals("order.deferred", page2.at("/items/0/eventType").asText());
    assertEquals("order", page2.at("/items/0/subjectType").asText());
  }

  @Test
  void theUnreadCountIsThePersonsOwn() throws Exception {
    notifyManager("one");
    notifyManager("two");

    long count = read(manager, "/api/notifications/unread-count", 200).get("count").asLong();

    assertTrue(count >= 2);
    assertEquals(0, read(stranger, "/api/notifications/unread-count", 200).get("count").asLong());
  }

  @Test
  void anotherPersonsInboxIsRefusedAndRecorded() throws Exception {
    notifyManager("private");
    long before = denials(dispatcher.id(), "notification:Read");

    read(dispatcher, "/api/notifications?user=" + manager.id(), 403);
    read(dispatcher, "/api/notifications/unread-count?user=" + manager.id(), 403);

    assertEquals(before + 2, denials(dispatcher.id(), "notification:Read"));
    assertEquals(0, read(dispatcher, "/api/notifications?user=" + dispatcher.id(), 200).get("items").size());
  }

  @Test
  void rowLevelSecurityHidesOtherPeoplesNotificationsEvenFromADirectQuery() {
    UUID mine = notifyManager("rls");

    long seen =
        database.asModule(
            ModuleRole.NOTIFICATION,
            dispatcher.id(),
            () ->
                ((Number)
                        database
                            .queryOne(
                                "SELECT count(*) AS n FROM notification.notifications WHERE notification_id = ?", mine)
                            .get("n"))
                    .longValue());

    assertEquals(0, seen);
  }

  @Test
  void theModuleCannotDeleteNorRewriteItsRouting() {
    Map<String, Object> grants =
        database.asSystem(
            ModuleRole.NOTIFICATION,
            () ->
                database.queryOne(
                    "SELECT has_table_privilege('waypoint_notification', 'notification.notifications', 'DELETE') AS d,"
                        + " has_table_privilege('waypoint_notification', 'notification.routing_rules', 'INSERT') AS r"));

    assertEquals(false, grants.get("d"), "notifications are kept forever");
    assertEquals(false, grants.get("r"), "routing is changed by a migration, not at runtime");
  }

  // ---- marking read --------------------------------------------------------------------------

  @Test
  void markingReadIsIdempotentAndKeepsTheFirstReadTime() throws Exception {
    UUID id = notifyManager("idempotent");
    String body = markRead(id);

    JsonNode first = send(manager, body, 200);
    Instant firstRead = readAt(id);
    JsonNode replay = send(manager, body, 200);
    JsonNode again = send(manager, markRead(id), 200);

    assertEquals(1, first.at("/result/marked").asInt());
    assertTrue(replay.get("replayed").asBoolean(), "the same command id is answered from its receipt");
    assertEquals(0, again.at("/result/marked").asInt(), "a new command on a read row changes nothing");
    assertEquals(firstRead, readAt(id));
  }

  @Test
  void markingAllReadLeavesWhatArrivedLaterUnread() throws Exception {
    UUID earlier = notifyManager("earlier");
    Instant looked =
        Instant.parse(
            read(manager, "/api/notifications?limit=1", 200).at("/items/0/createdAt").asText());
    Thread.sleep(5);
    UUID later = notifyManager("later");

    send(manager, envelope(NotificationCommands.MARK_ALL_READ, null, "{\"upTo\":\"" + looked + "\"}"), 200);

    assertTrue(readAt(earlier) != null);
    assertEquals(null, readAt(later));
  }

  @Test
  void someoneElsesNotificationIsNotFoundAndStaysUnread() throws Exception {
    UUID managers = notifyManager("not yours");

    send(dispatcher, markRead(managers), 404);

    assertEquals(null, readAt(managers));
  }

  @Test
  void markReadNeedsIds() throws Exception {
    send(manager, envelope(NotificationCommands.MARK_READ, null, "{\"notificationIds\":[]}"), 422);
  }

  // ---- devices ----------------------------------------------------------------------------------

  @Test
  void aBrowserSubscribedBySomeoneElseMovesToTheNewPerson() throws Exception {
    String shared = endpoint();
    UUID managers = subscribe(manager, shared);
    UUID dispatchers = subscribe(dispatcher, shared);

    assertEquals("unsubscribed", subscriptionStatus(managers), "the browser no longer pushes the manager's news");
    assertEquals("active", subscriptionStatus(dispatchers));
  }

  @Test
  void subscribingTwiceKeepsOneSubscriptionAndUnsubscribingEndsIt() throws Exception {
    String mine = endpoint();
    UUID first = subscribe(manager, mine);
    UUID second = subscribe(manager, mine);

    assertEquals(first, second);
    JsonNode off = send(manager, envelope(NotificationCommands.UNSUBSCRIBE, null, "{\"endpoint\":\"" + mine + "\"}"), 200);
    assertTrue(off.at("/result/unsubscribed").asBoolean());
    assertEquals("unsubscribed", subscriptionStatus(first));
    JsonNode again = send(manager, envelope(NotificationCommands.UNSUBSCRIBE, null, "{\"endpoint\":\"" + mine + "\"}"), 200);
    assertFalse(again.at("/result/unsubscribed").asBoolean(), "already gone is not an error");
  }

  @Test
  void subscribingWhenPushIsOffSaysSo() throws Exception {
    push.enabled = false;

    JsonNode problem = send(manager, subscribeBody(endpoint()), 503);

    assertEquals("DEPENDENCY_UNAVAILABLE", problem.get("code").asText());
  }

  @Test
  void aSubscriptionWithAMalformedKeyIsRefused() throws Exception {
    send(
        manager,
        envelope(NotificationCommands.SUBSCRIBE, null,
            "{\"endpoint\":\"" + endpoint() + "\",\"p256dhKey\":\"AAAA\",\"authSecret\":\"AAAA\"}"),
        422);
    send(
        manager,
        envelope(NotificationCommands.SUBSCRIBE, null,
            "{\"endpoint\":\"http://insecure.test/x\",\"p256dhKey\":\"AAAA\",\"authSecret\":\"AAAA\"}"),
        422);
  }

  // ---- live count ----------------------------------------------------------------------------------

  @Test
  void theStreamSendsTheUnreadCountOnConnectAndOnChange() throws Exception {
    notifyManager("before connecting");
    MvcResult stream =
        http.perform(get("/api/notifications/stream").cookie(manager.session()))
            .andExpect(request().asyncStarted())
            .andReturn();

    String first = await(stream, 1);
    assertTrue(first.contains("event:unread"), first);
    notifyManager("while connected");
    String both = await(stream, 2);
    List<Long> counts =
        both.lines()
            .filter(l -> l.startsWith("data:"))
            .map(l -> Long.parseLong(l.replaceAll(".*\"count\":(\\d+).*", "$1")))
            .toList();
    assertEquals(counts.get(0) + 1, counts.get(1));
  }

  /** Waits for the stream to carry {@code events} events; the sends run on their own thread. */
  private static String await(MvcResult stream, int events) throws Exception {
    for (int i = 0; i < 100; i++) {
      String body = stream.getResponse().getContentAsString();
      if (body.split("event:unread", -1).length - 1 >= events) {
        return body;
      }
      Thread.sleep(50);
    }
    return stream.getResponse().getContentAsString();
  }
}
