-- Loading's handlers and read endpoints now enforce these actions (issue #10).
-- implemented means "a handler or an endpoint enforces this action" (008).
--
-- loading:HandBack is new: the loader holding a trip lets it go and keeps the
-- ticks under their name, so another loader can take it (R-LOD-11). The role
-- policies already reach it: WaypointLoader and the dispatcher allow loading:*,
-- and the auditor has no Allow for it, so default deny applies.
--
-- loading:Handover and loading:RequestInterchange stay unimplemented. Neither
-- has a screen in the Day 5 design, and interchange needs Planning's
-- previewInterchange (#9).

INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('loading:HandBack', 'loading', 'Let go of a trip, keeping its checks, so another loader can take it', true)
ON CONFLICT (action) DO UPDATE SET implemented = true;

UPDATE iam.action_catalogue SET implemented = true
 WHERE action IN ('loading:Start', 'loading:Check', 'loading:Shortfall', 'loading:Release', 'loading:Read');
