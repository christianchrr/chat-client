package com.localai.contracts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ToolInvocationStatusResponse(
        UUID invocationId,
        String operation,
        List<String> requiredPermissions,
        boolean sideEffecting,
        ToolInvocationStatus status,
        Instant createdAt,
        Instant updatedAt) {
    public ToolInvocationStatusResponse {
        if (invocationId == null || operation == null || operation.isBlank()
                || requiredPermissions == null || status == null || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("Tool invocation status fields are required");
        }
        requiredPermissions = List.copyOf(requiredPermissions);
    }
}
