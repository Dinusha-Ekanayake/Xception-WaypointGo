package com.waypoint.dispatch.identity.application;

import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The identity commands, as a family.
 *
 * <p>One file because each handler is a few lines of payload reading over
 * {@link AccountAdminUseCase}, and nine files of imports and constructors would
 * hide how similar they are rather than reveal anything. Each is still a named
 * class, so a stack trace and a grep both find it.
 *
 * <p>Every rule lives in the use case. What lives here is the mapping from a wire
 * payload to a call, and the resource string a policy is written against:
 * {@code wpt:iam:user:<id>}, so an administrator can be allowed to manage one
 * depot's accounts and no others. Policy decides the action, the scope tables
 * decide the rows, and neither substitutes for the other.
 */
@Configuration
public class IdentityCommandHandlers {

  @Bean
  CreateAccountHandler createAccountHandler(AccountAdminUseCase accounts) {
    return new CreateAccountHandler(accounts);
  }

  @Bean
  UpdateAccountHandler updateAccountHandler(AccountAdminUseCase accounts) {
    return new UpdateAccountHandler(accounts);
  }

  @Bean
  DisableAccountHandler disableAccountHandler(AccountAdminUseCase accounts) {
    return new DisableAccountHandler(accounts);
  }

  @Bean
  ResetPasswordHandler resetPasswordHandler(AccountAdminUseCase accounts) {
    return new ResetPasswordHandler(accounts);
  }

  @Bean
  ChangeRoleHandler changeRoleHandler(AccountAdminUseCase accounts) {
    return new ChangeRoleHandler(accounts);
  }

  @Bean
  GrantScopeHandler grantScopeHandler(AccountAdminUseCase accounts) {
    return new GrantScopeHandler(accounts);
  }

  @Bean
  RevokeScopeHandler revokeScopeHandler(AccountAdminUseCase accounts) {
    return new RevokeScopeHandler(accounts);
  }

  @Bean
  AssignDriverHandler assignDriverHandler(AccountAdminUseCase accounts) {
    return new AssignDriverHandler(accounts);
  }

  @Bean
  EndDriverAssignmentHandler endDriverAssignmentHandler(AccountAdminUseCase accounts) {
    return new EndDriverAssignmentHandler(accounts);
  }

  // ---- the handlers ----

  static final class CreateAccountHandler extends IdentityHandler {
    CreateAccountHandler(AccountAdminUseCase accounts) {
      super(accounts, "iam:CreateUser", "iam:CreateUser");
    }

    /** The account does not exist yet, so the target is the collection. */
    @Override
    public String resource(Command command) {
      return "wpt:iam:user:*";
    }

    @Override
    public Object handle(Actor actor, Command command) {
      CommandPayload payload = CommandPayload.of(command);
      UUID userId =
          accounts.applyCreate(
              actor,
              payload.requiredText("email"),
              payload.requiredText("displayName"),
              payload.secret("password"),
              payload.requiredText("roleCode"));
      return Map.of("userId", userId.toString());
    }
  }

  static final class UpdateAccountHandler extends IdentityHandler {
    UpdateAccountHandler(AccountAdminUseCase accounts) {
      super(accounts, "iam:UpdateUser", "iam:UpdateUser");
    }

    @Override
    public Object handle(Actor actor, Command command) {
      CommandPayload payload = CommandPayload.of(command);
      UUID userId = payload.uuid("userId");
      accounts.applyUpdate(
          actor,
          userId,
          payload.text("displayName"),
          payload.text("email"),
          command.expectedVersion());
      return Map.of("userId", userId.toString());
    }
  }

  static final class DisableAccountHandler extends IdentityHandler {
    DisableAccountHandler(AccountAdminUseCase accounts) {
      super(accounts, "iam:DisableUser", "iam:DisableUser");
    }

    @Override
    public Object handle(Actor actor, Command command) {
      UUID userId = CommandPayload.of(command).uuid("userId");
      if (userId.equals(actor.userId())) {
        // Not paternalism: an administrator who disables their own account while
        // holding the only admin policy locks everyone out, permanently.
        throw new DomainException(ErrorCode.VALIDATION_FAILED, "An account cannot disable itself");
      }
      int revoked = accounts.applyDisable(actor, userId, command.expectedVersion());
      return Map.of("userId", userId.toString(), "sessionsRevoked", revoked);
    }
  }

  static final class ResetPasswordHandler extends IdentityHandler {
    ResetPasswordHandler(AccountAdminUseCase accounts) {
      super(accounts, "iam:ResetPassword", "iam:ResetPassword");
    }

    @Override
    public Object handle(Actor actor, Command command) {
      CommandPayload payload = CommandPayload.of(command);
      UUID userId = payload.uuid("userId");
      accounts.applyResetPassword(
          actor, userId, payload.secret("password"), command.expectedVersion());
      // The password is never echoed back, not even to the administrator who set it.
      return Map.of("userId", userId.toString());
    }
  }

  static final class ChangeRoleHandler extends IdentityHandler {
    ChangeRoleHandler(AccountAdminUseCase accounts) {
      super(accounts, "iam:ChangeRole", "iam:ChangeRole");
    }

    @Override
    public Object handle(Actor actor, Command command) {
      CommandPayload payload = CommandPayload.of(command);
      UUID userId = payload.uuid("userId");
      if (userId.equals(actor.userId())) {
        // The same reason an account cannot disable itself (R-IAM-16): the only
        // administrator demoting themselves leaves nobody who can undo it.
        throw new DomainException(
            ErrorCode.VALIDATION_FAILED, "An account cannot change its own role");
      }
      String roleCode = payload.requiredText("roleCode");
      int revoked = accounts.applyChangeRole(actor, userId, roleCode, command.expectedVersion());
      return Map.of("userId", userId.toString(), "roleCode", roleCode, "sessionsRevoked", revoked);
    }
  }

  static final class GrantScopeHandler extends IdentityHandler {
    GrantScopeHandler(AccountAdminUseCase accounts) {
      super(accounts, "iam:GrantScope", "iam:GrantScope");
    }

    @Override
    public Object handle(Actor actor, Command command) {
      return changeScope(accounts, actor, command, true);
    }
  }

  static final class RevokeScopeHandler extends IdentityHandler {
    RevokeScopeHandler(AccountAdminUseCase accounts) {
      super(accounts, "iam:RevokeScope", "iam:RevokeScope");
    }

    @Override
    public Object handle(Actor actor, Command command) {
      return changeScope(accounts, actor, command, false);
    }
  }

  static final class AssignDriverHandler extends IdentityHandler {
    AssignDriverHandler(AccountAdminUseCase accounts) {
      super(accounts, "iam:AssignDriver", "iam:AssignDriver");
    }

    /** The account the vehicle is given to, which is also what the version guards. */
    @Override
    public String resource(Command command) {
      UUID driver = CommandPayload.of(command).optionalUuid("driverUserId");
      return driver == null ? null : "wpt:iam:user:" + driver;
    }

    @Override
    public Object handle(Actor actor, Command command) {
      CommandPayload payload = CommandPayload.of(command);
      LocalDate from = payload.date("from");
      UUID assignmentId =
          accounts.applyAssignDriver(
              actor,
              payload.requiredText("vehicleId"),
              payload.uuid("driverUserId"),
              from,
              payload.optionalDate("until"),
              command.expectedVersion());
      return Map.of("assignmentId", assignmentId.toString(), "from", from.toString());
    }
  }

  /**
   * Ending an assignment is the same authority as making one, so it shares the
   * action, but it is a different decision and therefore a different kind. Folding
   * it into an optional field on the assign command would make "release this
   * vehicle today" indistinguishable from "I mistyped the end date".
   */
  static final class EndDriverAssignmentHandler extends IdentityHandler {
    EndDriverAssignmentHandler(AccountAdminUseCase accounts) {
      super(accounts, "iam:EndDriverAssignment", "iam:AssignDriver");
    }

    /** The subject is the vehicle's assignment, not an account. */
    @Override
    public String resource(Command command) {
      UUID assignmentId = CommandPayload.of(command).optionalUuid("assignmentId");
      return assignmentId == null ? null : "wpt:iam:assignment:" + assignmentId;
    }

    @Override
    public Object handle(Actor actor, Command command) {
      CommandPayload payload = CommandPayload.of(command);
      UUID assignmentId = payload.uuid("assignmentId");
      LocalDate on = payload.date("on");
      accounts.applyEndDriverAssignment(actor, assignmentId, on, command.expectedVersion());
      return Map.of("assignmentId", assignmentId.toString(), "endedOn", on.toString());
    }
  }

  // ---- shared ----

  /**
   * Exactly one of depot or outlet. Accepting both would make the audit line
   * ambiguous about what was actually granted.
   */
  private static Object changeScope(
      AccountAdminUseCase accounts, Actor actor, Command command, boolean granting) {
    CommandPayload payload = CommandPayload.of(command);
    UUID userId = payload.uuid("userId");
    String depotCode = payload.text("depotCode");
    String outletId = payload.text("outletId");

    if ((depotCode == null) == (outletId == null)) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "Give exactly one of depotCode or outletId");
    }
    if (depotCode != null) {
      if (granting) {
        accounts.applyGrantDepot(actor, userId, depotCode, command.expectedVersion());
      } else {
        accounts.applyRevokeDepot(actor, userId, depotCode, command.expectedVersion());
      }
      return Map.of("userId", userId.toString(), "depotCode", depotCode);
    }
    if (granting) {
      accounts.applyGrantOutlet(actor, userId, outletId, command.expectedVersion());
    } else {
      accounts.applyRevokeOutlet(actor, userId, outletId, command.expectedVersion());
    }
    return Map.of("userId", userId.toString(), "outletId", outletId);
  }

  /** The kind, the action, the database role, and a resource naming the account. */
  abstract static class IdentityHandler implements CommandHandler {
    final AccountAdminUseCase accounts;
    private final String kind;
    private final String action;

    IdentityHandler(AccountAdminUseCase accounts, String kind, String action) {
      this.accounts = accounts;
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
    public String resource(Command command) {
      UUID userId = CommandPayload.of(command).optionalUuid("userId");
      return userId == null ? null : "wpt:iam:user:" + userId;
    }
  }
}
