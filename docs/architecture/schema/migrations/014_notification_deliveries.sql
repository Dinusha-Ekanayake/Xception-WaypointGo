-- 014 Notification intent vs delivery
--     ops.notifications says what we decided to tell someone.
--     This says whether they were actually told, per channel.
-- ---------------------------------------------------------

CREATE TABLE IF NOT EXISTS ops.notification_deliveries (
    notification_delivery_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    notification_id uuid NOT NULL REFERENCES ops.notifications(notification_id) ON DELETE CASCADE,
    channel text NOT NULL,
    status text NOT NULL DEFAULT 'pending',
    attempts integer NOT NULL DEFAULT 0,
    last_error text,
    dispatched_at timestamptz,
    delivered_at timestamptz,
    CONSTRAINT ck_notification_channel CHECK (channel IN ('in_app','email','sms','push','webhook')),
    CONSTRAINT ck_notification_delivery_status CHECK (status IN ('pending','sent','delivered','failed','dead')),
    CONSTRAINT ck_notification_attempts CHECK (attempts >= 0)
);

CREATE INDEX IF NOT EXISTS ix_notification_deliveries_pending
    ON ops.notification_deliveries(status, notification_id) WHERE status IN ('pending','failed');
