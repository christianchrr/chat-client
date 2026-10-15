package com.localai.contracts;

public enum TurnStatus {
    RUNNING,
    CANCEL_REQUESTED,
    COMPLETED,
    FAILED,
    CANCELLED,
    INTERRUPTED,
    NEEDS_REVIEW
}
