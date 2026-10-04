package com.waypoint.dispatch.identity.contract;
import java.util.Map;
import java.util.UUID;
/** Dedicated seeded account ids only. Never returns a password or PIN. */
public interface DemoAccounts { Map<String,UUID> accounts(); }
