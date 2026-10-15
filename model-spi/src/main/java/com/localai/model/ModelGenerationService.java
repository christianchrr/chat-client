package com.localai.model;

import java.util.function.Consumer;

public interface ModelGenerationService {
    boolean isReady(String modelId);

    String generateText(String modelId, String prompt);

    default void streamText(String modelId, String prompt, Consumer<String> tokenConsumer) {
        if (tokenConsumer == null) {
            throw new IllegalArgumentException("tokenConsumer must not be null");
        }
        tokenConsumer.accept(generateText(modelId, prompt));
    }
}
