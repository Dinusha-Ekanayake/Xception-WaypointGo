package com.waypoint.dispatch.identity.web;

import com.waypoint.dispatch.identity.application.AccountQuery;
import com.waypoint.dispatch.platform.web.RequestAuthorizer;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reading accounts, scopes and driver assignments.
 *
 * <p>Reads only, and deliberately. Every change to an account is a command
 * through {@code POST /api/commands}, so it gets an idempotency receipt, a version
 * guard and an audit row that commit with it. A second write path here would have
 * none of those, and would be the one a client reached for.
 *
 * <p>Each response carries {@code rowVersion}, which is what the caller sends back
 * as {@code expectedVersion}. A read surface that hides the version makes the
 * version guard unusable.
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountAdminController {
  private final AccountQuery accounts;
  private final RequestAuthorizer authorizer;

  public AccountAdminController(AccountQuery accounts, RequestAuthorizer authorizer) {
    this.accounts = accounts;
    this.authorizer = authorizer;
  }

  /**
   * Listing accounts is reading the shape of the organisation, so it needs the
   * same permission as changing one. There is no read-only account browser.
   */
  @GetMapping
  public Map<String, Object> list(
      @RequestParam(required = false) String after,
      @RequestParam(defaultValue = "50") int limit,
      HttpServletRequest request) {
    authorizer.require(request, "iam:UpdateUser", "wpt:iam:user:*");
    List<AccountQuery.AccountView> page = accounts.page(after, limit);
    // Cursor paginated on a keyset. The next cursor is the last email returned,
    // so a new account appearing mid-scan cannot shift a page.
    String next = page.isEmpty() ? null : page.get(page.size() - 1).email();
    return Map.of("accounts", page, "nextAfter", next == null ? "" : next);
  }

  @GetMapping("/{userId}")
  public AccountQuery.AccountView one(@PathVariable String userId, HttpServletRequest request) {
    UUID id = uuid(userId);
    authorizer.require(request, "iam:UpdateUser", "wpt:iam:user:" + id);
    return accounts.byId(id);
  }

  @GetMapping("/driver-assignments")
  public List<AccountQuery.AssignmentView> assignments(
      @RequestParam(required = false) String on, HttpServletRequest request) {
    authorizer.require(request, "iam:AssignDriver", "wpt:iam:assignment:*");
    return accounts.assignments(on == null || on.isBlank() ? null : date(on));
  }

  private static UUID uuid(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "Not a uuid: " + value);
    }
  }

  private static LocalDate date(String value) {
    try {
      return LocalDate.parse(value);
    } catch (RuntimeException e) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "on must be a date as yyyy-mm-dd, not " + value);
    }
  }
}
