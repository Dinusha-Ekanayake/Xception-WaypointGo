-- A dispatcher sets what one trip carries in one change: orders added from the
-- deferred list or another trip, orders taken off, stops reordered, or the
-- trip removed (R-PLN-42). A new action needs a catalogue row and a handler
-- (R-IAM); the handler lands with this migration. The dispatcher's policy
-- grants plan:*; the auditor is denied by default, as for every write.
INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('plan:EditTrip', 'planning', 'Set what one trip carries and in what order, or remove it', true)
ON CONFLICT (action) DO NOTHING;
