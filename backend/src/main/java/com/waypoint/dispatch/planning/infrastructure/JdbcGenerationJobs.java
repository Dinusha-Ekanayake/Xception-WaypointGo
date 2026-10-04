package com.waypoint.dispatch.planning.infrastructure;

import com.waypoint.dispatch.platform.db.Database;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * The queue of plan generations (planning v2, R-PLN-41): one active job per
 * depot and day, claimed by a worker with {@code FOR UPDATE SKIP LOCKED} and a
 * lease, so replicas share the queue and a crashed worker's job is claimed again.
 */
@Repository
public class JdbcGenerationJobs {
  private static final String COLUMNS =
      "job_id, depot_code, service_date, status, keep_decisions, requested_by, command_id, attempts, plan_id, "
          + "result::text AS result, error, created_at, started_at, finished_at";

  public record JobRow(
      UUID jobId,
      String depotCode,
      LocalDate serviceDate,
      String status,
      boolean keepDecisions,
      UUID requestedBy,
      UUID commandId,
      int attempts,
      Optional<UUID> planId,
      Optional<String> result,
      Optional<String> error,
      Instant createdAt,
      Optional<Instant> startedAt,
      Optional<Instant> finishedAt) {}

  private final Database database;

  public JdbcGenerationJobs(Database database) {
    this.database = database;
  }

  /** Records a job unless one is already queued or running for the day; either way, the active job. */
  public JobRow enqueue(UUID jobId, String depot, LocalDate date, boolean keep, UUID by, UUID commandId, Instant now) {
    database.update(
        """
        INSERT INTO planning.generation_jobs
            (job_id, depot_code, service_date, status, keep_decisions, requested_by, command_id, created_at)
        VALUES (?, ?, ?, 'queued', ?, ?, ?, ?)
        ON CONFLICT (depot_code, service_date) WHERE status IN ('queued', 'running') DO NOTHING
        """,
        jobId, depot, Date.valueOf(date), keep, by, commandId, Timestamp.from(now));
    return active(depot, date).orElseThrow();
  }

  public Optional<JobRow> active(String depot, LocalDate date) {
    return one("SELECT " + COLUMNS + " FROM planning.generation_jobs WHERE depot_code = ? AND service_date = ?"
        + " AND status IN ('queued', 'running')", depot, Date.valueOf(date));
  }

  public Optional<JobRow> find(UUID jobId) {
    return one("SELECT " + COLUMNS + " FROM planning.generation_jobs WHERE job_id = ?", jobId);
  }

  public Optional<JobRow> latest(String depot, LocalDate date) {
    return one("SELECT " + COLUMNS + " FROM planning.generation_jobs WHERE depot_code = ? AND service_date = ?"
        + " ORDER BY created_at DESC LIMIT 1", depot, Date.valueOf(date));
  }

  /**
   * The oldest job no live worker holds: queued, or running with a lapsed lease.
   * Another worker skips the row this one locked.
   */
  public Optional<JobRow> claim(Instant now, Duration lease) {
    List<Map<String, Object>> rows =
        database.query(
            """
            UPDATE planning.generation_jobs j
               SET status = 'running', lease_until = ?, attempts = j.attempts + 1,
                   started_at = COALESCE(j.started_at, ?), row_version = j.row_version + 1
             WHERE j.job_id = (
                   SELECT job_id FROM planning.generation_jobs
                    WHERE status = 'queued' OR (status = 'running' AND lease_until < ?)
                    ORDER BY created_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1)
            RETURNING """ + " " + COLUMNS.replace("result::text AS result", "j.result::text AS result"),
            Timestamp.from(now.plus(lease)), Timestamp.from(now), Timestamp.from(now));
    return rows.stream().findFirst().map(JdbcGenerationJobs::row);
  }

  public void done(UUID jobId, UUID planId, String result, Instant now) {
    database.update(
        "UPDATE planning.generation_jobs SET status = 'done', plan_id = ?, result = ?::jsonb, error = NULL,"
            + " lease_until = NULL, finished_at = ?, row_version = row_version + 1 WHERE job_id = ?",
        planId, result, Timestamp.from(now), jobId);
  }

  /** Back to the queue, for another attempt, with why the last one did not finish. */
  public void requeue(UUID jobId, String why) {
    database.update(
        "UPDATE planning.generation_jobs SET status = 'queued', error = ?, lease_until = NULL,"
            + " row_version = row_version + 1 WHERE job_id = ?",
        why, jobId);
  }

  public void failed(UUID jobId, String why, Instant now) {
    database.update(
        "UPDATE planning.generation_jobs SET status = 'failed', error = ?, lease_until = NULL, finished_at = ?,"
            + " row_version = row_version + 1 WHERE job_id = ?",
        why, Timestamp.from(now), jobId);
  }

  public int waiting() {
    return ((Number) database.queryOne(
        "SELECT count(*) AS n FROM planning.generation_jobs WHERE status IN ('queued', 'running')").get("n")).intValue();
  }

  private Optional<JobRow> one(String sql, Object... params) {
    return database.query(sql, params).stream().findFirst().map(JdbcGenerationJobs::row);
  }

  private static JobRow row(Map<String, Object> r) {
    return new JobRow(
        (UUID) r.get("job_id"),
        (String) r.get("depot_code"),
        ((Date) r.get("service_date")).toLocalDate(),
        (String) r.get("status"),
        (Boolean) r.get("keep_decisions"),
        (UUID) r.get("requested_by"),
        (UUID) r.get("command_id"),
        ((Number) r.get("attempts")).intValue(),
        Optional.ofNullable((UUID) r.get("plan_id")),
        Optional.ofNullable((String) r.get("result")),
        Optional.ofNullable((String) r.get("error")),
        ((Timestamp) r.get("created_at")).toInstant(),
        Optional.ofNullable((Timestamp) r.get("started_at")).map(Timestamp::toInstant),
        Optional.ofNullable((Timestamp) r.get("finished_at")).map(Timestamp::toInstant));
  }
}
