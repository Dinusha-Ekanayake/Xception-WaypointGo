-- Ordering's handlers and read endpoints now enforce these actions (issue #8).
-- implemented means "a handler or an endpoint enforces this action" (008).

UPDATE iam.action_catalogue SET implemented = true
 WHERE action IN ('order:Place', 'order:Amend', 'order:Cancel', 'order:Read', 'order:CloseForDay');
