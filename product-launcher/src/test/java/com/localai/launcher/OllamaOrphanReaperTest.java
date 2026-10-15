package com.localai.launcher;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaOrphanReaperTest {
    @Test
    void leavesProcessesWithLiveParentsAndUnrelatedProcessesAlone() throws Exception {
        Process child = new ProcessBuilder("ping", "-n", "20", "127.0.0.1").start();
        try {
            OllamaFirstRunSetup.reapOrphanedRuntimeProcesses(Path.of("C:\\no-such-runtime-dir"));
            assertTrue(child.isAlive());
        } finally {
            child.destroyForcibly();
        }
    }
}