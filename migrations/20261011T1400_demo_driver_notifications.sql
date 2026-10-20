-- Demo publishing a plan was replaced by sample driver notices: judges publish a
-- plan themselves, and a published demo day blocked planning for everyone else.
-- demo:SendDriverNotifications puts sample notices in the demo driver's inbox
-- (covered by the administrator's demo:* statement). The two actions it replaces
-- have no handler any more, so they are marked unimplemented and the bus routes
-- neither; their catalogue rows stay because migrations are forward only.
INSERT INTO iam.action_catalogue(action,module,description,implemented) VALUES
 ('demo:SendDriverNotifications','demo','Put sample notices in the demo driver''s inbox',true)
ON CONFLICT (action) DO NOTHING;
UPDATE iam.action_catalogue SET implemented = false WHERE action IN ('plan:PrepareDemoDay','demo:PublishDay');
