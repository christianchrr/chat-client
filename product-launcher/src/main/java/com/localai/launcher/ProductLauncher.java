package com.localai.launcher;

import com.localai.apihost.ApiHostApplication;
import com.localai.contracts.ModelOption;
import com.localai.desktop.DesktopController;
import com.localai.desktop.LocalModelHardware;
import com.localai.model.ollama.ModelCatalogConfiguration;
import javafx.application.Application;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

public final class ProductLauncher {
    private ProductLauncher() {
    }

    public static void main(String[] args) {
        Path dataDirectory = applicationDataDirectory();
        try {
            Files.createDirectories(dataDirectory.resolve("data"));
            Files.createDirectories(dataDirectory.resolve("logs"));
            Files.createDirectories(dataDirectory.resolve("config"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not create the local application data directory", e);
        }

        Path modelCatalogFile = Path.of(System.getProperty(
                "local.ai.model.catalog.path",
                dataDirectory.resolve("config").resolve("models.yml").toString()));
        List<ModelOption> modelOptions;
        try {
            ModelCatalogConfiguration.ensureUserConfiguration(modelCatalogFile);
            modelOptions = ModelCatalogConfiguration.load(modelCatalogFile);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not load the model allow-list from " + modelCatalogFile, e);
        }
        Path selectionFile = dataDirectory.resolve("config").resolve("selected-model.txt");
        LocalModelHardware hardware = LocalModelHardware.detect(dataDirectory);
        markFirstRunWithoutModel(selectionFile);
        boolean noModelSelected = hasExplicitNoSelection(selectionFile);
        String modelId = noModelSelected
                ? null
                : selectedModel(selectionFile, modelOptions)
                        .orElseGet(() -> hardware.defaultModelId(modelOptions));
        String apiDefaultModelId = modelId == null ? hardware.defaultModelId(modelOptions) : modelId;
        OllamaFirstRunSetup modelSetup = new OllamaFirstRunSetup(dataDirectory, modelId);
        try (FirstRunSetupWindow setupWindow = FirstRunSetupWindow.show(dataDirectory)) {
            boolean prepared = false;
            while (!prepared) {
                try {
                    modelSetup.prepare(setupWindow::update);
                    prepared = true;
                } catch (Exception e) {
                    modelSetup.stop();
                    if (!setupWindow.askRetry(e.getMessage() == null
                            ? e.getClass().getSimpleName() : e.getMessage())) {
                        return;
                    }
                }
            }
        }

        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomToken());
        System.setProperty("server.address", "127.0.0.1");
        System.setProperty("server.port", "8080");
        System.setProperty("local.api.token", token);
        System.setProperty("local.ai.api.token", token);
        System.setProperty("local.ai.db.path", dataDirectory.resolve("data").resolve("assistant.db").toString());
        System.setProperty("local.ai.dropbox.path", dataDirectory.resolve("dropbox").toString());
        System.setProperty("local.ai.model.id", apiDefaultModelId);
        System.setProperty("local.ai.model.catalog.path", modelCatalogFile.toString());
        System.setProperty("local.ai.model.selection.path", selectionFile.toString());
        System.setProperty("local.ai.ollama.base-url", modelSetup.baseUrl());
        System.setProperty("logging.file.name", dataDirectory.resolve("logs").resolve("application.log").toString());
        System.setProperty("logging.logback.rollingpolicy.max-file-size", "5MB");
        System.setProperty("logging.logback.rollingpolicy.max-history", "3");
        ConfigurableApplicationContext api = null;
        try {
            api = new SpringApplicationBuilder(ApiHostApplication.class)
                    .web(WebApplicationType.SERVLET)
                    .properties(
                            "server.address=127.0.0.1",
                            "server.port=8080",
                            "local.api.token=" + token,
                            "local.ai.api.token=" + token,
                            "local.ai.db.path=" + dataDirectory.resolve("data").resolve("assistant.db"),
                            "local.ai.dropbox.path=" + dataDirectory.resolve("dropbox"),
                            "local.ai.model.id=" + apiDefaultModelId,
                            "local.ai.model.catalog.path=" + modelCatalogFile,
                            "local.ai.model.selection.path=" + selectionFile,
                            "local.ai.ollama.base-url=" + modelSetup.baseUrl(),
                            "logging.file.name="
                                    + dataDirectory.resolve("logs").resolve("application.log"),
                            "logging.logback.rollingpolicy.max-file-size=5MB",
                            "logging.logback.rollingpolicy.max-history=3")
                    .run();
            if (modelId != null) {
                api.getBeanProvider(com.localai.model.ollama.OllamaGenerationService.class)
                        .ifAvailable(service -> service.preloadModel(modelId));
            }
            Application.launch(DesktopController.class, args);
        } finally {
            if (api != null) {
                api.close();
            }
            modelSetup.stop();
        }
    }

    static java.util.Optional<String> selectedModel(Path selectionFile, List<ModelOption> options) {
        if (!Files.isRegularFile(selectionFile)) {
            return java.util.Optional.empty();
        }
        try {
            String modelId = Files.readString(selectionFile, java.nio.charset.StandardCharsets.UTF_8).trim();
            return options.stream()
                    .map(ModelOption::modelId)
                    .filter(modelId::equals)
                    .findFirst();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not read the saved local model selection.", e);
        }
    }

    /** On first launch nothing is downloaded but the runtime; the user chooses a model inside the app. */
    static void markFirstRunWithoutModel(Path selectionFile) {
        if (Files.exists(selectionFile)) {
            return;
        }
        try {
            Files.writeString(selectionFile,
                    com.localai.contracts.ModelSelectionResponse.NO_SELECTION_MARKER + System.lineSeparator(),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not save the initial model selection.", e);
        }
    }

    static boolean hasExplicitNoSelection(Path selectionFile) {
        if (!Files.isRegularFile(selectionFile)) {
            return false;
        }
        try {
            return com.localai.contracts.ModelSelectionResponse.NO_SELECTION_MARKER.equals(
                    Files.readString(selectionFile, java.nio.charset.StandardCharsets.UTF_8).trim());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not read the saved local model selection.", e);
        }
    }

    private static byte[] randomToken() {
        byte[] token = new byte[32];
        new SecureRandom().nextBytes(token);
        return token;
    }

    private static Path applicationDataDirectory() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData, "LocalAIAssistant");
        }
        return Path.of(System.getProperty("user.home"), ".localai", "LocalAIAssistant");
    }
}

