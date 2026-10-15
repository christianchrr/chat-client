package com.localai.contracts;

public record ConversationCreateRequest(String title) {
    public ConversationCreateRequest {
        if (title == null || title.isBlank()) {
            title = "New conversation";
        }
    }
}
