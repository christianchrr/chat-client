package com.localai.apihost;

import com.localai.model.ollama.OllamaGenerationService;
import com.localai.model.ollama.ModelCatalogConfiguration;
import com.localai.storage.ConversationStore;
import com.localai.storage.sqlite.SqliteConversationStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.nio.file.Path;

@Configuration
public class LocalApiConfiguration {

    @Bean
    public TurnEventBroker turnEventBroker() {
        return new TurnEventBroker();
    }

    @Bean
    public ConversationStore conversationStore(
            @Value("${local.ai.db.path:${user.home}/.localai/local-ai-assistant/assistant.db}") String path
    ) {
        return new SqliteConversationStore(Path.of(path));
    }

    @Bean
    public OllamaGenerationService ollamaGenerationService(
            @Value("${local.ai.ollama.base-url:http://127.0.0.1:11434}") String baseUrl,
            @Value("${local.ai.model.id:qwen3:30b}") String defaultModelId,
            @Value("${local.ai.model.catalog.path:}") String catalogPath,
            @Value("${local.ai.model.selection.path:${user.home}/.localai/selected-model.txt}") String selectionPath,
            @Value("${local.ai.model.num-ctx:0}") int configuredNumCtx) {
        java.util.List<com.localai.contracts.ModelOption> modelOptions;
        try {
            modelOptions = catalogPath.isBlank()
                    ? ModelCatalogConfiguration.loadDefault()
                    : ModelCatalogConfiguration.load(Path.of(catalogPath));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not load the local model allow-list configuration.", e);
        }
        OllamaGenerationService service = new OllamaGenerationService(
                URI.create(baseUrl),
                java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build(),
                defaultModelId,
                modelOptions,
                Path.of(selectionPath));
        service.setNumCtx(configuredNumCtx > 0 ? configuredNumCtx : contextForMemory(systemMemoryBytes()));
        return service;
    }

    /** Low-memory PCs get a smaller context so the model and its cache stay in memory; strong PCs get more. */
    static int contextForMemory(long ramBytes) {
        long gib = ramBytes / (1024L * 1024 * 1024);
        if (gib <= 12) {
            return 4096;
        }
        return gib >= 48 ? 16384 : 8192;
    }

    private static long systemMemoryBytes() {
        try {
            return ((com.sun.management.OperatingSystemMXBean)
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize();
        } catch (RuntimeException | LinkageError e) {
            return 16L * 1024 * 1024 * 1024;
        }
    }

    @Bean
    public DropboxService dropboxService(
            @Value("${local.ai.dropbox.path:${user.home}/.localai/dropbox}") String path) {
        return new DropboxService(Path.of(path));
    }

    @Bean
    public WebContextFetcher webContextFetcher() {
        return new WebContextFetcher();
    }
}
