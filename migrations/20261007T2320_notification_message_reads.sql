-- R-MSG-14: the dispatcher's "Seen" mark reads the driver's notifications by
-- message. Older rows have no messageId fact and are simply not found.
CREATE INDEX IF NOT EXISTS ix_notifications_message
    ON notification.notifications ((facts ->> 'messageId'))
 WHERE event_type = 'message.posted';
