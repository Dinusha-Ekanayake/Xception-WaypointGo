package com.waypoint.dispatch.identity.application;

import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.referencedata.infrastructure.CsvReferenceLoader;
import com.waypoint.dispatch.shared.util.Crypto;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Host-admin CLI only. Never exposed through the public API. */
@Component
public class AccountAdminUseCase {
  private final Database db;
  private final CsvReferenceLoader reference;
  public AccountAdminUseCase(Database db, CsvReferenceLoader reference) {
    this.db = db; this.reference = reference;
  }

  public void execute(String action, Map<String, String> input) {
    if (!List.of("account-create", "account-update", "account-password", "account-disable", "account-enable").contains(action)) {
      throw new IllegalArgumentException("Unknown account command");
    }
    String id = required(input, "ACCOUNT_ID").toLowerCase(java.util.Locale.ROOT);
    String operator = required(input, "ACCOUNT_OPERATOR");
    if (!id.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new IllegalArgumentException("ACCOUNT_ID must be an email");
    String role = input.getOrDefault("ACCOUNT_ROLE", "");
    String scope = input.getOrDefault("ACCOUNT_SCOPE", "");
    if (List.of("account-create", "account-update").contains(action)) {
      boolean valid = switch (role) {
        case "dispatcher" -> "all".equals(scope);
        case "driver" -> reference.get().vehicles().stream().anyMatch(v -> v.vehicleId().equals(scope));
        case "loader" -> reference.get().vehicles().stream().anyMatch(v -> v.depot().equals(scope));
        case "store" -> reference.get().outlets().stream().anyMatch(o -> o.outletId().equals(scope));
        default -> false;
      };
      if (!valid) throw new IllegalArgumentException("Invalid ACCOUNT_ROLE or ACCOUNT_SCOPE");
    }
    String password = input.getOrDefault("ACCOUNT_PASSWORD", "");
    if (List.of("account-create", "account-password").contains(action) && (password.length() < 12 || password.length() >= 200)) {
      throw new IllegalArgumentException("ACCOUNT_PASSWORD must contain 12 to 199 characters");
    }
    db.transaction(() -> {
      db.all("SELECT pg_advisory_xact_lock(71842002)");
      db.all("SELECT pg_advisory_xact_lock(hashtextextended(?,1))", id);
      var current = db.get("SELECT role,enabled FROM users WHERE id=?", id);
      if ("account-create".equals(action) && current != null) throw new IllegalArgumentException("Account already exists; use an explicit update");
      if (!"account-create".equals(action) && current == null) throw new IllegalArgumentException("Account not found");
      if (current != null && Boolean.TRUE.equals(current.get("enabled")) && "dispatcher".equals(current.get("role"))
          && ("account-disable".equals(action) || ("account-update".equals(action) && !"dispatcher".equals(role)))) {
        var count = db.get("SELECT count(*) AS count FROM users WHERE role='dispatcher' AND enabled");
        if (((Number) count.get("count")).longValue() <= 1) throw new IllegalArgumentException("Cannot remove the last enabled dispatcher");
      }
      String salt = Crypto.randomHex(16);
      switch (action) {
        case "account-create" -> db.run("INSERT INTO users(id,role,scope,salt,hash) VALUES(?,?,?,?,?)", id, role, scope, salt, Crypto.passwordHashHex(password, salt));
        case "account-password" -> db.run("UPDATE users SET salt=?,hash=? WHERE id=?", salt, Crypto.passwordHashHex(password, salt), id);
        case "account-update" -> db.run("UPDATE users SET role=?,scope=? WHERE id=?", role, scope, id);
        case "account-disable" -> db.run("UPDATE users SET enabled=false WHERE id=?", id);
        case "account-enable" -> db.run("UPDATE users SET enabled=true WHERE id=?", id);
        default -> throw new IllegalArgumentException("Unknown account command");
      }
      db.run("DELETE FROM sessions WHERE user_id=?", id);
      db.run("INSERT INTO account_audit(account_id,operator,action) VALUES(?,?,?)", id, operator, action);
    });
  }
  private static String required(Map<String, String> input, String key) {
    String value = input.getOrDefault(key, "").trim();
    if (value.isEmpty() || value.length() > 200) throw new IllegalArgumentException("Set " + key + " (1 to 200 characters)");
    return value;
  }
}
