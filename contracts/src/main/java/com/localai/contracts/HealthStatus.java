package com.localai.contracts;

public record HealthStatus(String status, String details) {
    public HealthStatus {
        if (status == null || status.isBlank()) {
            status = "UP";
        }
    }
}
