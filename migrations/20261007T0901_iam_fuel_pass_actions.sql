-- Roadmap FF-06b: the actions of the Fuel Pass on the driver's phone.
--
-- A new action needs a catalogue row and a handler (R-IAM). No policy changes:
-- the administrator already holds vehicle:* and the driver delivery:*, and no
-- other role holds either. Row-level security on execution.fuel_passes narrows
-- the read to the driver assigned to the vehicle.

INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('vehicle:SetFuelPass',    'execution', 'Upload the official Fuel Pass QR of a vehicle, replacing the one before', true),
    ('vehicle:RevokeFuelPass', 'execution', 'Withdraw a vehicle''s Fuel Pass QR, with a reason', true),
    ('delivery:ReadFuelPass',  'execution', 'Read the Fuel Pass QR of the vehicle the driver drives today or tomorrow', true)
ON CONFLICT (action) DO UPDATE SET implemented = true;
