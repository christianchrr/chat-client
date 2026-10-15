package com.localai.apihost;

import com.localai.contracts.MessageRole;
import com.localai.contracts.MessageRecord;
import com.localai.model.ModelGenerationService;
import com.localai.model.ModelSelectionService;
import com.localai.storage.ConversationStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class DropboxController {
    public record DropboxContextRequest(List<String> names) {
    }

    public record DropboxEditRequest(String content) {
    }

    private final DropboxService dropbox;
    private final ConversationStore conversationStore;

    private final ModelGenerationService modelGenerationService;

    public DropboxController(
            DropboxService dropbox,
            ConversationStore conversationStore,
            ModelGenerationService modelGenerationService) {
        this.dropbox = dropbox;
        this.conversationStore = conversationStore;
        this.modelGenerationService = modelGenerationService;
    }

    @GetMapping("/dropbox/files")
    public List<DropboxService.DropboxFile> list() {
        return dropbox.list();
    }

    @PutMapping(value = "/dropbox/files/{name}", consumes = "application/octet-stream")
    public ResponseEntity<?> add(@PathVariable("name") String name, @RequestBody byte[] content) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(dropbox.add(name, content));
        } catch (DropboxService.DropboxException e) {
            return error(e);
        }
    }

    @DeleteMapping("/dropbox/files/{name}")
    public ResponseEntity<?> remove(@PathVariable("name") String name) {
        try {
            dropbox.remove(name);
            return ResponseEntity.noContent().build();
        } catch (DropboxService.DropboxException e) {
            return error(e);
        }
    }

    @PostMapping("/dropbox/files/{name}/edited")
    public ResponseEntity<?> saveEdited(
            @PathVariable("name") String name, @RequestBody(required = false) DropboxEditRequest request) {
        try {
            String newName = dropbox.saveEditedCopy(name, request == null ? null : request.content());
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("name", newName));
        } catch (DropboxService.DropboxException e) {
            return error(e);
        }
    }

    @PostMapping("/conversations/{conversationId}/dropbox-context")
    public ResponseEntity<?> addContext(
            @PathVariable("conversationId") UUID conversationId,
            @RequestBody(required = false) DropboxContextRequest request) {
        if (conversationStore.findConversation(conversationId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (request == null || request.names() == null || request.names().isEmpty()
                || request.names().size() > 5) {
            return ResponseEntity.badRequest().body(Map.of("error", "dropbox_file_names_required"));
        }
        if (request.names().stream().anyMatch(DropboxService::isImage) && !activeModelSeesImages()) {
            return ResponseEntity.badRequest().body(Map.of("error", "image_requires_vision_model"));
        }
        try {
            List<String> sources = request.names().stream().map(dropbox::contextFor).toList();
            List<MessageRecord> saved = sources.stream()
                    .map(source -> conversationStore.appendMessage(conversationId, MessageRole.USER, source))
                    .toList();
            return ResponseEntity.status(HttpStatus.CREATED).body(saved);
        } catch (DropboxService.DropboxException e) {
            return error(e);
        }
    }

    private boolean activeModelSeesImages() {
        return modelGenerationService instanceof ModelSelectionService selection
                && selection.activeModelId() != null
                && modelGenerationService.supportsVision(selection.activeModelId());
    }

    private static ResponseEntity<Map<String, String>> error(DropboxService.DropboxException e) {
        HttpStatus status = "dropbox_file_not_found".equals(e.errorCode()) ? HttpStatus.NOT_FOUND
                : "dropbox_unavailable".equals(e.errorCode()) ? HttpStatus.INTERNAL_SERVER_ERROR
                : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Map.of("error", e.errorCode()));
    }
}
