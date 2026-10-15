package com.localai.model.ollama;

import com.localai.contracts.ModelOption;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class ModelCatalogConfiguration {
    private static final String RESOURCE_NAME = "/models.yml";
    private static final Pattern MODEL_ID_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}");
    private static final long BYTES_PER_GB = 1024L * 1024 * 1024;
    private static final long MAX_ESTIMATE_GB = 1024;
    private static final int MAX_CONFIG_BYTES = 64 * 1024;

    private ModelCatalogConfiguration() {
    }

    public static List<ModelOption> load(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return parse(input, path.toString());
        }
    }

    public static List<ModelOption> loadDefault() {
        try (InputStream input = ModelCatalogConfiguration.class.getResourceAsStream(RESOURCE_NAME)) {
            if (input == null) {
                throw new IllegalStateException("Bundled model catalog configuration is missing.");
            }
            return parse(input, "bundled models.yml");
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the bundled model catalog configuration.", e);
        }
    }

    public static Path ensureUserConfiguration(Path path) throws IOException {
        if (Files.exists(path)) {
            if (!Files.isRegularFile(path)) {
                throw new IOException("The model configuration path is not a file: " + path);
            }
            return path;
        }
        Path parent = path.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "models-", ".yml.tmp");
        try (InputStream input = ModelCatalogConfiguration.class.getResourceAsStream(RESOURCE_NAME)) {
            if (input == null) {
                throw new IOException("Bundled model catalog configuration is missing.");
            }
            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary, path);
            } catch (java.nio.file.FileAlreadyExistsException e) {
                if (!Files.isRegularFile(path)) {
                    throw new IOException("The model configuration path is not a file: " + path, e);
                }
            }
            return path;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static List<ModelOption> parse(InputStream input, String source) throws IOException {
        byte[] bytes = input.readNBytes(MAX_CONFIG_BYTES + 1);
        if (bytes.length > MAX_CONFIG_BYTES) {
            throw new IOException("Model configuration exceeds the 64 KB limit: " + source);
        }
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setMaxAliasesForCollections(0);
        loaderOptions.setCodePointLimit(MAX_CONFIG_BYTES);
        loaderOptions.setAllowDuplicateKeys(false);
        Object document;
        try {
            document = new Yaml(new SafeConstructor(loaderOptions))
                    .load(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        } catch (org.yaml.snakeyaml.error.YAMLException e) {
            throw new IOException("Invalid YAML in model configuration: " + source, e);
        }
        if (!(document instanceof Map<?, ?> root)) {
            throw new IOException("Model configuration must contain a YAML mapping: " + source);
        }
        requireOnlyKeys(root, Set.of("version", "models"), source);
        Object version = root.get("version");
        if (!(version instanceof Number number) || number.intValue() != 1 || number.doubleValue() != 1.0) {
            throw new IOException("Unsupported or missing model configuration version in " + source);
        }
        Object modelsNode = root.get("models");
        if (!(modelsNode instanceof List<?> models) || models.isEmpty() || models.size() > 100) {
            throw new IOException("Model configuration must define between 1 and 100 models: " + source);
        }

        List<ModelOption> options = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (Object modelNode : models) {
            if (!(modelNode instanceof Map<?, ?> model)) {
                throw new IOException("Each configured model must be a YAML mapping: " + source);
            }
            requireOnlyKeys(model, Set.of(
                    "id", "displayName", "estimatedDownloadGb", "recommendedVramGb", "recommendedRamGb"), source);
            String id = requiredText(model, "id", source);
            if (!MODEL_ID_PATTERN.matcher(id).matches() || id.endsWith(":cloud")) {
                throw new IOException("Invalid local Ollama model id in " + source + ": " + id);
            }
            if (!seenIds.add(id)) {
                throw new IOException("Duplicate model id in " + source + ": " + id);
            }
            String displayName = requiredText(model, "displayName", source);
            if (displayName.length() > 100) {
                throw new IOException("Model displayName exceeds 100 characters in " + source);
            }
            options.add(new ModelOption(
                    id,
                    displayName,
                    estimateBytes(model, "estimatedDownloadGb", source),
                    estimateBytes(model, "recommendedVramGb", source),
                    estimateBytes(model, "recommendedRamGb", source)));
        }
        return List.copyOf(options);
    }

    private static void requireOnlyKeys(Map<?, ?> mapping, Set<String> allowed, String source) throws IOException {
        for (Object key : mapping.keySet()) {
            if (!(key instanceof String name) || !allowed.contains(name)) {
                throw new IOException("Unknown model configuration field '" + key + "': " + source);
            }
        }
    }

    private static String requiredText(Map<?, ?> model, String key, String source) throws IOException {
        Object value = model.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IOException("Model configuration field '" + key + "' must be a non-empty string: " + source);
        }
        return text.trim();
    }

    private static long estimateBytes(Map<?, ?> model, String key, String source) throws IOException {
        Object value = model.get(key);
        if (!(value instanceof Number number)
                || number.doubleValue() <= 0
                || number.doubleValue() > MAX_ESTIMATE_GB
                || number.doubleValue() != Math.floor(number.doubleValue())) {
            throw new IOException("Model configuration field '" + key
                    + "' must be an integer from 1 to " + MAX_ESTIMATE_GB + ": " + source);
        }
        return number.longValue() * BYTES_PER_GB;
    }
}
