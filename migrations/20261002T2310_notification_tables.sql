-- Notification's tables (issue #14). Schema and role came with 20260930T1200.
--
--     An event becomes one notification per person who needs to hear about it,
--     and every channel it went out on is a delivery row, so "the store was told"
--     is a fact the system can prove. Channels are the in-app inbox and web push
--     (D-N). Nothing here is ever deleted: a notification is read or not, a
--     delivery reaches sent or dead, a subscription is unsubscribed or expired.
--
--     Who hears about what is data, not code: a versioned routing table the
--     module may read but not change.

-- ---- routing ----------------------------------------------------------------

CREATE TABLE notification.routing_versions (
    rule_version integer     PRIMARY KEY,
    published_at timestamptz NOT NULL,
    is_current   boolean     NOT NULL DEFAULT false,
    note         text        NOT NULL
);

CREATE UNIQUE INDEX ux_routing_versions_current ON notification.routing_versions (is_current) WHERE is_current;

COMMENT ON TABLE notification.routing_versions IS
  'One version of the routing matrix is current. A change is a new version, never an edit.';

CREATE TABLE notification.routing_rules (
    rule_version     integer NOT NULL REFERENCES notification.routing_versions (rule_version),
    event_type       text    NOT NULL,
    recipient_role   text    NOT NULL,
    scope_kind       text    NOT NULL,
    push             boolean NOT NULL,
    -- Applies only when the event's fact has one of these values, for example
    -- a warehouse status of insufficient or expired.
    when_fact        text,
    when_values      text[],
    -- Applies only when the rule for this role found nobody, for example the
    -- dispatcher hearing of a released trip that has no driver (LOD-05).
    only_if_none_for text,
    title_template   text    NOT NULL,
    body_template    text    NOT NULL,
    PRIMARY KEY (rule_version, event_type, recipient_role),
    CONSTRAINT ck_routing_scope CHECK (scope_kind IN ('outlet','depot','vehicle')),
    CONSTRAINT ck_routing_role CHECK (recipient_role IN ('dispatcher','loader','driver','store_manager')),
    CONSTRAINT ck_routing_condition CHECK ((when_fact IS NULL) = (when_values IS NULL))
);

COMMENT ON TABLE notification.routing_rules IS
  'Event type to recipient role and scope, with the message template. {name} is filled from the event.';

INSERT INTO notification.routing_versions (rule_version, published_at, is_current, note)
VALUES (1, TIMESTAMPTZ '2026-10-02 20:00:00+05:30', true, 'issue #14, MODULES section 9');

INSERT INTO notification.routing_rules
    (rule_version, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
     title_template, body_template)
VALUES
    (1, 'order.deferred', 'store_manager', 'outlet', true, NULL, NULL, NULL,
     'Order deferred',
     'Your order planned for {serviceDate} was deferred: {reason}'),
    (1, 'order.auto_deferred', 'store_manager', 'outlet', true, NULL, NULL, NULL,
     'Order moved to {toDate}',
     'The warehouse did not confirm stock before the cutoff, so the order moved from {fromDate} to {toDate}.'),
    (1, 'order.unservable', 'store_manager', 'outlet', true, NULL, NULL, NULL,
     'Order cannot be delivered',
     'No vehicle can take this order: {reason}'),
    (1, 'order.unservable', 'dispatcher', 'depot', true, NULL, NULL, NULL,
     'Unservable order at {outletId}',
     'No vehicle can take it: {reason}. It needs a decision.'),
    (1, 'warehouse.order_status_changed', 'store_manager', 'outlet', true, 'status', ARRAY['insufficient','expired'], NULL,
     'Stock problem on an order',
     'The warehouse reported the order as {status}. Adjust and place it again.'),
    (1, 'plan.published', 'loader', 'depot', true, NULL, NULL, NULL,
     'Plan published for {serviceDate}',
     'Version {planVersion} with {tripCount} trips is ready to load.'),
    (1, 'plan.published', 'driver', 'vehicle', true, NULL, NULL, NULL,
     'Your trip for {serviceDate}',
     'Trip {tripNumber} on {vehicleId}, departing {plannedDeparture}, {stopCount} stops.'),
    (1, 'plan.revised', 'loader', 'depot', true, NULL, NULL, NULL,
     'Plan revised for {serviceDate}',
     'Version {planVersion} with {tripCount} trips replaces the earlier plan: {reason}'),
    (1, 'plan.revised', 'driver', 'vehicle', true, NULL, NULL, NULL,
     'Your trip changed for {serviceDate}',
     'Trip {tripNumber} on {vehicleId}, departing {plannedDeparture}, {stopCount} stops. {reason}'),
    (1, 'trip.released', 'driver', 'vehicle', true, NULL, NULL, NULL,
     'Your vehicle is loaded',
     '{vehicleId} is released for {serviceDate} with {stopCount} stops.'),
    (1, 'trip.released', 'dispatcher', 'depot', true, NULL, NULL, 'driver',
     'Released trip has no driver',
     '{vehicleId} was released for {serviceDate} with no driver assigned.'),
    (1, 'loading.shortfall', 'dispatcher', 'depot', true, NULL, NULL, NULL,
     'Loading shortfall',
     '{kind}: {missingUnits} units. {reason} Departure is blocked.'),
    (1, 'delivery.started', 'store_manager', 'outlet', false, NULL, NULL, NULL,
     'Delivery arriving',
     'The driver has started your delivery.'),
    (1, 'delivery.completed', 'store_manager', 'outlet', false, NULL, NULL, NULL,
     'Delivery recorded',
     'Your delivery was recorded as {outcome}. Proof is ready to review.'),
    (1, 'delivery.failed', 'dispatcher', 'depot', true, NULL, NULL, NULL,
     'Delivery failed at {outletId}',
     '{reason}. It needs a decision.'),
    (1, 'delivery.failed', 'store_manager', 'outlet', true, NULL, NULL, NULL,
     'Delivery not completed',
     'Your delivery could not be completed: {reason}'),
    (1, 'eta.changed', 'store_manager', 'outlet', true, NULL, NULL, NULL,
     'Arrival time changed',
     'Now expected at {expectedArrival}, {delayMinutes} minutes late.'),
    (1, 'eta.changed', 'dispatcher', 'depot', true, NULL, NULL, NULL,
     'Delivery running late',
     '{outletId} now expected at {expectedArrival}, {delayMinutes} minutes late.'),
    (1, 'issue.raised', 'dispatcher', 'depot', true, NULL, NULL, NULL,
     'Issue raised: {issueType}',
     '{severity} severity at {depotCode}.'),
    (1, 'issue.raised', 'store_manager', 'outlet', true, NULL, NULL, NULL,
     'Issue raised: {issueType}',
     '{severity} severity, about your outlet.'),
    (1, 'issue.escalated', 'dispatcher', 'depot', true, NULL, NULL, NULL,
     'Issue escalated: {issueType}',
     'A {severity} issue waited {waitedMinutes} minutes with no one assigned.'),
    (1, 'vehicle.fault_reported', 'dispatcher', 'depot', true, NULL, NULL, NULL,
     'Vehicle fault: {vehicleId}',
     '{description}'),
    (1, 'road.disruption_reported', 'dispatcher', 'depot', true, NULL, NULL, NULL,
     'Road disruption',
     '{vehicleId} in {districtName}: {description}'),
    (1, 'receipt.disputed', 'dispatcher', 'depot', true, NULL, NULL, NULL,
     'Receipt disputed at {outletId}',
     '{reason}'),
    (1, 'vehicle.status_changed', 'dispatcher', 'depot', false, NULL, NULL, NULL,
     'Vehicle {vehicleId} is {status}',
     'From {serviceDate}. {reason}');

-- ---- notifications ----------------------------------------------------------

CREATE TABLE notification.notifications (
    notification_id   uuid PRIMARY KEY,
    recipient_user_id uuid        NOT NULL REFERENCES iam.users (user_id),
    event_id          uuid        NOT NULL,
    event_type        text        NOT NULL,
    -- Which target of the event this is about, so a driver with two trips in one
    -- plan hears about both, and a redelivered event about neither again.
    target_key        text        NOT NULL,
    rule_version      integer     NOT NULL REFERENCES notification.routing_versions (rule_version),
    title             text        NOT NULL,
    body              text        NOT NULL,
    subject_type      text,
    subject_id        text,
    created_at        timestamptz NOT NULL,
    read_at           timestamptz,
    row_version       bigint      NOT NULL DEFAULT 1,
    CONSTRAINT uq_notifications_event UNIQUE (event_id, recipient_user_id, target_key),
    CONSTRAINT ck_notifications_subject CHECK ((subject_type IS NULL) = (subject_id IS NULL)),
    CONSTRAINT ck_notifications_read CHECK (read_at IS NULL OR read_at >= created_at - interval '1 minute')
);

COMMENT ON TABLE notification.notifications IS
  'One message to one person about one event. Kept forever; read state only ever moves from unread to read.';

CREATE INDEX ix_notifications_inbox
    ON notification.notifications (recipient_user_id, created_at DESC, notification_id DESC);
CREATE INDEX ix_notifications_unread
    ON notification.notifications (recipient_user_id, created_at) WHERE read_at IS NULL;

-- ---- push subscriptions -----------------------------------------------------

CREATE TABLE notification.push_subscriptions (
    subscription_id uuid PRIMARY KEY,
    user_id         uuid        NOT NULL REFERENCES iam.users (user_id),
    device_id       uuid,
    endpoint        text        NOT NULL,
    p256dh_key      text        NOT NULL,
    auth_secret     text        NOT NULL,
    status          text        NOT NULL,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    row_version     bigint      NOT NULL DEFAULT 1,
    CONSTRAINT ck_push_subscriptions_status CHECK (status IN ('active','unsubscribed','expired')),
    CONSTRAINT ck_push_subscriptions_endpoint CHECK (endpoint LIKE 'https://%')
);

COMMENT ON TABLE notification.push_subscriptions IS
  'A browser push endpoint per person and device. One browser has one endpoint, so it is active for one person at a time.';

CREATE UNIQUE INDEX ux_push_subscriptions_active ON notification.push_subscriptions (endpoint) WHERE status = 'active';
CREATE INDEX ix_push_subscriptions_user ON notification.push_subscriptions (user_id) WHERE status = 'active';

-- ---- deliveries -------------------------------------------------------------

CREATE TABLE notification.deliveries (
    delivery_id     uuid PRIMARY KEY,
    notification_id uuid        NOT NULL REFERENCES notification.notifications (notification_id),
    channel         text        NOT NULL,
    subscription_id uuid        REFERENCES notification.push_subscriptions (subscription_id),
    status          text        NOT NULL,
    attempts        integer     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    last_error      text,
    created_at      timestamptz NOT NULL,
    sent_at         timestamptz,
    row_version     bigint      NOT NULL DEFAULT 1,
    CONSTRAINT uq_deliveries_target UNIQUE NULLS NOT DISTINCT (notification_id, channel, subscription_id),
    CONSTRAINT ck_deliveries_channel CHECK (channel IN ('in_app','push')),
    CONSTRAINT ck_deliveries_status CHECK (status IN ('pending','sent','delivered','failed','dead')),
    CONSTRAINT ck_deliveries_push_target CHECK ((channel = 'push') = (subscription_id IS NOT NULL)),
    CONSTRAINT ck_deliveries_due CHECK (status NOT IN ('pending','failed') OR next_attempt_at IS NOT NULL),
    CONSTRAINT ck_deliveries_attempts CHECK (attempts >= 0)
);

COMMENT ON TABLE notification.deliveries IS
  'Each channel a notification went out on, with every attempt counted. A push that keeps failing reaches dead and stays.';

CREATE INDEX ix_deliveries_due ON notification.deliveries (next_attempt_at) WHERE status IN ('pending','failed');
CREATE INDEX ix_deliveries_notification ON notification.deliveries (notification_id);
CREATE INDEX ix_deliveries_subscription ON notification.deliveries (subscription_id) WHERE subscription_id IS NOT NULL;
CREATE INDEX ix_deliveries_dead ON notification.deliveries (created_at) WHERE status = 'dead';

-- ---- privileges -------------------------------------------------------------

GRANT SELECT ON notification.routing_versions, notification.routing_rules TO waypoint_notification;
REVOKE INSERT, UPDATE ON notification.routing_versions, notification.routing_rules FROM waypoint_notification;
GRANT SELECT, INSERT, UPDATE
    ON notification.notifications, notification.deliveries, notification.push_subscriptions
    TO waypoint_notification;

-- ---- row-level security -----------------------------------------------------
-- A person sees and changes only their own notifications and subscriptions; the
-- process (consumers, the push job) sees all of them.

ALTER TABLE notification.notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification.notifications FORCE ROW LEVEL SECURITY;
CREATE POLICY notifications_own ON notification.notifications
    FOR ALL TO waypoint_notification
    USING (app.actor_is_system() OR recipient_user_id = app.current_actor())
    WITH CHECK (app.actor_is_system() OR recipient_user_id = app.current_actor());

ALTER TABLE notification.push_subscriptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification.push_subscriptions FORCE ROW LEVEL SECURITY;
CREATE POLICY push_subscriptions_own ON notification.push_subscriptions
    FOR ALL TO waypoint_notification
    USING (app.actor_is_system() OR user_id = app.current_actor())
    WITH CHECK (app.actor_is_system() OR user_id = app.current_actor());

ALTER TABLE notification.deliveries ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification.deliveries FORCE ROW LEVEL SECURITY;
CREATE POLICY deliveries_own ON notification.deliveries
    FOR ALL TO waypoint_notification
    USING (EXISTS (SELECT 1 FROM notification.notifications n WHERE n.notification_id = deliveries.notification_id))
    WITH CHECK (EXISTS (SELECT 1 FROM notification.notifications n
                         WHERE n.notification_id = deliveries.notification_id));

ALTER TABLE notification.routing_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification.routing_versions FORCE ROW LEVEL SECURITY;
CREATE POLICY routing_versions_read ON notification.routing_versions FOR SELECT TO waypoint_notification USING (true);

ALTER TABLE notification.routing_rules ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification.routing_rules FORCE ROW LEVEL SECURITY;
CREATE POLICY routing_rules_read ON notification.routing_rules FOR SELECT TO waypoint_notification USING (true);
