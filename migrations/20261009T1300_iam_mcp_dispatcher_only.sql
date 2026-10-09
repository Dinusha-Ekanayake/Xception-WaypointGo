-- Decision 2026-10-09 (@Oxshadha): connecting an AI assistant over MCP is for the
-- dispatcher. The driver, the loader and the store manager do not get it by
-- role: only the dispatcher's Settings ever offered the button, but the server
-- still let those roles connect a client of their own.
--
-- The role attachments of WaypointMcpReader and WaypointMcpWriter are detached,
-- as iam:DetachPolicy does, so mcp:Connect falls to the default Deny for those
-- roles (R-IAM-03). An administrator can still attach WaypointMcpReader to one
-- named person, or back to a role, with no deployment. The dispatcher, the
-- administrator and the auditor keep what they had.
DELETE FROM iam.policy_attachments a
USING iam.policies p
WHERE a.policy_id = p.policy_id
  AND p.name IN ('WaypointMcpReader', 'WaypointMcpWriter')
  AND a.principal_type = 'role'
  AND a.principal_id IN ('driver', 'loader', 'store_manager');

-- A policy change clears every cached decision on the next request.
UPDATE iam.policy_generation SET generation = generation + 1, changed_at = now();
