-- The write half of the event backbone, and an actor for work no person does.
--
--     005 created integration.outbox_events and said nothing writes to it yet.
--     OutboxEventPublisher now does, inside the transaction that made the change.
--     A consumer receives an EventEnvelope, which carries the payload version,
--     the producing module, the correlation id and the actor as well as the
--     payload, so the outbox row has to carry them too or the relay (#6) would
--     have nothing to build the envelope from. All four are additive: two are
--     nullable, and the version's constant default is a metadata-only change.

ALTER TABLE integration.outbox_events
    ADD COLUMN IF NOT EXISTS event_version  integer NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS producer       text,
    ADD COLUMN IF NOT EXISTS correlation_id text,
    ADD COLUMN IF NOT EXISTS actor_id       uuid;

-- Per-aggregate ordering is the relay's guarantee (SYSTEM-ARCHITECTURE 6.3), so
-- it reads events for one aggregate in the order they were written.
CREATE INDEX IF NOT EXISTS ix_outbox_aggregate
    ON integration.outbox_events (aggregate_type, aggregate_id, occurred_at);

-- ---- consumer inbox -----------------------------------------------------
--     Delivery is at least once. The relay records (consumer, event_id) in the
--     same transaction as the consumer's own change, so a redelivery finds the
--     row and does nothing. Written with the publisher rather than the relay so
--     consumers are idempotent from the first one written.

CREATE TABLE IF NOT EXISTS integration.consumed_events (
    consumer    text        NOT NULL,
    event_id    uuid        NOT NULL,
    consumed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);

CREATE INDEX IF NOT EXISTS ix_consumed_events_at ON integration.consumed_events (consumed_at);

COMMENT ON TABLE integration.consumed_events IS
  'One row per event a consumer has applied. Makes at-least-once delivery idempotent.';

GRANT SELECT, INSERT ON integration.consumed_events TO waypoint_integration;

DO $$
DECLARE r text;
BEGIN
    FOREACH r IN ARRAY ARRAY['waypoint_ref','waypoint_iam','waypoint_ordering','waypoint_planning',
                             'waypoint_loading','waypoint_execution','waypoint_receipt',
                             'waypoint_issues','waypoint_notification','waypoint_sync',
                             'waypoint_warehouse','waypoint_ml'] LOOP
        EXECUTE format('GRANT SELECT, INSERT ON integration.consumed_events TO %I', r);
    END LOOP;
END $$;

-- ---- system actor -------------------------------------------------------
--     A subscriber or scheduled job acts for nobody, and 007 made "nobody" see
--     no rows, which is right for a request and wrong for the cutoff job. The
--     process therefore acts as one fixed principal, com.waypoint.dispatch
--     .shared.domain.Actor.SYSTEM_ID, and module policies admit it by name.
--
--     It cannot be impersonated from a request: app.actor_id is set only by
--     platform/db/Database, from a resolved session, and no session can belong
--     to it because no iam.users row has it. Its version nibble is zero, so no
--     generated id (v4 or v7) can collide with it either.

CREATE OR REPLACE FUNCTION app.actor_is_system() RETURNS boolean
LANGUAGE sql STABLE AS $$
    SELECT COALESCE(app.current_actor() = '00000000-0000-0000-0000-000000000001'::uuid, false)
$$;

COMMENT ON FUNCTION app.actor_is_system() IS
  'True when the transaction runs as the process itself (relay, scheduler). Mirrors Actor.SYSTEM_ID.';
