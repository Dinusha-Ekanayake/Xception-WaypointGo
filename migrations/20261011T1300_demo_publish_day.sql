-- Publishing a prepared demo day is its own control room action (demo:PublishDay),
-- separate from seeding its orders (demo:ResetDay). It plans and publishes the
-- day through plan:PrepareDemoDay. The administrator's demo:* statement covers it.
INSERT INTO iam.action_catalogue(action,module,description,implemented) VALUES
 ('demo:PublishDay','demo','Plan and publish a prepared demo day',true)
ON CONFLICT (action) DO NOTHING;
