package com.waypoint.dispatch.shared.event;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * An event as a consumer receives it: the payload plus who, when and why.
 *
 * <p>Delivery is at least once, so a consumer must treat {@code eventId} as its
 * idempotency key and record {@code (consumer, eventId)} before acting. Two
 * deliveries of the same envelope must be indistinguishable from one.
 *
 * @param eventId UUIDv7, so ids sort by the time they were minted
 * @param type the payload's {@link DomainEvent#type()}, duplicated here so a
 *     relay can route without deserialising the payload
 * @param version the payload's {@link DomainEvent#version()}
 * @param occurredAt server time of the transaction that produced it
 * @param producer the module that published it, for example {@code ordering}
 * @param correlationId the request that caused it, so a chain of events can be
 *     traced back to the command that started it
 * @param actorId who caused it; empty for a system actor such as the scheduler
 * @param payload the event itself
 */
public record EventEnvelope<E extends DomainEvent>(
    UUID eventId,
    String type,
    int version,
    Instant occurredAt,
    String producer,
    Optional<String> correlationId,
    Optional<UUID> actorId,
    E payload) {}
