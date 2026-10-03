package com.waypoint.dispatch.issues.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.waypoint.dispatch.ordering.domain.Order;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.receipt.contract.ReceiptEvents.ReceiptConfirmed;
import com.waypoint.dispatch.support.ReceiptIssuesSupport;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Photos of a delivery problem (Figma store manager "06d", "08b3"): uploaded by
 * the outlet's manager for its order, linked to an issue whichever arrives first,
 * read by those who can see the issue, and cleared past retention.
 */
class IssueAttachmentsIntegrationTest extends ReceiptIssuesSupport {
  @Autowired IssueAttachmentRetentionJob retention;

  static final byte[] PHOTO = png(Color.RED);
  static final byte[] OTHER_PHOTO = png(Color.BLUE);

  // ---- uploading -----------------------------------------------------------------

  @Test
  void aPhotoIsStoredOnceUnderItsIdAndADifferentOneIsRefused() throws Exception {
    Order order = deliveredOrder(outlet);
    UUID id = UUID.randomUUID();

    JsonNode first = upload(manager, id, order, null, PHOTO, 200);
    assertTrue(first.get("stored").asBoolean());
    assertEquals("image/png", first.get("contentType").asText());
    assertFalse(upload(manager, id, order, null, PHOTO, 200).get("stored").asBoolean(), "a repeat from the queue is a no-op");

    upload(manager, id, order, null, OTHER_PHOTO, 409);
  }

  @Test
  void onlyARealImageOfAcceptableSizeIsTaken() throws Exception {
    Order order = deliveredOrder(outlet);
    upload(manager, UUID.randomUUID(), order, null, "<svg onload=alert(1)>".getBytes(), 422);
    byte[] huge = Arrays.copyOf(PHOTO, 3_200_000);
    upload(manager, UUID.randomUUID(), order, null, huge, 413);
  }

  @Test
  void anotherOutletsManagerCannotAttachAPhotoAndTheRefusalIsAudited() throws Exception {
    Order order = deliveredOrder(outlet);
    long before = denials(stranger.id(), "issue:AttachPhoto");

    upload(stranger, UUID.randomUUID(), order, null, PHOTO, 403);

    assertEquals(before + 1, denials(stranger.id(), "issue:AttachPhoto"));
  }

  // ---- linking and reading -------------------------------------------------------

  @Test
  void anIssueNamesItsPhotosWhicheverArrivesFirst() throws Exception {
    Order order = deliveredOrder(outlet);
    UUID early = UUID.randomUUID();
    UUID late = UUID.randomUUID();
    upload(manager, early, order, null, PHOTO, 200);

    JsonNode raised = send(manager, raise(order, early, late), 200).get("result");
    UUID issueId = UUID.fromString(raised.get("issueId").asText());
    assertEquals(1, read(manager, "/api/issues/" + issueId, 200).get("attachments").size(), "only what has arrived");

    // The second photo was still on the phone; it links itself when it arrives.
    upload(manager, late, order, null, OTHER_PHOTO, 200);
    JsonNode both = read(manager, "/api/issues/" + issueId, 200).get("attachments");
    assertEquals(2, both.size());

    assertArrayEquals(PHOTO, photo(manager, issueId, early, 200));
    assertArrayEquals(PHOTO, photo(dispatcher, issueId, early, 200), "the depot's dispatcher sees it too");
    photo(stranger, issueId, early, 404);
    photo(manager, issueId, UUID.randomUUID(), 404);
  }

  @Test
  void photosTakenWhileCountingBelongToTheReceiptsInvestigationInEitherOrder() throws Exception {
    // Photo first, then the investigation.
    Order first = delivered();
    UUID receiptA = receiptOf(first);
    UUID before = UUID.randomUUID();
    upload(manager, before, first, receiptA, PHOTO, 200);
    UUID investigationA = investigate(first, receiptA);
    assertEquals(before.toString(), read(manager, "/api/issues/" + investigationA, 200)
        .get("attachments").get(0).get("attachmentId").asText());

    // The investigation first, then the photo, as when the phone was offline.
    Order second = delivered();
    UUID receiptB = receiptOf(second);
    UUID investigationB = investigate(second, receiptB);
    UUID after = UUID.randomUUID();
    upload(manager, after, second, receiptB, OTHER_PHOTO, 200);
    assertEquals(after.toString(), read(manager, "/api/issues/" + investigationB, 200)
        .get("attachments").get(0).get("attachmentId").asText());

    // A receipt that is not the order's is refused.
    upload(manager, UUID.randomUUID(), first, receiptB, PHOTO, 422);
  }

  // ---- retention -----------------------------------------------------------------

  @Test
  void aPhotoPastItsRetentionIsClearedButStaysOnRecord() throws Exception {
    Order order = deliveredOrder(outlet);
    UUID id = UUID.randomUUID();
    upload(manager, id, order, null, PHOTO, 200);
    UUID issueId = UUID.fromString(send(manager, raise(order, id), 200).get("result").get("issueId").asText());
    database.asSystem(
        ModuleRole.ISSUES,
        () -> database.update("UPDATE issues.attachments SET retain_until = current_date - 1 WHERE attachment_id = ?", id));

    assertTrue(retention.runAt(Instant.now()) >= 1);

    photo(manager, issueId, id, 404);
    assertEquals(0, read(manager, "/api/issues/" + issueId, 200).get("attachments").size());
    var row =
        database.asSystem(
            ModuleRole.ISSUES,
            () -> database.queryOne("SELECT sha256, purged_at FROM issues.attachments WHERE attachment_id = ?", id));
    assertFalse(String.valueOf(row.get("sha256")).isBlank(), "the record that it existed stays");
    assertTrue(row.get("purged_at") != null);
    assertEquals(0, retention.runAt(Instant.now().plus(Duration.ofMinutes(1))), "running again clears nothing new");
  }

  // ---- fixtures ------------------------------------------------------------------

  private Order delivered() {
    Order order = deliveredOrder(outlet);
    deliver("receipt.on-delivery-completed", completed(order, Instant.now()), driver.id());
    return order;
  }

  private UUID receiptOf(Order order) throws Exception {
    return UUID.fromString(read(manager, "/api/receipts/" + order.orderId(), 200).get("receiptId").asText());
  }

  /** A partial receipt with a note, so it is investigated, and its event delivered. */
  private UUID investigate(Order order, UUID receiptId) throws Exception {
    send(manager,
        envelope("receipt:ConfirmPartial", 1L,
            "{\"orderId\":\"" + order.orderId() + "\",\"lines\":[{\"productId\":\"P-1\",\"receivedQuantity\":8}],"
                + "\"note\":\"Damaged: P-1 x2\"}"),
        200);
    deliver("issues.on-receipt-confirmed", new ReceiptConfirmed(receiptId, order.orderId(), order.outletId(), true, Instant.now()));
    return UUID.fromString(
        read(manager, "/api/issues/by-subject?type=receipt&id=" + receiptId, 200).get(0).get("issueId").asText());
  }

  private String raise(Order order, UUID... photos) {
    StringBuilder ids = new StringBuilder();
    for (UUID p : photos) {
      ids.append(ids.length() == 0 ? "" : ",").append('"').append(p).append('"');
    }
    return envelope(
        "issue:Raise", null,
        "{\"type\":\"DAMAGED_GOODS\",\"severity\":\"MEDIUM\",\"depotCode\":\"" + depot + "\",\"outletId\":\""
            + outlet.outletId() + "\",\"subjects\":[{\"type\":\"order\",\"id\":\"" + order.orderId() + "\"}],"
            + "\"description\":\"2 cases of P-1 damaged\",\"attachmentIds\":[" + ids + "]}");
  }

  private JsonNode upload(Person who, UUID id, Order order, UUID receipt, byte[] bytes, int expected) throws Exception {
    String path =
        "/api/issues/attachments/" + id + "?order=" + order.orderId() + (receipt == null ? "" : "&receipt=" + receipt);
    MvcResult result =
        http.perform(put(path).cookie(who.session()).contentType("image/png").content(bytes)).andReturn();
    String body = result.getResponse().getContentAsString();
    assertEquals(expected, result.getResponse().getStatus(), body);
    return body.isEmpty() ? mapper.nullNode() : mapper.readTree(body);
  }

  private byte[] photo(Person who, UUID issueId, UUID attachmentId, int expected) throws Exception {
    MvcResult result =
        http.perform(get("/api/issues/" + issueId + "/attachments/" + attachmentId + "/content").cookie(who.session()))
            .andReturn();
    assertEquals(expected, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    if (expected == 200) {
      assertEquals("nosniff", result.getResponse().getHeader("X-Content-Type-Options"));
    }
    return result.getResponse().getContentAsByteArray();
  }

  private static byte[] png(Color color) {
    try {
      BufferedImage image = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
      var g = image.createGraphics();
      g.setColor(color);
      g.fillRect(0, 0, 40, 30);
      g.dispose();
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ImageIO.write(image, "png", out);
      return out.toByteArray();
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
