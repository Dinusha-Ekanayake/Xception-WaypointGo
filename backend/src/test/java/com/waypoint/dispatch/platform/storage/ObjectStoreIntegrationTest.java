package com.waypoint.dispatch.platform.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.waypoint.dispatch.identity.application.AccountAdminUseCase;
import com.waypoint.dispatch.identity.application.LoginHandler;
import com.waypoint.dispatch.identity.web.AuthController;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.Migrator;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.support.FakeS3;
import com.waypoint.dispatch.support.TestDatabase;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * M-01 end to end, against an S3-compatible store in the process: a new file
 * goes to the bucket encrypted and the database keeps none of it; a file stored
 * before the switch is moved, checked and its database copy cleared later; a
 * changed object is refused; a removed photo leaves the bucket. Profile photos
 * stand for every module's media: each reads and writes through the same client.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(TestDatabase.class)
class ObjectStoreIntegrationTest {
  static final String PASSWORD = "Bucket2026Test!";
  static final FakeS3 S3;

  static {
    try {
      S3 = new FakeS3();
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @Autowired MockMvc http;
  @Autowired Migrator migrator;
  @Autowired Database database;
  @Autowired AccountAdminUseCase accounts;
  @Autowired LoginHandler login;
  @Autowired MediaBackfillJob backfill;

  String admin;
  String person;
  UUID personId;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("app.database-url", TestDatabase::url);
    registry.add("app.storage.object-store", () -> "s3");
    registry.add("app.storage.endpoint", S3::endpoint);
    registry.add("app.storage.bucket", () -> "waypoint-test");
    registry.add("app.storage.access-key-id", () -> "test-key");
    registry.add("app.storage.secret-access-key", () -> "test-secret");
    registry.add("app.storage.encryption-key", () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));
  }

  @AfterAll
  static void stop() {
    S3.close();
  }

  @BeforeEach
  void setUp() {
    migrator.migrate();
    String run = UUID.randomUUID().toString().substring(0, 8);
    admin = "osa-" + run + "@bucket.test";
    person = "osp-" + run + "@bucket.test";
    accounts.createAccount(admin, "Admin", PASSWORD, "admin");
    personId = accounts.createAccount(person, "Person", PASSWORD, "dispatcher");
  }

  @Test
  void aNewPhotoGoesToTheBucketEncryptedAndTheDatabaseKeepsNone() throws Exception {
    byte[] image = png("a photo uploaded with the store on");
    UUID photoId = UUID.randomUUID();
    upload(photoId, image);

    byte[] stored = S3.objects.get("/waypoint-test/profile-photos/" + photoId);
    assertNotNull(stored, "the object is in the bucket");
    assertFalse(new String(stored, StandardCharsets.ISO_8859_1).contains("uploaded with the store on"), "as ciphertext");
    Map<String, Object> row = row(photoId);
    assertNull(row.get("content"), "the database keeps no copy");
    assertEquals("profile-photos/" + photoId, row.get("object_key"));
    assertArrayEquals(image, read(200), "and it reads back as it was");

    // One changed bit in the bucket and the photo is refused, never served.
    stored[stored.length - 1] ^= 1;
    read(503);
    stored[stored.length - 1] ^= 1;
    assertArrayEquals(image, read(200));
  }

  @Test
  void aPhotoStoredBeforeTheSwitchIsMovedCheckedAndItsDatabaseCopyClearedLater() throws Exception {
    byte[] image = png("a photo stored before the switch");
    UUID photoId = UUID.randomUUID();
    String sha = MediaBackfillJob.sha256(image);
    database.asSystem(ModuleRole.IAM, () -> database.update(
        "INSERT INTO iam.profile_photos (photo_id, user_id, content, content_type, size_bytes, sha256, consent_note,"
            + " uploaded_by, uploaded_at) VALUES (?, ?, ?, 'image/png', ?, ?, 'Signed the form', ?, now())",
        photoId, personId, image, image.length, sha, personId));

    Instant now = Instant.now();
    assertTrue(backfill.runAt(now) >= 1);
    Map<String, Object> moved = row(photoId);
    assertEquals("profile-photos/" + photoId, moved.get("object_key"));
    assertNotNull(moved.get("moved_at"));
    assertNotNull(moved.get("content"), "the database copy stays while it can still be undone");
    backfill.runAt(now);
    assertEquals(1, S3.objects.keySet().stream().filter(k -> k.endsWith(photoId.toString())).count(), "moved once");

    backfill.runAt(now.plus(Duration.ofDays(31)));
    assertNull(row(photoId).get("content"), "cleared after S3_MOVE_AFTER");
    assertArrayEquals(image, read(200), "and read from the bucket");

    // Removing the photo, as the person asked, deletes it from the bucket too.
    long version = ((Number) row(photoId).get("row_version")).longValue();
    command("iam:RemoveProfilePhoto", version, "{\"userId\":\"" + personId + "\",\"reason\":\"They asked for it\"}");
    assertFalse(S3.objects.containsKey("/waypoint-test/profile-photos/" + photoId));
    Map<String, Object> removed = row(photoId);
    assertNull(removed.get("object_key"));
    assertNotNull(removed.get("removed_at"));
  }

  @Test
  void aFileWhoseDatabaseCopyDoesNotMatchItsHashIsNotMoved() {
    byte[] image = png("bytes that do not match their hash");
    UUID photoId = UUID.randomUUID();
    database.asSystem(ModuleRole.IAM, () -> database.update(
        "INSERT INTO iam.profile_photos (photo_id, user_id, content, content_type, size_bytes, sha256, consent_note,"
            + " uploaded_by, uploaded_at) VALUES (?, ?, ?, 'image/png', ?, ?, 'Signed the form', ?, now())",
        photoId, personId, image, image.length, "0".repeat(64), personId));
    backfill.runAt(Instant.now());
    assertNull(row(photoId).get("object_key"), "left in the database, and counted");
    assertNotNull(row(photoId).get("content"));
  }

  private Map<String, Object> row(UUID photoId) {
    return database.asSystem(ModuleRole.IAM, () -> database.queryOne(
        "SELECT content, object_key, moved_at, removed_at, row_version FROM iam.profile_photos WHERE photo_id = ?",
        photoId));
  }

  private void upload(UUID photoId, byte[] image) throws Exception {
    MvcResult r = http.perform(put("/api/accounts/" + personId + "/photo/" + photoId + "?expectedVersion=0&consent=Signed")
            .cookie(session(admin)).contentType(MediaType.IMAGE_PNG).content(image))
        .andReturn();
    assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
  }

  private byte[] read(int status) throws Exception {
    MvcResult r = http.perform(get("/api/accounts/" + personId + "/photo").cookie(session(person))).andReturn();
    assertEquals(status, r.getResponse().getStatus(), r.getResponse().getContentAsString());
    return r.getResponse().getContentAsByteArray();
  }

  private void command(String kind, long version, String payload) throws Exception {
    String body = "{\"commandId\":\"" + UUID.randomUUID() + "\",\"kind\":\"" + kind + "\",\"expectedVersion\":" + version
        + ",\"payload\":" + payload + ",\"clientRecordedAt\":\"" + Instant.now() + "\"}";
    MvcResult r = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/commands")
            .cookie(session(admin)).contentType(MediaType.APPLICATION_JSON).content(body))
        .andReturn();
    assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
  }

  private Cookie session(String email) {
    return new Cookie(AuthController.COOKIE, login.login(email, PASSWORD, null, "127.0.0.1"));
  }

  private static byte[] png(String words) {
    byte[] text = words.getBytes(StandardCharsets.UTF_8);
    byte[] out = new byte[8 + text.length];
    byte[] header = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    System.arraycopy(header, 0, out, 0, 8);
    System.arraycopy(text, 0, out, 8, text.length);
    return out;
  }
}
