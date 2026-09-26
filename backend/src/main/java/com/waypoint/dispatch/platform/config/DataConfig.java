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
 * Builds the PostgreSQL pool from DATABASE_URL (pooled Neon URL or local URL).
 * Accepts both `postgresql://...` and `jdbc:postgresql://...` forms.
 */
@Configuration
public class DataConfig {

  @Bean(destroyMethod = "close")
  public DataSource dataSource(
      @Value("${DATABASE_URL:}") String databaseUrl,
      @Value("${spring.datasource.hikari.maximum-pool-size:3}") int maxPool) {
    if (databaseUrl == null || databaseUrl.isBlank()) throw new IllegalStateException("DATABASE_URL is required");
    String raw = databaseUrl.trim();
    String jdbc = toJdbcUrl(raw);
    String username = username(raw);
    String password = password(raw);

    HikariDataSource ds = new HikariDataSource();
    ds.setJdbcUrl(jdbc);
    if (username != null) ds.setUsername(username);
    if (password != null) ds.setPassword(password);
    String schema = System.getenv("DATABASE_SCHEMA");
    if (schema != null && !schema.isBlank()) {
      if (!schema.matches("waypoint_test_[a-f0-9]+")) throw new IllegalArgumentException("Invalid test schema");
      ds.setSchema(schema);
    }
    ds.setMaximumPoolSize(maxPool);
    ds.setConnectionTimeout(10_000);
    ds.setIdleTimeout(10_000);
    ds.addDataSourceProperty("socketTimeout", "15");
    ds.addDataSourceProperty("connectTimeout", "10");
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

  /** Serializable transactions with statement timeouts, mirroring lib/database.ts. */
  @Bean
  public TransactionTemplate serializableTransactions(PlatformTransactionManager manager) {
    TransactionTemplate template = new TransactionTemplate(manager);
    template.setIsolationLevel(TransactionTemplate.ISOLATION_SERIALIZABLE);
    template.setTimeout(15);
    return template;
  }

  static String toJdbcUrl(String raw) {
    try {
      String normalized = raw;
      if (normalized.startsWith("postgres://")) {
        normalized = "postgresql://" + normalized.substring("postgres://".length());
      }
      if (normalized.startsWith("postgresql://")) {
        URI uri = new URI(normalized);
        StringBuilder jdbc = new StringBuilder("jdbc:postgresql://").append(uri.getHost());
        if (uri.getPort() > 0) jdbc.append(':').append(uri.getPort());
        jdbc.append(uri.getPath() == null || uri.getPath().isEmpty() ? "/postgres" : uri.getPath());
        if (uri.getQuery() != null && !uri.getQuery().isEmpty()) {
          jdbc.append('?').append(uri.getQuery());
        }
        return jdbc.toString();
      }
      return normalized;
    } catch (Exception e) {
      throw new IllegalStateException("Invalid DATABASE_URL", e);
    }
  }

  static String username(String raw) {
    String userInfo = userInfo(raw);
    if (userInfo == null || userInfo.isEmpty()) return null;
    int idx = userInfo.indexOf(':');
    return idx < 0 ? userInfo : userInfo.substring(0, idx);
  }

  static String password(String raw) {
    String userInfo = userInfo(raw);
    if (userInfo == null) return null;
    int idx = userInfo.indexOf(':');
    if (idx < 0) return null;
    try {
      return java.net.URLDecoder.decode(
          userInfo.substring(idx + 1), java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) {
      return userInfo.substring(idx + 1);
    }
  }

  private static String userInfo(String raw) {
    try {
      String normalized = raw;
      if (normalized.startsWith("postgres://")) {
        normalized = "http://" + normalized.substring("postgres://".length());
      } else if (normalized.startsWith("postgresql://")) {
        normalized = "http://" + normalized.substring("postgresql://".length());
      }
      return new URI(normalized).getUserInfo();
    } catch (Exception e) {
      return null;
    }
  }
}
