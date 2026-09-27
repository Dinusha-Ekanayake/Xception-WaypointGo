package com.waypoint.dispatch.referencedata.contract;

import java.util.UUID;

/**
 * Identifies one immutable snapshot of master data (decision D1). A plan records
 * the version it was built against, so re-reading history never silently changes
 * a past decision.
 */
public record ReferenceVersionId(UUID value) {}
