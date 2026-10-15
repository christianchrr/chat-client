package com.localai.contracts;

public enum ToolInvocationStatus {
    INTENT_RECORDED,
    AUTHORIZED,
    DENIED,
    EXECUTING,
    SUCCEEDED,
    FAILED,
    INTERRUPTED,
    NEEDS_REVIEW
}
