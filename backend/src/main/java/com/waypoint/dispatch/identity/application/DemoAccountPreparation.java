package com.waypoint.dispatch.identity.application;

import com.waypoint.dispatch.demo.contract.DemoRuntime;
import com.waypoint.dispatch.identity.contract.DemoAccounts;
import com.waypoint.dispatch.identity.contract.IdentityQuery;
import com.waypoint.dispatch.platform.db.*;
import com.waypoint.dispatch.platform.messaging.*;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.*;
import java.sql.Date;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class DemoAccountPreparation implements CommandHandler,DemoAccounts {
  private final Database db; private final AccountAdminUseCase accounts; private final IdentityQuery identity;
  private final DemoRuntime runtime; private final ReferenceQuery ref;
  public DemoAccountPreparation(Database db,AccountAdminUseCase accounts,IdentityQuery identity,DemoRuntime runtime,ReferenceQuery ref){this.db=db;this.accounts=accounts;this.identity=identity;this.runtime=runtime;this.ref=ref;}
  public Map<String,UUID> accounts() {
    return db.readAs(ModuleRole.IAM,db.ambientActor().orElse(null),()->{
      Map<String,UUID> result=new LinkedHashMap<>();
      for(String role:List.of("dispatcher","loader","driver","store_manager")) {
        var row=db.queryOne("SELECT u.user_id FROM iam.users u JOIN iam.user_roles r ON r.user_id=u.user_id WHERE u.email=? AND u.is_active AND r.role_code=?",role+"@waypoint.local",role);
        if(row!=null)result.put(role,(UUID)row.get("user_id"));
      }
      return Map.copyOf(result);
    });
  }
  public String kind(){return "iam:PrepareDemoDay";} public String action(){return kind();}
  public ModuleRole moduleRole(){return ModuleRole.IAM;} public String resource(Command c){return "wpt:iam:demo:accounts";}
  public Object handle(Actor actor,Command c) {
    if(!runtime.view().enabled() || !identity.scopeOf(actor.userId()).roles().contains("admin"))throw new DomainException(ErrorCode.FORBIDDEN,"Demo administrator required");
    if(c.expectedVersion()==null || c.expectedVersion()!=0)throw new DomainException(ErrorCode.VERSION_CONFLICT,"New preparation requires version zero");
    var users=accounts(); var day=CommandPayload.of(c).date("serviceDate");
    if(users.size()!=4)throw new DomainException(ErrorCode.CONFLICT,"Create the four dedicated demo accounts with the normal setup first");
    UUID manager=users.get("store_manager"),driver=users.get("driver");
    if(db.queryOne("SELECT 1 FROM iam.user_outlet_access WHERE user_id=? AND outlet_id='OUT001'",manager)==null)
      accounts.applyGrantOutlet(actor,manager,"OUT001",version(manager));
    int assigned=0;
    for(var v:ref.availableVehicles("Peliyagoda",day,null)) {
      var existing=identity.driverOn(v.vehicleId(),day);
      if(existing.isPresent())continue;
      accounts.applyAssignDriver(actor,v.vehicleId(),driver,day,day.plusDays(1),version(driver));assigned++;
    }
    return Map.of("assigned",assigned,"serviceDate",day.toString(),"pin","Existing loader PIN preserved");
  }
  private long version(UUID id){return ((Number)db.queryOne("SELECT row_version FROM iam.users WHERE user_id=?",id).get("row_version")).longValue();}
}
