package com.localai.model.ollama;

import com.localai.model.ModelRuntimeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaRuntimeManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void canStartAndStopManagedLoopbackRuntime() throws Exception {
        Path modelRoot = tempDir.resolve("models");
        int port = 18080;

        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");

        ModelRuntimeConfig config = new ModelRuntimeConfig(
                List.of(javaExecutable, "-cp", classpath, OllamaStubServerMain.class.getName(), "127.0.0.1", Integer.toString(port), modelRoot.toString()),
                tempDir,
                modelRoot,
                "127.0.0.1",
                port,
                null
        );

        OllamaRuntimeManager manager = new OllamaRuntimeManager(config);
        try {
            manager.start();
            assertTrue(manager.waitForReady(Duration.ofSeconds(10)), "runtime should become ready");
            assertTrue(manager.isRunning(), "runtime should still be alive");
        } finally {
            manager.stop();
            assertTrue(!manager.isRunning(), "runtime should be stopped");
        }
    }
}
