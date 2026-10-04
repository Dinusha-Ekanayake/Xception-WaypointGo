-- delivery:ArriveAtDepot, the driver at the depot with the vehicle (R-EXE-24).
--
-- A new action needs a catalogue row and a handler (R-IAM). No policy changes:
-- the driver policy already allows delivery:*, and the handler refuses anyone
-- who does not drive the vehicle today. The administrator holds every action.

INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('delivery:ArriveAtDepot', 'execution', 'Say the vehicle is at the depot, ready to be loaded', true)
ON CONFLICT (action) DO UPDATE SET implemented = true;
