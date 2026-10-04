package com.waypoint.dispatch.identity.application;

import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.util.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registering and retiring a device, as commands. The rules are in
 * {@link DeviceRegistry}; what lives here is the mapping from a wire payload to a
 * call, and the resource a policy is written against: {@code wpt:iam:device:<id>}.
 */
@Configuration
public class DeviceCommandHandlers {

  @Bean
  CommandHandler registerDeviceHandler(DeviceRegistry devices) {
    return new DeviceHandler("iam:RegisterDevice") {
      /** The device does not exist yet, so the target is the collection. */
      @Override
      public String resource(Command command) {
        return "wpt:iam:device:*";
      }

      @Override
      public Object handle(Actor actor, Command command) {
        CommandPayload payload = CommandPayload.of(command);
        UUID deviceId =
            devices.applyRegister(
                actor,
                payload.requiredText("label"),
                payload.requiredText("kind"),
                payload.text("depotCode"));
        return Map.of("deviceId", deviceId.toString());
      }
    };
  }

  @Bean
  CommandHandler retireDeviceHandler(DeviceRegistry devices, Clock clock) {
    return new DeviceHandler("iam:RetireDevice") {
      @Override
      public String resource(Command command) {
        UUID deviceId = CommandPayload.of(command).optionalUuid("deviceId");
        return deviceId == null ? null : "wpt:iam:device:" + deviceId;
      }

      @Override
      public Object handle(Actor actor, Command command) {
        UUID deviceId = CommandPayload.of(command).uuid("deviceId");
        int revoked = devices.applyRetire(actor, deviceId, command.expectedVersion(), clock.realTime().now());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", deviceId.toString());
        result.put("sessionsRevoked", revoked);
        return result;
      }
    };
  }

  private abstract static class DeviceHandler implements CommandHandler {
    private final String kind;

    DeviceHandler(String kind) {
      this.kind = kind;
    }

    @Override
    public final String kind() {
      return kind;
    }

    /** One kind per action here, so the two names are the same string. */
    @Override
    public final String action() {
      return kind;
    }

    @Override
    public final ModuleRole moduleRole() {
      return ModuleRole.IAM;
    }
  }
}
