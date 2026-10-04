package com.waypoint.dispatch.ordering.application;

import com.waypoint.dispatch.demo.contract.DemoRuntime;
import com.waypoint.dispatch.identity.contract.IdentityQuery;
import com.waypoint.dispatch.ordering.domain.*;
import com.waypoint.dispatch.ordering.infrastructure.JdbcOrderRepository;
import com.waypoint.dispatch.platform.config.AppProperties;
import com.waypoint.dispatch.platform.db.*;
import com.waypoint.dispatch.platform.messaging.*;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.*;
import com.waypoint.dispatch.shared.util.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Date;
import java.util.*;
import org.springframework.stereotype.Component;

/** Adds a new S1 date through the command bus. Never rewrites an earlier day. */
@Component
public class PrepareDemoDayHandler implements CommandHandler {
  private final Database db;
  private final JdbcOrderRepository orders;
  private final EventPublisher events;
  private final ReferenceQuery ref;
  private final IdentityQuery identity;
  private final DemoRuntime demo;
  private final AppProperties config;
  private final Clock clock;
  public PrepareDemoDayHandler(Database db, JdbcOrderRepository orders, EventPublisher events,
      ReferenceQuery ref, IdentityQuery identity, DemoRuntime demo, AppProperties config, Clock clock) {
    this.db=db; this.orders=orders; this.events=events; this.ref=ref; this.identity=identity;
    this.demo=demo; this.config=config; this.clock=clock;
  }
  public String kind() { return "order:PrepareDemoDay"; }
  public String action() { return kind(); }
  public ModuleRole moduleRole() { return ModuleRole.ORDERING; }
  public String resource(Command c) { return "wpt:order:depot:Peliyagoda"; }
  public Object handle(Actor actor, Command command) {
    if(!demo.view().enabled() || !identity.scopeOf(actor.userId()).roles().contains("admin"))
      throw new DomainException(ErrorCode.FORBIDDEN,"Demo preparation requires an administrator and enabled demo mode");
    var p=CommandPayload.of(command); var day=p.date("serviceDate");
    if(command.expectedVersion()==null || command.expectedVersion()!=0)
      throw new DomainException(ErrorCode.VERSION_CONFLICT,"A new demo date starts at version zero");
    if(!Boolean.TRUE.equals(db.queryOne("SELECT app.actor_has_depot('Peliyagoda') AS ok").get("ok")))
      throw new DomainException(ErrorCode.FORBIDDEN,"Peliyagoda is outside your scope");
    db.queryOne("SELECT pg_advisory_xact_lock(hashtext(?)) AS locked", "demo.seed:"+day);
    if(!ref.isOperating(day) || db.queryOne("SELECT 1 FROM ordering.orders WHERE depot_code='Peliyagoda' AND delivery_date=? LIMIT 1",Date.valueOf(day))!=null
        || orders.isClosed("Peliyagoda",day)) throw new DomainException(ErrorCode.CONFLICT,"Choose an empty operating date");
    var now=clock.now();
    var rows=DeliveryDaySeed.read(Path.of(config.dataDir()).resolve("Test Data/task2b_peak_day_scenarios.csv"));
    List<String> ids=new ArrayList<>();
    for(var row:rows) {
      var outlet=ref.outlet(row.get("outlet_id"),null).orElseThrow();
      var reservation=new Reservation("SEED-WH-"+command.commandId()+"-"+row.get("order_ref"),
          new BigDecimal(row.get("order_weight_kg")),new BigDecimal(row.get("order_volume_m3")),
          row.get("temp_requirement"),Integer.parseInt(row.get("order_units")));
      var id=UuidV7.generate(now,new java.security.SecureRandom());
      var order=Order.place(id,OrderRef.derive(actor.userId(),id),outlet.outletId(),outlet.depotCode(),outlet.brandCode(),outlet.districtName(),
          new DeliveryDate(day,day,List.of()),Optional.of(reservation),List.of(new OrderLine("SEED-"+outlet.brandCode()+"-"+reservation.temperature(),reservation.itemCount())));
      orders.insert(order,actor.userId(),now,command.commandId(),Optional.empty());
      orders.recordStatus(order.orderId(),Optional.empty(),order.status(),"Demo S1: "+p.requiredText("reason"),actor.userId(),Optional.empty(),now);
      events.publish(actor,OrderMessages.placed(order)); ids.add(order.orderId().toString());
    }
    return Map.of("serviceDate",day.toString(),"depotCode","Peliyagoda","placed",ids.size(),"orderIds",ids);
  }
}
