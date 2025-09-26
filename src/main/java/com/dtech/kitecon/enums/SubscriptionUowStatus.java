package com.dtech.kitecon.enums;

public enum SubscriptionUowStatus {
    ACTIVE,   // ready to be (re)processed periodically
    WIP,      // currently being processed
    FAILED,   // failed; eligible for retry
    INACTIVE  // no longer relevant (e.g., expired series)
}
