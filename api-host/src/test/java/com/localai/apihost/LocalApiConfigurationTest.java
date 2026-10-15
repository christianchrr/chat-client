package com.localai.apihost;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalApiConfigurationTest {
    private static final long GIB = 1024L * 1024 * 1024;

    @Test
    void sizesContextToInstalledMemory() {
        assertEquals(4096, LocalApiConfiguration.contextForMemory(8 * GIB));
        assertEquals(8192, LocalApiConfiguration.contextForMemory(16 * GIB));
        assertEquals(16384, LocalApiConfiguration.contextForMemory(64 * GIB));
    }
}