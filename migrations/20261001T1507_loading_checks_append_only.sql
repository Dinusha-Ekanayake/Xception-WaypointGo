-- Earlier check attempts are custody evidence. No application role may edit
-- them, and the trigger also protects against privileged maintenance writes.
REVOKE UPDATE ON loading.item_checks FROM waypoint_loading;

CREATE FUNCTION loading.reject_item_check_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'loading.item_checks is append only' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER item_checks_append_only
    BEFORE UPDATE OR DELETE ON loading.item_checks
    FOR EACH ROW EXECUTE FUNCTION loading.reject_item_check_mutation();
