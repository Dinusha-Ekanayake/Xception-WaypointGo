-- Receipt's handlers and read endpoints now enforce these actions (issue #13).
-- implemented means "a handler or an endpoint enforces this action" (008).

UPDATE iam.action_catalogue SET implemented = true
 WHERE action IN ('receipt:Confirm', 'receipt:ConfirmPartial', 'receipt:Dispute', 'receipt:Read');
