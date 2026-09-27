package com.waypoint.dispatch.platform.config;

import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The connection pool. Connects as waypoint_app, which is NOINHERIT, owns no
 * tables and holds no BYPASSRLS, so a transaction that forgets SET LOCAL ROLE
 * fails with a permission error instead of running with full access.
 *
 * <p>Startup never blocks on the database: initializationFailTimeout is negative,
 * so the process comes up and reports liveness even when PostgreSQL is down.
 * Readiness is what tells you the database is reachable.
 */
@Configuration
public class DataConfig {

  @Bean(destroyMethod = "close")
  public DataSource dataSource(
      @Value("${DATABASE_URL:}") String databaseUrl,
      @Value("${spring.datasource.hikari.maximum-pool-size:8}") int maxPool) {
    HikariDataSource ds = new HikariDataSource();
    String raw = databaseUrl == null ? "" : databaseUrl.trim();
    ds.setJdbcUrl(raw.isBlank() ? "jdbc:postgresql://127.0.0.1:5432/waypoint" : toJdbcUrl(raw));
    String username = username(raw);
    String password = password(raw);
    if (username != null) {
      ds.setUsername(username);
    }
    if (password != null) {
      ds.setPassword(password);
    }
    ds.setMaximumPoolSize(maxPool);
    ds.setInitializationFailTimeout(-1);
    // Fail fast when PostgreSQL is unreachable so readiness answers 503 instead of hanging.
    ds.setConnectionTimeout(3000);
    ds.setValidationTimeout(2000);
    ds.setPoolName("waypoint");
    return ds;
  }

  @Bean
  public JdbcTemplate jdbcTemplate(DataSource dataSource) {
    JdbcTemplate template = new JdbcTemplate(dataSource);
    template.setQueryTimeout(15);
    return template;
  }

  @Bean
  public PlatformTransactionManager transactionManager(DataSource dataSource) {
    return new DataSourceTransactionManager(dataSource);
  }

  /** Serializable by default. Anything weaker is a deliberate, argued exception. */
  @Bean
  public TransactionTemplate serializableTransactions(PlatformTransactionManager manager) {
    TransactionTemplate template = new TransactionTemplate(manager);
    template.setIsolationLevel(TransactionTemplate.ISOLATION_SERIALIZABLE);
    template.setTimeout(15);
    return template;
  }

  static String toJdbcUrl(String raw) {
    if (raw.startsWith("jdbc:")) {
      return raw;
    }
    URI uri = URI.create(raw.startsWith("postgres://") ? "postgresql://" + raw.substring(11) : raw);
    StringBuilder sb = new StringBuilder("jdbc:postgresql://").append(uri.getHost());
    if (uri.getPort() > 0) {
      sb.append(':').append(uri.getPort());
    }
    sb.append(uri.getPath() == null || uri.getPath().isBlank() ? "/waypoint" : uri.getPath());
    if (uri.getQuery() != null) {
      sb.append('?').append(uri.getQuery());
    }
    return sb.toString();
  }

  static String username(String raw) {
    String info = userInfo(raw);
    if (info == null) {
      return null;
    }
    int colon = info.indexOf(':');
    return colon < 0 ? info : info.substring(0, colon);
  }

  static String password(String raw) {
    String info = userInfo(raw);
    if (info == null) {
      return null;
    }
    int colon = info.indexOf(':');
    return colon < 0 ? null : info.substring(colon + 1);
  }

  private static String userInfo(String raw) {
    if (raw.isBlank() || raw.startsWith("jdbc:")) {
      return null;
    }
    return URI.create(raw.startsWith("postgres://") ? "postgresql://" + raw.substring(11) : raw)
        .getUserInfo();
  }
}
