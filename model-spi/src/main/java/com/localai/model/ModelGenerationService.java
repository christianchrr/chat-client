package com.localai.model;

import java.util.List;
import java.util.function.Consumer;

public interface ModelGenerationService {
    boolean isReady(String modelId);

    String generateText(String modelId, String prompt);

    /** True when the model can read images; images are only sent to models that report this. */
    default boolean supportsVision(String modelId) {
        return false;
    }

    /** True when the model can reason before answering, so the thinking switch applies to it. */
    default boolean supportsThinking(String modelId) {
        return false;
    }

    /** Streams a reply; thinking is only applied when the model supports it. */
    default void streamText(String modelId, String prompt, List<String> imagesBase64, boolean thinking,
            Consumer<String> tokenConsumer) {
        streamText(modelId, prompt, imagesBase64, tokenConsumer);
    }

    /** Streams a reply to a prompt that also includes base64-encoded images. */
    default void streamText(String modelId, String prompt, List<String> imagesBase64,
            Consumer<String> tokenConsumer) {
        streamText(modelId, prompt, tokenConsumer);
    }

    default void streamText(String modelId, String prompt, Consumer<String> tokenConsumer) {
        if (tokenConsumer == null) {
            throw new IllegalArgumentException("tokenConsumer must not be null");
        }
        tokenConsumer.accept(generateText(modelId, prompt));
    }
}
