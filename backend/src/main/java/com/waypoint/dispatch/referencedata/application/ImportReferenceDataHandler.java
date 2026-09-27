package com.waypoint.dispatch.referencedata.application;

import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.referencedata.domain.ReferenceSnapshot;
import com.waypoint.dispatch.referencedata.domain.ReferenceValidator;
import com.waypoint.dispatch.referencedata.domain.ReferenceViolation;
import com.waypoint.dispatch.referencedata.infrastructure.CsvReferenceImporter;
import com.waypoint.dispatch.referencedata.infrastructure.ReferenceCache;
import com.waypoint.dispatch.referencedata.infrastructure.ReferenceVersionReader;
import com.waypoint.dispatch.referencedata.infrastructure.ReferenceVersionWriter;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stage, validate, hash, publish. In that order, in one transaction.
 *
 * <p>An import either publishes completely or changes nothing. Partial reference
 * data is worse than none, because planning would run against it and produce a
 * confident wrong answer.
 */
@Component
public class ImportReferenceDataHandler {
  private static final Logger log = LoggerFactory.getLogger(ImportReferenceDataHandler.class);

  private final CsvReferenceImporter importer;
  private final ReferenceVersionWriter writer;
  private final ReferenceVersionReader reader;
  private final ReferenceCache cache;
  private final Database database;
  private final Metrics metrics;

  public ImportReferenceDataHandler(
      CsvReferenceImporter importer,
      ReferenceVersionWriter writer,
      ReferenceVersionReader reader,
      ReferenceCache cache,
      Database database,
      Metrics metrics) {
    this.importer = importer;
    this.writer = writer;
    this.reader = reader;
    this.cache = cache;
    this.database = database;
    this.metrics = metrics;
  }

  /** What an import did, so the caller can tell a publish from a no-op. */
  public record ImportOutcome(UUID versionId, boolean published, int outlets, int vehicles) {}

  public ImportOutcome importFrom(Path dataDir, UUID importedBy) {
    CsvReferenceImporter.Staged staged = importer.stage(dataDir);

    List<ReferenceViolation> violations =
        ReferenceValidator.validate(staged.snapshot(), ReferenceValidator.Expectations.waypoint());
    if (!violations.isEmpty()) {
      metrics.increment("waypoint.reference.import.rejected");
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED,
          "Reference import refused with " + violations.size() + " violation(s)",
          violations.stream().map(ReferenceViolation::toString).toList());
    }

    return database.asModule(
        ModuleRole.REF,
        importedBy,
        () -> {
          // Identical content is a no-op by hash, not a new version of the world.
          var existing = writer.findByHash(staged.contentHash());
          if (existing.isPresent()) {
            metrics.increment("waypoint.reference.import.unchanged");
            log.info("Reference data unchanged; version {} already holds it", existing.get());
            reader.load(existing.get()).ifPresent(cache::publish);
            return new ImportOutcome(existing.get(), false, 0, 0);
          }

          UUID versionId =
              writer.insertVersion(staged.sourceLabel(), staged.contentHash(), importedBy);
          writer.writeRows(versionId, staged.snapshot());
          writer.makeCurrent(versionId);

          ReferenceSnapshot published =
              reader
                  .load(versionId)
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "Version " + versionId + " was written but could not be read back"));
          cache.publish(published);
          metrics.increment("waypoint.reference.import.published");
          return new ImportOutcome(
              versionId,
              true,
              published.allOutlets().size(),
              published.allVehicles().size());
        });
  }
}
