package com.waypoint.dispatch.demo.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.demo.domain.DemoSettings;
import com.waypoint.dispatch.ordering.contract.DemoDayQuery;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.*;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DemoCommandHandlers {
  // With DEMO_RESET on (R-DEMO-06) the demo runs in a database of its own: turning
  // demo mode on in the real database asks the server to switch to the demo one,
  // and turning it off there asks to switch back, so the real data is never written.
  @Bean CommandHandler demoEnable(Database db, DemoAccess access, DemoDayQuery days, ObjectMapper mapper, @Value("${app.demo.reset:false}") boolean separate) { return new SettingsHandler("Enable", db, access, days, mapper, separate); }
  @Bean CommandHandler demoDisable(Database db, DemoAccess access, DemoDayQuery days, ObjectMapper mapper, @Value("${app.demo.reset:false}") boolean separate) { return new SettingsHandler("Disable", db, access, days, mapper, separate); }
  @Bean CommandHandler demoSetClock(Database db, DemoAccess access, DemoDayQuery days, ObjectMapper mapper) { return new SettingsHandler("SetClock", db, access, days, mapper, false); }
  @Bean CommandHandler demoUpdateSettings(Database db, DemoAccess access, DemoDayQuery days, ObjectMapper mapper) { return new SettingsHandler("UpdateSettings", db, access, days, mapper, false); }

  /** The database a deployment with DEMO_RESET runs its demo in; the reset script uses the same name. */
  public static final String DEMO_DATABASE = "waypoint_demo";

  static boolean inDemoDatabase(Database db) {
    return DEMO_DATABASE.equals(db.queryOne("SELECT current_database() AS name").get("name"));
  }

  /** A request deploy/vps/demo-reset.sh carries out. */
  static void request(Database db, Actor actor, java.util.UUID id, String key, String reason) {
    db.update("INSERT INTO demo.scenario_runs(id,scenario_key,actor,reason,outcome) VALUES (?,?,?,?,'requested')",
        id, key, actor.userId(), reason);
  }

  static final class SettingsHandler implements CommandHandler {
    private final String verb;
    private final Database db;
    private final DemoAccess access;
    private final DemoDayQuery days;
    private final ObjectMapper mapper;
    private final boolean separate;
    SettingsHandler(String verb, Database db, DemoAccess access, DemoDayQuery days, ObjectMapper mapper, boolean separate) {
      this.verb=verb; this.db=db; this.access=access; this.days=days; this.mapper=mapper; this.separate=separate;
    }
    @Override public String kind() { return "demo:" + verb; }
    @Override public String action() { return kind(); }
    @Override public ModuleRole moduleRole() { return ModuleRole.DEMO; }
    @Override public String resource(Command c) { return "wpt:demo:settings:global"; }
    @Override public Object handle(Actor actor, Command command) {
      access.admin(actor);
      db.queryOne("SELECT pg_advisory_xact_lock(hashtext('demo.dispatch')) AS locked");
      var r=db.queryOne("SELECT * FROM demo.settings WHERE id FOR UPDATE");
      long version=((Number)r.get("row_version")).longValue();
      if (command.expectedVersion()==null || command.expectedVersion()!=version)
        throw new DomainException(ErrorCode.VERSION_CONFLICT,"Demo settings changed; refresh first");
      var old=DemoSettingsQuery.settings(r);
      var p=CommandPayload.of(command);
      String reason=p.requiredText("reason");
      if(reason.length()<3 || reason.length()>500) throw new DomainException(ErrorCode.VALIDATION_FAILED,"Use a reason of 3-500 characters");
      if(separate && verb.equals("Enable") && !inDemoDatabase(db)) {
        // The real database stays as it is; the server switches to the demo database.
        if(db.queryOne("SELECT 1 FROM demo.scenario_runs WHERE scenario_key='demo:SwitchToDemo' AND outcome='requested' LIMIT 1")!=null)
          throw new DomainException(ErrorCode.CONFLICT,"The switch to the demo is already on its way");
        request(db, actor, command.commandId(), "demo:SwitchToDemo", reason);
        return Map.of("enabled",false,"switching",true,"rowVersion",version,"reason",reason);
      }
      if (!old.enabled() && !verb.equals("Enable")) throw new DomainException(ErrorCode.CONFLICT,"Demo mode is off");
      boolean enabled=verb.equals("Enable") || (!verb.equals("Disable") && old.enabled());
      long offset=enabled ? old.offsetSeconds() : 0;
      if(verb.equals("SetClock")) {
        Instant target;
        try { target=Instant.parse(p.requiredText("target")); }
        catch(java.time.format.DateTimeParseException invalid) { throw new DomainException(ErrorCode.VALIDATION_FAILED,"Choose a valid target time"); }
        if(days.latestClose().filter(target::isBefore).isPresent())
          throw new DomainException(ErrorCode.CONFLICT,"The clock cannot precede the last committed order close");
        offset=DemoSettings.offsetTo(Duration.between(Clock.system().now(),target).getSeconds());
      }
      var next=new DemoSettings(enabled,offset,
          integer(command,"simPointIntervalMs",old.simPointIntervalMs()), integer(command,"positionFlushMs",old.positionFlushMs()),
          p.flag("banner",old.banner()), integer(command,"speed",old.speed()));
      int changed=db.update("UPDATE demo.settings SET enabled=?,clock_offset_seconds=?,sim_point_interval_ms=?,position_flush_ms=?,banner=?,speed=?,row_version=row_version+1,updated_by=?,updated_at=now() WHERE id AND row_version=?",
          next.enabled(),next.offsetSeconds(),next.simPointIntervalMs(),next.positionFlushMs(),next.banner(),next.speed(),actor.userId(),version);
      if(changed!=1) throw new DomainException(ErrorCode.VERSION_CONFLICT,"Demo settings changed");
      if(!enabled) db.update("UPDATE demo.simulations SET status='stopped',row_version=row_version+1 WHERE status IN ('running','paused')");

      var result=Map.of("enabled",enabled,"offsetSeconds",offset,"rowVersion",version+1,"reason",reason);
      db.update("INSERT INTO demo.scenario_runs(id,scenario_key,actor,reason,outcome,details) VALUES (?,?,?,?,?,?::jsonb)",
          command.commandId(),kind(),actor.userId(),reason,"completed",mapper.valueToTree(result).toString());
      if(separate && verb.equals("Disable") && inDemoDatabase(db)) {
        request(db, actor, java.util.UUID.nameUUIDFromBytes((command.commandId()+":switch-back").getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            "demo:SwitchBack", reason);
        return Map.of("enabled",false,"switching",true,"offsetSeconds",0L,"rowVersion",version+1,"reason",reason);
      }
      return result;
    }
  }
  static int integer(Command c,String field,int fallback) {
    var value=c.payload().get(field);
    if(value==null) return fallback;
    if(!value.isIntegralNumber() || !value.canConvertToInt()) throw new DomainException(ErrorCode.VALIDATION_FAILED,"Invalid " + field);
    return value.intValue();
  }
}
