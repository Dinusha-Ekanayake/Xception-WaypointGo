-- 015 Foreign keys that were not indexed
--     An unindexed FK turns every parent delete and join into a
--     sequential scan. The original covers the main read paths well;
--     these are the gaps.
-- ---------------------------------------------------------

CREATE INDEX IF NOT EXISTS ix_allocations_order          ON ops.order_allocations(order_id);
CREATE INDEX IF NOT EXISTS ix_allocations_decided_by     ON ops.order_allocations(decided_by);
CREATE INDEX IF NOT EXISTS ix_order_status_history_order ON ops.order_status_history(order_id, changed_at DESC);
CREATE INDEX IF NOT EXISTS ix_order_items_product        ON ops.order_items(product_id);
CREATE INDEX IF NOT EXISTS ix_orders_created_by          ON ops.orders(created_by);
CREATE INDEX IF NOT EXISTS ix_deferrals_plan             ON ops.order_deferrals(planning_run_id);
CREATE INDEX IF NOT EXISTS ix_route_legs_to_outlet       ON ops.route_legs(to_outlet_id);
CREATE INDEX IF NOT EXISTS ix_issues_trip                ON ops.operational_issues(trip_id);
CREATE INDEX IF NOT EXISTS ix_issues_order               ON ops.operational_issues(order_id);
CREATE INDEX IF NOT EXISTS ix_issues_allocation          ON ops.operational_issues(allocation_id);
CREATE INDEX IF NOT EXISTS ix_pod_delivery_record        ON ops.proof_of_delivery(delivery_record_id);
CREATE INDEX IF NOT EXISTS ix_delivery_driver            ON ops.delivery_records(driver_user_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS ix_sync_device                ON integration.sync_operations(device_id, received_at DESC);
CREATE INDEX IF NOT EXISTS ix_audit_actor                ON integration.audit_log(actor_user_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS ix_vehicle_driver_vehicle     ON iam.vehicle_driver_assignments(vehicle_id, valid_from DESC);
CREATE INDEX IF NOT EXISTS ix_vehicle_driver_user        ON iam.vehicle_driver_assignments(driver_user_id);
