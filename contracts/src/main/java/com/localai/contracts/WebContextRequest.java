package com.localai.contracts;

import java.util.List;

public record WebContextRequest(List<String> urls) {
    public WebContextRequest {
        if (urls == null || urls.isEmpty() || urls.size() > 5
                || urls.stream().anyMatch(url -> url == null || url.isBlank())) {
            throw new IllegalArgumentException("Provide between one and five non-blank URLs");
        }
        urls = List.copyOf(urls);
    }
}
