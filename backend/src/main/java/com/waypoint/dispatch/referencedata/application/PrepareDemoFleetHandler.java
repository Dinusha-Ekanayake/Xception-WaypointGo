package com.waypoint.dispatch.referencedata.application;

import com.waypoint.dispatch.demo.contract.DemoRuntime;
import com.waypoint.dispatch.identity.contract.IdentityQuery;
import com.waypoint.dispatch.platform.config.AppProperties;
import com.waypoint.dispatch.platform.db.*;
import com.waypoint.dispatch.platform.messaging.*;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.*;
import java.nio.file.*;
import java.sql.Date;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class PrepareDemoFleetHandler implements CommandHandler {
  private final Database db;private final DemoRuntime runtime;private final IdentityQuery identity;private final AppProperties config;
  public PrepareDemoFleetHandler(Database db,DemoRuntime runtime,IdentityQuery identity,AppProperties config){this.db=db;this.runtime=runtime;this.identity=identity;this.config=config;}
  public String kind(){return "reference:PrepareDemoDay";}public String action(){return kind();}
  public ModuleRole moduleRole(){return ModuleRole.REF;}public String resource(Command c){return "wpt:ref:depot:Peliyagoda";}
  public Object handle(Actor actor,Command c) {
    if(!runtime.view().enabled() || !identity.scopeOf(actor.userId()).roles().contains("admin"))throw new DomainException(ErrorCode.FORBIDDEN,"Demo administrator required");
    if(c.expectedVersion()==null || c.expectedVersion()!=0)throw new DomainException(ErrorCode.VERSION_CONFLICT,"New preparation requires version zero");
    var day=CommandPayload.of(c).date("serviceDate");int count=0;
    try {
      for(String line:Files.readAllLines(Path.of(config.dataDir()).resolve("Test Data/task2b_peak_day_fleet.csv")).stream().skip(1).toList()) {
        if(line.isBlank())continue;var cells=line.split(",",-1);
        if(cells[2].equals("available"))continue;
        count+=db.update("INSERT INTO ref.vehicle_day_status(vehicle_id,service_date,status,reason,set_by) VALUES (?,?,?,?,?) ON CONFLICT (vehicle_id,service_date) DO NOTHING",cells[1],Date.valueOf(day),cells[2],"Seed: demo S1 "+c.commandId(),actor.userId());
      }
    }catch(java.io.IOException error){throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE,"S1 fleet dataset unavailable");}
    return Map.of("marked",count,"serviceDate",day.toString());
  }
}
