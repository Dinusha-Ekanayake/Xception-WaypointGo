-- Notification's handlers and read endpoints now enforce these actions (issue #14).
-- implemented means "a handler or an endpoint enforces this action" (008).
--
-- notification:MarkAllRead is new. The role policies already grant notification:*
-- to the dispatcher, loader, driver and store manager (20260930T1201), so no
-- policy version changes.

INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('notification:MarkAllRead', 'notification', 'Mark every notification up to a time read', true)
ON CONFLICT (action) DO UPDATE SET implemented = true;

UPDATE iam.action_catalogue SET implemented = true
 WHERE action IN ('notification:Read', 'notification:MarkRead', 'notification:Subscribe',
                  'notification:Unsubscribe');
