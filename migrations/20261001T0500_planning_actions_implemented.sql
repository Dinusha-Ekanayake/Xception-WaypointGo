-- Planning's draft and publication handlers now enforce these actions (issue #9,
-- step 5). implemented means "a handler or an endpoint enforces this action" (008).
-- plan:Revise and plan:Replan stay unimplemented until step 6.

UPDATE iam.action_catalogue SET implemented = true
 WHERE action IN ('plan:Generate', 'plan:Override', 'plan:Defer', 'plan:Publish');
