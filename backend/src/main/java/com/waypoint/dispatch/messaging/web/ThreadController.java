package com.waypoint.dispatch.messaging.web;

import com.waypoint.dispatch.messaging.application.MessagingQuery;
import com.waypoint.dispatch.messaging.application.VoiceNotes;
import com.waypoint.dispatch.messaging.contract.MessagingCommands;
import com.waypoint.dispatch.messaging.contract.MessagingViews.MemberView;
import com.waypoint.dispatch.messaging.contract.MessagingViews.MessagePage;
import com.waypoint.dispatch.messaging.contract.MessagingViews.ReportMarkView;
import com.waypoint.dispatch.messaging.contract.MessagingViews.ThreadView;
import com.waypoint.dispatch.platform.web.RequestAuthorizer;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reading threads, and the audio of voice notes (issue #136). Writing a message
 * is {@code message:Post} through {@code POST /api/commands}; a voice note is the
 * one write here, because it is binary: idempotent by the phone's id.
 *
 * <p>Policy decides {@code message:Read} and {@code message:Post}; row-level
 * security decides which threads and which messages (effective access is policy
 * AND scope).
 */
@RestController
@RequestMapping("/api/threads")
public class ThreadController {
  private static final String READ = MessagingCommands.READ;

  private final MessagingQuery query;
  private final VoiceNotes voice;
  private final RequestAuthorizer authorizer;

  public ThreadController(MessagingQuery query, VoiceNotes voice, RequestAuthorizer authorizer) {
    this.query = query;
    this.voice = voice;
    this.authorizer = authorizer;
  }

  @GetMapping("/by-subject")
  public ThreadView bySubject(@RequestParam String type, @RequestParam String id, HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:message:" + type + ":" + id);
    return query.bySubject(actor, type, id);
  }

  /** Every report on a depot's trips that day: the warning signs on the dispatcher's timeline. */
  @GetMapping("/reports")
  public List<ReportMarkView> reports(
      @RequestParam String depot,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:message:depot:" + depot);
    return query.reports(actor, depot, date);
  }

  @GetMapping("/{threadId}")
  public ThreadView thread(@PathVariable UUID threadId, HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:message:thread:" + threadId);
    return query.thread(actor, threadId);
  }

  @GetMapping("/{threadId}/messages")
  public MessagePage messages(
      @PathVariable UUID threadId,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit,
      HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:message:thread:" + threadId);
    return query.messages(actor, threadId, Optional.ofNullable(cursor), limit);
  }

  /** Who the reader may write to here (R-MSG-02). */
  @GetMapping("/{threadId}/members")
  public List<MemberView> members(@PathVariable UUID threadId, HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:message:thread:" + threadId);
    return query.members(actor, threadId);
  }

  /**
   * Stores a voice note before the message that carries it. PUT, because it is
   * this id, this audio: a retry stores nothing more.
   */
  @PutMapping(
      path = "/{threadId}/voice/{voiceNoteId}",
      consumes = {"audio/webm", "audio/ogg", "audio/mp4", "audio/aac", "audio/mpeg", MediaType.APPLICATION_OCTET_STREAM_VALUE})
  public VoiceNotes.Stored upload(
      @PathVariable UUID threadId,
      @PathVariable UUID voiceNoteId,
      @RequestParam(required = false) Integer durationMs,
      @RequestParam(required = false) String peaks,
      @RequestHeader("Content-Type") String contentType,
      @RequestBody byte[] content,
      HttpServletRequest request) {
    var actor = authorizer.require(request, MessagingCommands.POST, "wpt:message:thread:" + threadId);
    return voice.store(
        actor, threadId, voiceNoteId, contentType, content, Optional.ofNullable(durationMs),
        com.waypoint.dispatch.messaging.domain.MessagePolicy.peaks(peaks));
  }

  @GetMapping("/{threadId}/voice/{voiceNoteId}")
  /**
   * The audio, with byte ranges: Safari plays and seeks media only through
   * {@code Range} requests answered {@code 206}, so without them a note plays
   * once and then cannot be replayed (MSG-15). Returning a {@link Resource} lets
   * Spring answer a range with the part asked for and {@code Accept-Ranges}.
   */
  public ResponseEntity<Resource> audio(
      @PathVariable UUID threadId, @PathVariable UUID voiceNoteId, HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:message:thread:" + threadId);
    VoiceNotes.Audio audio = voice.audio(actor, threadId, voiceNoteId);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(audio.contentType()))
        .cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options", "nosniff")
        .header("Accept-Ranges", "bytes")
        .body(new ByteArrayResource(audio.content()));
  }
}
