package com.waypoint.dispatch.identity.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Policy administration, as commands.
 *
 * <p>These were controller methods that wrote directly, which meant the one
 * change that decides what everyone may do had no receipt, could be applied
 * twice by a retry, and could be lost to a second administrator working from the
 * same page. Each is now a kind on the bus like any other write.
 *
 * <p>The resource is {@code wpt:iam:policy:<name>}, so an administrator can be
 * allowed to manage some policies and not others. Creating a version and moving
 * the default share {@code iam:CreatePolicy} with creating the policy: authoring
 * a document is one authority, however many steps it takes.
 */
@Configuration
public class PolicyCommandHandlers {

  @Bean
  CommandHandler createPolicyHandler(PolicyAdminUseCase policies) {
    return new PolicyHandler("iam:CreatePolicy", "iam:CreatePolicy") {
      @Override
      public Object handle(Actor actor, Command command) {
        CommandPayload payload = CommandPayload.of(command);
        String name = payload.requiredText("name");
        UUID policyId =
            policies.applyCreate(actor, name, payload.text("description"), document(command));
        return Map.of("policyId", policyId.toString(), "name", name, "versionNumber", 1);
      }
    };
  }

  @Bean
  CommandHandler createPolicyVersionHandler(PolicyAdminUseCase policies) {
    return new PolicyHandler("iam:CreatePolicyVersion", "iam:CreatePolicy") {
      @Override
      public Object handle(Actor actor, Command command) {
        String name = CommandPayload.of(command).requiredText("name");
        int version =
            policies.applyCreateVersion(actor, name, document(command), command.expectedVersion());
        return Map.of("name", name, "versionNumber", version);
      }
    };
  }

  @Bean
  CommandHandler setDefaultPolicyVersionHandler(PolicyAdminUseCase policies) {
    return new PolicyHandler("iam:SetDefaultPolicyVersion", "iam:CreatePolicy") {
      @Override
      public Object handle(Actor actor, Command command) {
        String name = CommandPayload.of(command).requiredText("name");
        JsonNode version = command.payload().get("versionNumber");
        if (version == null || !version.canConvertToInt()) {
          throw new DomainException(ErrorCode.VALIDATION_FAILED, "versionNumber must be a number");
        }
        policies.applySetDefaultVersion(actor, name, version.intValue(), command.expectedVersion());
        return Map.of("name", name, "versionNumber", version.intValue());
      }
    };
  }

  @Bean
  CommandHandler attachPolicyHandler(PolicyAdminUseCase policies) {
    return new PolicyHandler("iam:AttachPolicy", "iam:AttachPolicy") {
      @Override
      public Object handle(Actor actor, Command command) {
        CommandPayload payload = CommandPayload.of(command);
        String name = payload.requiredText("name");
        String type = payload.requiredText("principalType");
        String id = payload.requiredText("principalId");
        policies.applyAttach(actor, name, type, id, command.expectedVersion());
        return Map.of("name", name, "principalType", type, "principalId", id);
      }
    };
  }

  @Bean
  CommandHandler detachPolicyHandler(PolicyAdminUseCase policies) {
    return new PolicyHandler("iam:DetachPolicy", "iam:DetachPolicy") {
      @Override
      public Object handle(Actor actor, Command command) {
        CommandPayload payload = CommandPayload.of(command);
        String name = payload.requiredText("name");
        String type = payload.requiredText("principalType");
        String id = payload.requiredText("principalId");
        policies.applyDetach(actor, name, type, id, command.expectedVersion());
        return Map.of("name", name, "principalType", type, "principalId", id);
      }
    };
  }

  /** The document travels as JSON inside the payload, and is stored as the text it arrived as. */
  private static String document(Command command) {
    JsonNode document = command.payload() == null ? null : command.payload().get("document");
    if (document == null || !document.isObject()) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "document must be a JSON object");
    }
    return document.toString();
  }

  /** The kind, the action, the identity role, and a resource naming the policy. */
  private abstract static class PolicyHandler implements CommandHandler {
    private final String kind;
    private final String action;

    PolicyHandler(String kind, String action) {
      this.kind = kind;
      this.action = action;
    }

    @Override
    public final String kind() {
      return kind;
    }

    @Override
    public final String action() {
      return action;
    }

    @Override
    public final ModuleRole moduleRole() {
      return ModuleRole.IAM;
    }

    @Override
    public final String resource(Command command) {
      String name = CommandPayload.of(command).text("name");
      return name == null ? null : "wpt:iam:policy:" + name;
    }
  }
}
