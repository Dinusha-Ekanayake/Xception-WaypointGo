-- Intelligence's handlers and read endpoints now enforce these actions (issue #16).
-- implemented means "a handler or an endpoint enforces this action" (008).
--
-- ml:ExportTrainingData is new: the delivery actuals a model retrains on. The
-- administrator already holds ml:* (20260930T1201); dispatchers and auditors
-- hold only ml:Read, so no policy version changes.

INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('ml:ExportTrainingData', 'intelligence', 'Export delivery actuals for model training', true)
ON CONFLICT (action) DO UPDATE SET implemented = true;

UPDATE iam.action_catalogue SET implemented = true
 WHERE action IN ('ml:Read', 'ml:RegisterModel', 'ml:ActivateModel', 'ml:RetireModel');
