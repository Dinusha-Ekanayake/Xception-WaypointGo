-- With DEMO_RESET on, the demo runs in a database of its own (R-DEMO-06):
-- demo:Enable and demo:Disable ask the server to switch, and demo:RequestReset
-- asks it to start the demo database again from a fresh install. The commands
-- only record requests; deploy/vps/demo-reset.sh does the work. Covered by the
-- administrator's demo:* statement.
INSERT INTO iam.action_catalogue(action,module,description,implemented) VALUES
 ('demo:RequestReset','demo','Ask for the demo database to start again from a fresh install',true)
ON CONFLICT (action) DO NOTHING;
