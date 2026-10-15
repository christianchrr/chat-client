package com.localai.model.ollama;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelCatalogConfigurationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsTheBundledAllowListAndHardwareEstimates() {
        var options = ModelCatalogConfiguration.loadDefault();

        assertEquals(List.of("llama3.2:latest", "qwen3:4b", "qwen3:8b", "qwen3:14b", "qwen3:30b", "qwen3:32b",
                "qwen2.5vl:7b", "llama3.2-vision:11b"),
                options.stream().map(option -> option.modelId()).toList());
        assertEquals("Llama 3.2 3B", options.getFirst().displayName());
        assertEquals(2L * 1024 * 1024 * 1024, options.getFirst().estimatedDownloadBytes());
        assertEquals("Qwen3 30B", options.get(4).displayName());
        assertEquals(20L * 1024 * 1024 * 1024, options.get(4).estimatedDownloadBytes());
        assertEquals(22L * 1024 * 1024 * 1024, options.get(4).recommendedVramBytes());
    }

    @Test
    void loadsAdditionalValidModelsFromTheUserYamlFile() throws Exception {
        Path config = temporaryDirectory.resolve("models.yml");
        Files.writeString(config, """
                version: 1
                models:
                  - id: qwen3:8b
                    displayName: Qwen3 8B
                    estimatedDownloadGb: 6
                    recommendedVramGb: 8
                    recommendedRamGb: 12
                """);

        var options = ModelCatalogConfiguration.load(config);

        assertEquals(1, options.size());
        assertEquals("qwen3:8b", options.getFirst().modelId());
        assertEquals("Qwen3 8B", options.getFirst().displayName());
    }

    @Test
    void rejectsDuplicateOrCloudModelIds() throws Exception {
        Path duplicate = writeConfig("""
                version: 1
                models:
                  - id: qwen3:8b
                    displayName: First
                    estimatedDownloadGb: 6
                    recommendedVramGb: 8
                    recommendedRamGb: 12
                  - id: qwen3:8b
                    displayName: Duplicate
                    estimatedDownloadGb: 6
                    recommendedVramGb: 8
                    recommendedRamGb: 12
                """);
        Path cloud = writeConfig("""
                version: 1
                models:
                  - id: qwen3:8b:cloud
                    displayName: Cloud model
                    estimatedDownloadGb: 6
                    recommendedVramGb: 8
                    recommendedRamGb: 12
                """);

        IOException duplicateError = assertThrows(IOException.class,
                () -> ModelCatalogConfiguration.load(duplicate));
        IOException cloudError = assertThrows(IOException.class,
                () -> ModelCatalogConfiguration.load(cloud));
        assertTrue(duplicateError.getMessage().contains("Duplicate"));
        assertTrue(cloudError.getMessage().contains("Invalid local Ollama model id"));
    }

    @Test
    void rejectsDuplicateYamlKeysAndUnknownFields() throws Exception {
        Path duplicateKey = writeConfig("""
                version: 1
                version: 1
                models:
                  - id: qwen3:8b
                    displayName: Qwen3 8B
                    estimatedDownloadGb: 6
                    recommendedVramGb: 8
                    recommendedRamGb: 12
                """);
        Path unknownField = writeConfig("""
                version: 1
                models:
                  - id: qwen3:8b
                    displayName: Qwen3 8B
                    estimatedDownloadGb: 6
                    recommendedVramGb: 8
                    recommendedRamGb: 12
                    recommendedVramGB: 8
                """);

        assertTrue(assertThrows(IOException.class,
                () -> ModelCatalogConfiguration.load(duplicateKey)).getMessage().contains("Invalid YAML"));
        assertTrue(assertThrows(IOException.class,
                () -> ModelCatalogConfiguration.load(unknownField)).getMessage().contains("Unknown"));
    }

    @Test
    void copiesBundledYamlOnlyWhenNoUserConfigurationExists() throws Exception {
        Path config = temporaryDirectory.resolve("config").resolve("models.yml");

        ModelCatalogConfiguration.ensureUserConfiguration(config);
        assertEquals(ModelCatalogConfiguration.loadDefault(), ModelCatalogConfiguration.load(config));

        Files.writeString(config, "user-edit");
        ModelCatalogConfiguration.ensureUserConfiguration(config);
        assertEquals("user-edit", Files.readString(config));
    }

    private Path writeConfig(String contents) throws IOException {
        Path path = temporaryDirectory.resolve("models-" + System.nanoTime() + ".yml");
        Files.writeString(path, contents);
        return path;
    }
}
