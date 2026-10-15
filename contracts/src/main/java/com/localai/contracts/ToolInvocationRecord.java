package com.localai.contracts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ToolInvocationRecord(
        UUID id,
        UUID turnId,
        String idempotencyKey,
        String operation,
        List<String> requiredPermissions,
        boolean sideEffecting,
        ToolInvocationStatus status,
        Instant createdAt,
        Instant updatedAt) {
    public ToolInvocationRecord {
        if (id == null || turnId == null || idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > 200 || operation == null || operation.isBlank()
                || operation.length() > 128 || requiredPermissions == null || requiredPermissions.isEmpty()
                || status == null || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("Tool invocation identity and policy fields are required");
        }
        requiredPermissions = List.copyOf(requiredPermissions);
        if (requiredPermissions.stream().anyMatch(permission -> permission == null || permission.isBlank()
                || permission.length() > 128)) {
            throw new IllegalArgumentException("Tool invocation permission names must be bounded and non-blank");
        }
    }
}
