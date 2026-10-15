package com.localai.desktop;

import com.localai.contracts.ModelOption;
import com.localai.contracts.ModelDownloadProgress;
import com.localai.contracts.ModelSelectionResponse;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Alert;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ProgressBar;
import javafx.geometry.Pos;
import javafx.scene.layout.VBox;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class DesktopController extends Application {

    private final VBox transcript = new VBox(16);
    private final ScrollPane transcriptScrollPane = new ScrollPane(transcript);
    private final TextField input = new TextField();
    private final Label status = new Label("Connecting to local API...");
    private final ComboBox<com.localai.contracts.ConversationRecord> conversationPicker = new ComboBox<>();
    private final ComboBox<ModelOption> modelPicker = new ComboBox<>();
    private final Label modelAdvice = new Label("Detecting model fit...");
    private final ProgressBar modelDownloadProgressBar = new ProgressBar(0);
    private final Label modelDownloadProgressLabel = new Label();
    private final HBox downloadProgressRow = new HBox(10, modelDownloadProgressLabel, modelDownloadProgressBar);
    private final Button sendButton = new Button("Send");
    private final Button newConversationButton = new Button("New conversation");
    private final Button deleteConversationButton = new Button("Delete chat");
    private final Button cancelButton = new Button("Cancel");
    private final Button memoryButton = new Button("Conversation memory");
    private final Button modelsButton = new Button("Model library");
    private final Button webContextButton = new Button("Add web context");
    private final ExecutorService apiExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "local-api-client");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService controlExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "local-api-control");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService progressExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "model-download-progress");
        thread.setDaemon(true);
        return thread;
    });
    private LocalApiClient apiClient;
    private java.util.UUID conversationId;
    private volatile java.util.UUID activeTurnId;
    private boolean updatingConversationPicker;
    private boolean updatingModelPicker;
    private boolean modelOptionsReady;
    private boolean modelSwitching;
    private String activeModelId;
    private LocalModelHardware modelHardware;
    private java.util.Set<String> installedModelIds = java.util.Set.of();
    private final Map<String, ProgressBar> libraryProgressBars = new java.util.HashMap<>();
    private final Map<String, Label> libraryProgressLabels = new java.util.HashMap<>();
    private volatile ScheduledFuture<?> progressPolling;
    private volatile String progressModelId;
    private ScheduledFuture<?> chatWarmupTask;
    private volatile long chatWarmupGeneration;

    @Override
    public void start(Stage stage) {
        transcript.setId("transcript");
        transcript.getStyleClass().add("chat-transcript");
        transcript.setPadding(new Insets(18));
        transcriptScrollPane.setFitToWidth(true);
        transcriptScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        transcriptScrollPane.setId("chat-scroll");
        transcriptScrollPane.getStyleClass().add("chat-scroll");
        transcript.heightProperty().addListener((observable, oldHeight, newHeight) ->
                transcriptScrollPane.setVvalue(1));
        input.setId("message-input");
        input.setPromptText("Ask a question...");
        input.setPrefHeight(42);
        input.setOnAction(event -> submitMessage());
        input.setTooltip(new javafx.scene.control.Tooltip("Type a message and press Enter to send."));
        status.getStyleClass().add("status-label");
        status.setWrapText(true);
        status.setMaxWidth(260);
        modelDownloadProgressLabel.getStyleClass().add("download-progress-label");
        modelDownloadProgressBar.setMaxWidth(Double.MAX_VALUE);
        modelDownloadProgressBar.setPrefWidth(260);
        downloadProgressRow.getStyleClass().add("download-progress-row");
        downloadProgressRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        downloadProgressRow.setVisible(false);
        downloadProgressRow.setManaged(false);
        HBox.setHgrow(modelDownloadProgressBar, Priority.ALWAYS);
        sendButton.getStyleClass().add("primary-button");
        cancelButton.getStyleClass().add("cancel-button");
        newConversationButton.setText("New chat");
        newConversationButton.setTooltip(new javafx.scene.control.Tooltip("Start a new conversation"));
        newConversationButton.getStyleClass().add("secondary-button");
        deleteConversationButton.setTooltip(new javafx.scene.control.Tooltip("Delete the selected conversation"));
        deleteConversationButton.getStyleClass().add("secondary-button");
        memoryButton.setText("Memory");
        memoryButton.setTooltip(new javafx.scene.control.Tooltip("Manage conversation memory"));
        memoryButton.getStyleClass().add("secondary-button");
        modelsButton.setText("Models");
        modelsButton.setTooltip(new javafx.scene.control.Tooltip("Install, select, or uninstall local models"));
        modelsButton.getStyleClass().add("secondary-button");
        webContextButton.setText("Web context");
        webContextButton.setTooltip(new javafx.scene.control.Tooltip("Add information from public web pages"));
        webContextButton.getStyleClass().add("secondary-button");
        conversationPicker.setPromptText("Select a conversation");
        conversationPicker.setMaxWidth(Double.MAX_VALUE);
        conversationPicker.setConverter(new StringConverter<>() {
            @Override
            public String toString(com.localai.contracts.ConversationRecord conversation) {
                return conversation == null ? "" : conversation.title();
            }

            @Override
            public com.localai.contracts.ConversationRecord fromString(String value) {
                return null;
            }
        });
        conversationPicker.setOnAction(event -> {
            if (!updatingConversationPicker) {
                var selected = conversationPicker.getValue();
                if (selected != null && !selected.id().equals(conversationId)) {
                    selectConversation(selected);
                }
            }
        });
        modelPicker.setPromptText("No model selected — choose one to start chatting");
        modelPicker.setPrefWidth(250);
        modelAdvice.setWrapText(true);
        modelAdvice.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(modelAdvice, javafx.scene.layout.Priority.ALWAYS);
        modelPicker.setConverter(new StringConverter<>() {
            @Override
            public String toString(ModelOption option) {
                if (option == null) {
                    return "";
                }
                if (modelHardware == null) {
                    return option.modelId();
                }
                String advisory = modelHardware.advisory(option);
                if ("Fits detected hardware".equals(advisory) && isRecommendedModel(option)) {
                    advisory = "Recommended";
                }
                return option.displayName() + " [" + advisory + "]";
            }

            @Override
            public ModelOption fromString(String value) {
                return null;
            }
        });
        modelPicker.setOnAction(event -> {
            if (!updatingModelPicker && modelPicker.getValue() != null) {
                requestModelSelection(modelPicker.getValue());
            }
        });

        String apiUrl = System.getProperty("local.ai.api.url", "http://127.0.0.1:8080");
        String token = System.getProperty("local.ai.api.token",
                System.getenv().getOrDefault("LOCAL_API_TOKEN", "dev-local-token"));
        apiClient = new LocalApiClient(URI.create(apiUrl), token);
        sendButton.setOnAction(event -> {
            if (conversationId == null) {
                initializeConversation();
            } else {
                submitMessage();
            }
        });
        cancelButton.setDisable(true);
        cancelButton.setOnAction(event -> cancelActiveTurn());
        newConversationButton.setOnAction(event -> createConversation());
        deleteConversationButton.setOnAction(event -> confirmDeleteConversation());
        memoryButton.setOnAction(event -> showConversationMemory());
        modelsButton.setOnAction(event -> showModelCatalog());
        webContextButton.setOnAction(event -> showWebContextDialog());
        sendButton.setDisable(true);
        modelsButton.setDisable(true);
        webContextButton.setDisable(true);
        input.setDisable(true);
        conversationPicker.setDisable(true);
        newConversationButton.setDisable(true);

        Label brandMark = new Label("AI");
        brandMark.getStyleClass().add("brand-mark");
        Label title = new Label("Local AI Assistant");
        title.getStyleClass().add("app-title");
        Label subtitle = new Label("Your private workspace for local AI");
        subtitle.getStyleClass().add("app-subtitle");
        VBox titleBlock = new VBox(3, title, subtitle);
        HBox brandBlock = new HBox(12, brandMark, titleBlock);
        brandBlock.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox topBar = new HBox(12, brandBlock, status);
        topBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(brandBlock, Priority.ALWAYS);

        Label conversationLabel = new Label("CONVERSATION");
        conversationLabel.getStyleClass().add("section-label");
        HBox conversationRow = new HBox(
                10, conversationLabel, conversationPicker, newConversationButton, deleteConversationButton);
        conversationRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(conversationPicker, Priority.ALWAYS);
        Label modelLabel = new Label("Model");
        modelLabel.getStyleClass().add("section-label");
        HBox modelRow = new HBox(10, modelLabel, modelPicker, modelAdvice);
        modelRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(modelAdvice, Priority.ALWAYS);
        HBox toolsRow = new HBox(8, memoryButton, modelsButton, webContextButton);
        toolsRow.getStyleClass().add("toolbar");
        VBox header = new VBox(15, topBar, conversationRow, modelRow, toolsRow);
        header.getStyleClass().add("app-header");

        HBox composer = new HBox(10, input, sendButton, cancelButton);
        composer.getStyleClass().add("composer");
        HBox.setHgrow(input, Priority.ALWAYS);
        VBox layout = new VBox(14, header, downloadProgressRow, transcriptScrollPane, composer);
        layout.getStyleClass().add("app-root");
        layout.setPadding(new Insets(20));
        VBox.setVgrow(transcriptScrollPane, Priority.ALWAYS);

        Scene scene = new Scene(layout, 1020, 760);
        var stylesheet = getClass().getResource("desktop.css");
        if (stylesheet == null) {
            throw new IllegalStateException("Desktop stylesheet is missing.");
        }
        scene.getStylesheets().add(stylesheet.toExternalForm());
        stage.setScene(scene);
        stage.setMinWidth(860);
        stage.setMinHeight(640);
        stage.setTitle("Local AI Assistant");
        stage.show();
        initializeConversation();
    }

    @Override
    public void stop() {
        stopModelProgressPolling();
        stopChatWarmupIndicator();
        apiExecutor.shutdownNow();
        controlExecutor.shutdownNow();
        progressExecutor.shutdownNow();
    }

    public static void main(String[] args) {
        launch(args);
    }

    private void initializeConversation() {
        sendButton.setDisable(true);
        sendButton.setText("Retry connection");
        input.setDisable(true);
        conversationPicker.setDisable(true);
        newConversationButton.setDisable(true);
        memoryButton.setDisable(true);
        status.setText("Connecting to local API...");
        apiExecutor.execute(() -> {
            try {
                List<com.localai.contracts.ConversationRecord> conversations = apiClient.listConversations();
                var conversationsToShow = conversations.isEmpty()
                        ? List.of(apiClient.createConversation("Empty conversation"))
                        : conversations;
                var selected = conversationsToShow.get(conversationsToShow.size() - 1);
                var messages = apiClient.listMessages(selected.id());
                var turns = apiClient.listTurns(selected.id());
                RecoveryDetails recoveryDetails = loadRecoveryDetails(turns);
                List<ModelOption> options = apiClient.listModelOptions();
                ModelSelectionResponse selection = apiClient.getModelSelection();
                List<com.localai.contracts.ModelCatalogEntry> installedModels = apiClient.listAvailableModels();
                LocalModelHardware hardware = LocalModelHardware.detect(applicationDataDirectory());
                Platform.runLater(() -> {
                    setConversations(conversationsToShow, selected);
                    showConversation(selected, messages, turns, recoveryDetails);
                    configureModelPicker(options, selection, installedModels, hardware);
                    status.setText(activeModelId == null ? "Connected — no model selected" : "Connected");
                    sendButton.setText("Send");
                    setConversationControlsDisabled(false);
                });
            } catch (RuntimeException e) {
                Platform.runLater(() -> {
                    status.setText("Local API unavailable: " + e.getMessage());
                    sendButton.setDisable(false);
                });
            }
        });
    }

    private void selectConversation(com.localai.contracts.ConversationRecord conversation) {
        setConversationControlsDisabled(true);
        status.setText("Loading conversation...");
        apiExecutor.execute(() -> {
            try {
                var messages = apiClient.listMessages(conversation.id());
                var turns = apiClient.listTurns(conversation.id());
                RecoveryDetails recoveryDetails = loadRecoveryDetails(turns);
                Platform.runLater(() -> {
                    showConversation(conversation, messages, turns, recoveryDetails);
                    status.setText("Connected");
                    setConversationControlsDisabled(false);
                });
            } catch (RuntimeException e) {
                Platform.runLater(() -> {
                    status.setText("Could not load conversation: " + e.getMessage());
                    setConversationControlsDisabled(false);
                    restorePickerSelection();
                });
            }
        });
    }

    private void createConversation() {
        setConversationControlsDisabled(true);
        status.setText("Creating conversation...");
        apiExecutor.execute(() -> {
            try {
                var conversation = apiClient.createConversation("Empty conversation");
                var conversations = apiClient.listConversations();
                Platform.runLater(() -> {
                    setConversations(conversations, conversation);
                    showConversation(conversation, List.of(), List.of(), RecoveryDetails.empty());
                    status.setText("Connected");
                    setConversationControlsDisabled(false);
                });
            } catch (RuntimeException e) {
                Platform.runLater(() -> {
                    status.setText("Could not create conversation: " + e.getMessage());
                    setConversationControlsDisabled(false);
                    restorePickerSelection();
                });
            }
        });
    }

    private void setConversations(
            List<com.localai.contracts.ConversationRecord> conversations,
            com.localai.contracts.ConversationRecord selected) {
        conversationPicker.setPromptText(conversations.isEmpty()
                ? "No conversations — start a new chat"
                : "Select a conversation");
        updatingConversationPicker = true;
        try {
            conversationPicker.getItems().setAll(conversations);
            conversationPicker.setValue(selected);
        } finally {
            updatingConversationPicker = false;
        }
    }

    private void confirmDeleteConversation() {
        var selected = conversationPicker.getValue();
        if (selected == null) {
            return;
        }
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("Delete conversation");
        confirmation.setHeaderText("Delete \"" + selected.title() + "\"?");
        confirmation.setContentText("This permanently deletes the conversation and its messages.");
        confirmation.getButtonTypes().setAll(ButtonType.CANCEL, ButtonType.OK);
        if (confirmation.showAndWait().filter(ButtonType.OK::equals).isEmpty()) {
            return;
        }
        setConversationControlsDisabled(true);
        status.setText("Deleting conversation...");
        apiExecutor.execute(() -> {
            try {
                apiClient.deleteConversation(selected.id());
                List<com.localai.contracts.ConversationRecord> conversations = apiClient.listConversations();
                if (conversations.isEmpty()) {
                    Platform.runLater(() -> {
                        setConversations(List.of(), null);
                        conversationId = null;
                        transcript.getChildren().clear();
                        status.setText("Conversation deleted — start a new chat when ready");
                        setConversationControlsDisabled(false);
                    });
                    return;
                }
                var nextConversation = conversations.getLast();
                var messages = apiClient.listMessages(nextConversation.id());
                var turns = apiClient.listTurns(nextConversation.id());
                RecoveryDetails recoveryDetails = loadRecoveryDetails(turns);
                Platform.runLater(() -> {
                    setConversations(conversations, nextConversation);
                    showConversation(nextConversation, messages, turns, recoveryDetails);
                    status.setText("Connected");
                    setConversationControlsDisabled(false);
                });
            } catch (RuntimeException e) {
                Platform.runLater(() -> {
                    status.setText("Could not delete conversation: " + e.getMessage());
                    setConversationControlsDisabled(false);
                    restorePickerSelection();
                });
            }
        });
    }

    private void showConversation(
            com.localai.contracts.ConversationRecord conversation,
            List<com.localai.contracts.MessageRecord> messages,
            List<com.localai.contracts.TurnStatusResponse> turns,
            RecoveryDetails recoveryDetails) {
        conversationId = conversation.id();
        transcript.getChildren().clear();
        java.util.Map<java.util.UUID, com.localai.contracts.TurnStatusResponse> turnsByUserMessage =
                turns.stream().collect(java.util.stream.Collectors.toMap(
                        com.localai.contracts.TurnStatusResponse::userMessageId,
                        turn -> turn));
        for (com.localai.contracts.MessageRecord message : messages) {
            appendChatMessage(message.role(), message.content());
            if (message.role() == com.localai.contracts.MessageRole.USER) {
                var turn = turnsByUserMessage.get(message.id());
                if (turn != null && turn.status() != com.localai.contracts.TurnStatus.COMPLETED) {
                    String partial = recoveryDetails.checkpoints().get(turn.turnId());
                    if (partial != null) {
                        appendChatMessage(com.localai.contracts.MessageRole.ASSISTANT, partial);
                        appendChatNotice("Saved partial response · " + turn.status());
                    } else {
                        appendChatNotice("Turn " + turn.status());
                    }
                    if (turn.status() == com.localai.contracts.TurnStatus.NEEDS_REVIEW) {
                        List<com.localai.contracts.ToolInvocationStatusResponse> invocations =
                                recoveryDetails.toolInvocations().getOrDefault(turn.turnId(), List.of());
                        for (var invocation : invocations) {
                            appendChatNotice(
                                    "Review required: " + invocation.operation()
                                            + "; side-effecting=" + invocation.sideEffecting()
                                            + "; permissions=" + invocation.requiredPermissions()
                                            + "; invocation status=" + invocation.status());
                        }
                    }
                }
            }
        }
    }

    private Label appendChatMessage(com.localai.contracts.MessageRole role, String text) {
        Label message = new Label(text);
        message.setWrapText(true);
        message.setMaxWidth(720);
        message.setAccessibleText((role == com.localai.contracts.MessageRole.USER ? "User" : "Assistant")
                + " message: " + text);
        message.getStyleClass().add(role == com.localai.contracts.MessageRole.USER
                ? "chat-user-message" : "chat-assistant-message");
        HBox row = new HBox(message);
        row.setAlignment(role == com.localai.contracts.MessageRole.USER
                ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        row.setMaxWidth(Double.MAX_VALUE);
        row.getStyleClass().add("chat-message-row");
        transcript.getChildren().add(row);
        return message;
    }

    private void appendChatNotice(String text) {
        Label notice = new Label(text);
        notice.setWrapText(true);
        notice.setMaxWidth(Double.MAX_VALUE);
        notice.getStyleClass().add("chat-notice");
        transcript.getChildren().add(notice);
    }

    private void appendChatError(String text) {
        Label error = new Label(text);
        error.setWrapText(true);
        error.setMaxWidth(Double.MAX_VALUE);
        error.getStyleClass().add("chat-error-message");
        transcript.getChildren().add(error);
    }

    private RecoveryDetails loadRecoveryDetails(
            List<com.localai.contracts.TurnStatusResponse> turns) {
        java.util.Map<java.util.UUID, String> checkpoints = new java.util.HashMap<>();
        java.util.Map<java.util.UUID, List<com.localai.contracts.ToolInvocationStatusResponse>> toolInvocations =
                new java.util.HashMap<>();
        for (var turn : turns) {
            if (turn.status() == com.localai.contracts.TurnStatus.INTERRUPTED
                    || turn.status() == com.localai.contracts.TurnStatus.CANCELLED
                    || turn.status() == com.localai.contracts.TurnStatus.FAILED
                    || turn.status() == com.localai.contracts.TurnStatus.NEEDS_REVIEW) {
                apiClient.getTurnCheckpoint(turn.turnId())
                        .ifPresent(checkpoint -> checkpoints.put(
                                turn.turnId(), checkpoint.partialAssistantText()));
            }
            if (turn.status() == com.localai.contracts.TurnStatus.NEEDS_REVIEW) {
                toolInvocations.put(turn.turnId(), apiClient.listToolInvocations(turn.turnId()));
            }
        }
        return new RecoveryDetails(Map.copyOf(checkpoints), Map.copyOf(toolInvocations));
    }

    private void restorePickerSelection() {
        updatingConversationPicker = true;
        try {
            conversationPicker.getItems().stream()
                    .filter(conversation -> conversation.id().equals(conversationId))
                    .findFirst()
                    .ifPresent(conversationPicker::setValue);
        } finally {
            updatingConversationPicker = false;
        }
    }

    private void setConversationControlsDisabled(boolean disabled) {
        conversationPicker.setDisable(disabled);
        modelPicker.setDisable(disabled || !modelOptionsReady || modelSwitching);
        newConversationButton.setDisable(disabled);
        memoryButton.setDisable(disabled || conversationId == null);
        modelsButton.setDisable(disabled);
        webContextButton.setDisable(disabled || conversationId == null);
        deleteConversationButton.setDisable(disabled || conversationId == null);
        input.setDisable(disabled || activeModelId == null || conversationId == null);
        sendButton.setDisable(disabled || activeModelId == null || conversationId == null);
    }

    private void refreshConversationTitles(java.util.UUID selectedConversationId) {
        apiExecutor.execute(() -> {
            try {
                List<com.localai.contracts.ConversationRecord> conversations = apiClient.listConversations();
                Platform.runLater(() -> {
                    if (!selectedConversationId.equals(conversationId)) {
                        return;
                    }
                    conversations.stream()
                            .filter(conversation -> conversation.id().equals(selectedConversationId))
                            .findFirst()
                            .ifPresent(selected -> setConversations(conversations, selected));
                });
            } catch (RuntimeException e) {
                Platform.runLater(() -> status.setText("Could not refresh conversation title: " + e.getMessage()));
            }
        });
    }

    private void configureModelPicker(
            List<ModelOption> options,
            ModelSelectionResponse selection,
            List<com.localai.contracts.ModelCatalogEntry> installedModels,
            LocalModelHardware hardware) {
        modelHardware = hardware;
        installedModelIds = installedModels.stream()
                .map(com.localai.contracts.ModelCatalogEntry::modelId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        updatingModelPicker = true;
        try {
            modelPicker.getItems().setAll(options);
            activeModelId = selection.modelId();
            modelPicker.setPromptText(activeModelId == null
                    ? "No model selected — choose one to start chatting"
                    : "Select a local model");
            modelPicker.setValue(options.stream()
                    .filter(option -> option.modelId().equals(selection.modelId()))
                    .findFirst()
                    .orElse(null));
            modelOptionsReady = !options.isEmpty();
            updateModelAdvice(modelPicker.getValue());
        } finally {
            updatingModelPicker = false;
        }
    }

    private void requestModelSelection(ModelOption option) {
        if (option.modelId().equals(activeModelId) || modelSwitching) {
            updateModelAdvice(option);
            return;
        }
        boolean installed = isModelInstalled(option.modelId());
        if (!installed && !modelHardware.hasEnoughDisk(option)) {
            Alert warning = new Alert(Alert.AlertType.WARNING,
                    modelHardware.diskAdvisory(option) + ". Free disk space before downloading this model.",
                    ButtonType.OK);
            applyDialogStyles(warning);
            warning.showAndWait();
            restoreModelPickerSelection();
            return;
        }
        String advisory = modelHardware.advisory(option);
        if (!"Fits detected hardware".equals(advisory)) {
            Alert warning = new Alert(Alert.AlertType.CONFIRMATION);
            applyDialogStyles(warning);
            warning.setTitle("Model hardware advisory");
            warning.setHeaderText(option.modelId() + " may not run well on this computer");
            warning.setContentText(advisory + "\n\nContinue and select this model?");
            warning.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
            if (warning.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                restoreModelPickerSelection();
                return;
            }
        }

        modelSwitching = true;
        setConversationControlsDisabled(true);
        status.setText("Preparing " + option.displayName()
                + ". Its first download may take a while; installed models are reused.");
        if (!installed) {
            startModelProgressPolling(option.modelId());
        }
        controlExecutor.execute(() -> {
            try {
                ModelSelectionResponse selection = apiClient.selectModel(option.modelId());
                List<com.localai.contracts.ModelCatalogEntry> models = apiClient.listAvailableModels();
                Platform.runLater(() -> {
                    stopModelProgressPolling();
                    hideModelDownloadProgress();
                    activeModelId = selection.modelId();
                    installedModelIds = models.stream()
                            .map(com.localai.contracts.ModelCatalogEntry::modelId)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet());
                    modelSwitching = false;
                    updatingModelPicker = true;
                    modelPicker.setValue(option);
                    updatingModelPicker = false;
                    updateModelAdvice(option);
                    status.setText("Connected — using " + activeModelId);
                    setConversationControlsDisabled(false);
                });
            } catch (RuntimeException e) {
                Platform.runLater(() -> {
                    stopModelProgressPolling();
                    hideModelDownloadProgress();
                    modelSwitching = false;
                    restoreModelPickerSelection();
                    status.setText("Could not select " + option.displayName() + ": " + e.getMessage());
                    setConversationControlsDisabled(false);
                });
            }
        });
    }

    private boolean isModelInstalled(String modelId) {
        return installedModelIds.contains(modelId) || installedModelIds.contains(modelId + ":latest");
    }

    private void restoreModelPickerSelection() {
        updatingModelPicker = true;
        try {
            modelPicker.setValue(modelPicker.getItems().stream()
                    .filter(option -> option.modelId().equals(activeModelId))
                    .findFirst()
                    .orElse(null));
        } finally {
            updatingModelPicker = false;
        }
        updateModelAdvice(modelPicker.getValue());
    }

    private void updateModelAdvice(ModelOption option) {
        if (activeModelId == null) {
            modelAdvice.setText("No model selected · install or choose a model to start chatting");
            return;
        }
        if (option == null || modelHardware == null) {
            modelAdvice.setText("Hardware requirements unavailable");
            return;
        }
        boolean installedOnDisk = isModelInstalled(option.modelId());
        String installed = installedOnDisk ? "Installed" : "Not downloaded";
        String disk = installedOnDisk
                ? "Already downloaded"
                : modelHardware.diskAdvisory(option);
        String fit = modelHardware.advisory(option);
        if ("Fits detected hardware".equals(fit) && isRecommendedModel(option)) {
            fit = "Recommended";
        }
        modelAdvice.setText(fit + " · " + disk + " · " + installed);
    }

    private boolean isRecommendedModel(ModelOption option) {
        return !modelPicker.getItems().isEmpty()
        && option.modelId().equals(modelHardware.defaultModelId(modelPicker.getItems()));
    }

    private static java.nio.file.Path applicationDataDirectory() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return java.nio.file.Path.of(localAppData, "LocalAIAssistant");
        }
        return java.nio.file.Path.of(System.getProperty("user.home"), ".localai", "LocalAIAssistant");
    }

    private void applyDialogStyles(Dialog<?> dialog) {
        var stylesheet = getClass().getResource("desktop.css");
        if (stylesheet == null) {
            throw new IllegalStateException("Desktop stylesheet is missing.");
        }
        dialog.getDialogPane().getStylesheets().add(stylesheet.toExternalForm());
        dialog.getDialogPane().getStyleClass().add("app-dialog");
    }

    private void showModelCatalog() {
        Dialog<Void> dialog = new Dialog<>();
        applyDialogStyles(dialog);
        dialog.setTitle("Local Model Library");
        dialog.setHeaderText("Models");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().setPrefSize(900, 580);

        Label locationLabel = new Label("MODEL FILES ARE STORED HERE");
        locationLabel.getStyleClass().add("library-section-label");
        TextField modelDirectoryField = new TextField(
                applicationDataDirectory().resolve("models").resolve("ollama").toAbsolutePath().toString());
        modelDirectoryField.setEditable(false);
        modelDirectoryField.setFocusTraversable(false);
        modelDirectoryField.getStyleClass().add("model-path-field");
        Label guidance = new Label(
                "Download to keep a model on this computer. Use selects it for chat. "
                        + "You can uninstall models that are not currently in use.");
        guidance.getStyleClass().add("library-guidance");
        guidance.setWrapText(true);

        VBox entries = new VBox(7);
        entries.getStyleClass().add("model-library-list");
        entries.setPadding(new Insets(4));
        ScrollPane listScroll = new ScrollPane(entries);
        listScroll.setFitToWidth(true);
        listScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        listScroll.getStyleClass().add("model-library-scroll");
        VBox content = new VBox(7, locationLabel, modelDirectoryField, guidance, listScroll);
        content.getStyleClass().add("model-library-content");
        VBox.setVgrow(listScroll, Priority.ALWAYS);
        dialog.getDialogPane().setContent(content);
        dialog.show();
        refreshModelLibrary(dialog, entries);
    }

    private void refreshModelLibrary(Dialog<Void> dialog, VBox entries) {
        libraryProgressBars.clear();
        libraryProgressLabels.clear();
        Label loading = new Label("Loading configured models and checking local downloads...");
        loading.getStyleClass().add("library-empty-state");
        entries.getChildren().setAll(loading);
        apiExecutor.execute(() -> {
            try {
                List<ModelOption> options = apiClient.listModelOptions();
                List<com.localai.contracts.ModelCatalogEntry> installedModels = apiClient.listAvailableModels();
                Platform.runLater(() -> {
                    if (!dialog.isShowing()) {
                        return;
                    }
                    entries.getChildren().clear();
                    java.util.Set<String> installedIds = installedModels.stream()
                            .map(com.localai.contracts.ModelCatalogEntry::modelId)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet());
                    Label configuredHeading = new Label("AVAILABLE FROM CONFIGURATION");
                    configuredHeading.getStyleClass().add("library-section-label");
                    entries.getChildren().add(configuredHeading);
                    if (options.isEmpty()) {
                        Label empty = new Label("No models are configured. Check your models.yml file.");
                        empty.getStyleClass().add("library-empty-state");
                        entries.getChildren().add(empty);
                    }
                    for (ModelOption option : options) {
                        boolean installed = installedIds.contains(option.modelId())
                                || installedIds.contains(option.modelId() + ":latest");
                        boolean active = option.modelId().equals(activeModelId);
                        Label details = new Label(option.displayName() + "  ·  " + option.modelId()
                                + "\n" + (installed ? "Downloaded" : "Not downloaded")
                                + "  ·  " + option.estimatedDownloadBytes() / (1024L * 1024 * 1024)
                                + " GiB");
                        details.getStyleClass().add("model-library-details");
                        String hardwareAdvisory = modelHardware == null
                                ? "Hardware advisory unavailable"
                                : modelHardware.advisory(option);
                        details.setText(details.getText() + "  ·  " + hardwareAdvisory);
                        details.setWrapText(true);
                        Label progressLabel = new Label();
                        progressLabel.getStyleClass().add("download-progress-label");
                        ProgressBar progressBar = new ProgressBar(-1);
                        progressBar.setMaxWidth(Double.MAX_VALUE);
                        progressBar.setVisible(false);
                        progressBar.setManaged(false);
                        libraryProgressBars.put(option.modelId(), progressBar);
                        libraryProgressLabels.put(option.modelId(), progressLabel);
                        VBox modelDetails = new VBox(3, details, progressLabel, progressBar);
                        Button install = new Button("Download");
                        install.getStyleClass().add("primary-button");
                        install.setVisible(!installed);
                        install.setManaged(!installed);
                        install.setDisable(modelSwitching || !modelOptionsReady);
                        install.setOnAction(event -> installModelFromLibrary(dialog, entries, option));
                        Button use = new Button(active ? "In use" : "Use");
                        use.getStyleClass().add(active ? "secondary-button" : "primary-button");
                        use.setVisible(installed);
                        use.setManaged(installed);
                        use.setDisable(active || modelSwitching || !modelOptionsReady);
                        use.setOnAction(event -> {
                            dialog.close();
                            updatingModelPicker = true;
                            modelPicker.setValue(option);
                            updatingModelPicker = false;
                            requestModelSelection(option);
                        });
                        Button uninstall = new Button("Uninstall");
                        uninstall.getStyleClass().add("cancel-button");
                        uninstall.setVisible(installed);
                        uninstall.setManaged(installed);
                        uninstall.setDisable(modelSwitching);
                        uninstall.setOnAction(event -> uninstallModelFromLibrary(dialog, entries, option));
                        HBox actions = new HBox(4, install, use, uninstall);
                        actions.getStyleClass().add("model-library-actions");
                        HBox row = new HBox(8, modelDetails, actions);
                        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                        HBox.setHgrow(modelDetails, Priority.ALWAYS);
                        row.getStyleClass().add("model-library-row");
                        entries.getChildren().add(row);
                    }

                    Label installedHeading = new Label("INSTALLED ON THIS COMPUTER");
                    installedHeading.getStyleClass().add("library-section-label");
                    entries.getChildren().add(installedHeading);
                    List<com.localai.contracts.ModelCatalogEntry> additionalInstalledModels = installedModels.stream()
                            .filter(model -> options.stream().noneMatch(option ->
                                    option.modelId().equals(model.modelId())
                                            || (option.modelId() + ":latest").equals(model.modelId())))
                            .toList();
                    if (additionalInstalledModels.isEmpty()) {
                        Label empty = new Label("No models downloaded yet.");
                        empty.getStyleClass().add("library-empty-state");
                        entries.getChildren().add(empty);
                        return;
                    }
                    for (var model : additionalInstalledModels) {
                        Label details = new Label(model.modelId()
                                + "\nSize: " + model.sizeBytes() + " bytes"
                                + "  ·  Family: " + (model.family() == null ? "unknown" : model.family())
                                + "  ·  Parameters: "
                                + (model.parameterSize() == null ? "unknown" : model.parameterSize())
                                + "  ·  Quantization: "
                                + (model.quantizationLevel() == null ? "unknown" : model.quantizationLevel()));
                        details.getStyleClass().add("model-library-details");
                        details.setWrapText(true);
                        HBox installedRow = new HBox(details);
                        installedRow.getStyleClass().add("installed-model-row");
                        entries.getChildren().add(installedRow);
                    }
                });
            } catch (RuntimeException e) {
                Platform.runLater(() -> {
                    if (dialog.isShowing()) {
                        Label error = new Label("Could not load the model list.\n" + e.getMessage());
                        error.getStyleClass().add("library-error");
                        entries.getChildren().setAll(error);
                    }
                });
            }
        });
    }

    private void installModelFromLibrary(Dialog<Void> dialog, VBox entries, ModelOption option) {
        if (modelHardware != null && !modelHardware.hasEnoughDisk(option)) {
            Alert warning = new Alert(Alert.AlertType.WARNING,
                    modelHardware.diskAdvisory(option) + ". Free disk space before downloading this model.",
                    ButtonType.OK);
            applyDialogStyles(warning);
            warning.showAndWait();
            return;
        }
        modelSwitching = true;
        progressModelId = option.modelId();
        status.setText("Downloading " + option.displayName() + ". This may take a while...");
        ProgressBar rowProgress = libraryProgressBars.get(option.modelId());
        Label rowProgressLabel = libraryProgressLabels.get(option.modelId());
        if (rowProgress != null) {
            rowProgress.setVisible(true);
            rowProgress.setManaged(true);
            rowProgress.setProgress(-1);
        }
        if (rowProgressLabel != null) {
            rowProgressLabel.setText("Connecting to local model runtime...");
        }
        startModelProgressPolling(option.modelId());
        controlExecutor.execute(() -> {
            try {
                apiClient.installModel(option.modelId());
                Platform.runLater(() -> {
                    stopModelProgressPolling();
                    modelSwitching = false;
                    progressModelId = null;
                    hideModelDownloadProgress();
                    status.setText(option.displayName() + " downloaded. Choose Use when you want to switch.");
                    java.util.Set<String> updatedIds = new java.util.HashSet<>(installedModelIds);
                    updatedIds.add(option.modelId());
                    installedModelIds = java.util.Set.copyOf(updatedIds);
                    refreshModelLibrary(dialog, entries);
                });
            } catch (RuntimeException e) {
                Platform.runLater(() -> {
                    stopModelProgressPolling();
                    modelSwitching = false;
                    progressModelId = null;
                    hideModelDownloadProgress();
                    status.setText("Could not download " + option.displayName() + ": " + e.getMessage());
                    refreshModelLibrary(dialog, entries);
                });
            }
        });
    }

    private void uninstallModelFromLibrary(Dialog<Void> dialog, VBox entries, ModelOption option) {
        boolean uninstallingSelectedModel = option.modelId().equals(activeModelId);
        String confirmationText = "Remove " + option.displayName()
                + " from this computer? You can download it again later."
                + (uninstallingSelectedModel
                        ? "\n\nChat will be paused until you choose another model."
                        : "");
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                confirmationText, ButtonType.OK, ButtonType.CANCEL);
        applyDialogStyles(confirmation);
        confirmation.setTitle("Uninstall local model");
        confirmation.setHeaderText("This frees the model's disk space.");
        if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        modelSwitching = true;
        status.setText("Uninstalling " + option.displayName() + "...");
        setConversationControlsDisabled(true);
        refreshModelLibrary(dialog, entries);
        controlExecutor.execute(() -> {
            try {
                apiClient.uninstallModel(option.modelId());
                ModelSelectionResponse selection = apiClient.getModelSelection();
                Platform.runLater(() -> {
                    modelSwitching = false;
                    activeModelId = selection.modelId();
                    java.util.Set<String> updatedIds = new java.util.HashSet<>(installedModelIds);
                    updatedIds.remove(option.modelId());
                    updatedIds.remove(option.modelId() + ":latest");
                    installedModelIds = java.util.Set.copyOf(updatedIds);
                    updatingModelPicker = true;
                    modelPicker.setValue(modelPicker.getItems().stream()
                            .filter(candidate -> candidate.modelId().equals(activeModelId))
                            .findFirst()
                            .orElse(null));
                    modelPicker.setPromptText(activeModelId == null
                            ? "No model selected — choose one to start chatting"
                            : "Select a local model");
                    updatingModelPicker = false;
                    updateModelAdvice(modelPicker.getValue());
                    status.setText(activeModelId == null
                            ? "Model uninstalled — no model selected"
                            : option.displayName() + " uninstalled.");
                    setConversationControlsDisabled(false);
                    refreshModelLibrary(dialog, entries);
                });
            } catch (RuntimeException e) {
                Platform.runLater(() -> {
                    modelSwitching = false;
                    status.setText("Could not uninstall " + option.displayName() + ": " + e.getMessage());
                    setConversationControlsDisabled(false);
                    refreshModelLibrary(dialog, entries);
                });
            }
        });
    }

    private void startModelProgressPolling(String modelId) {
        stopModelProgressPolling();
        progressModelId = modelId;
        downloadProgressRow.setVisible(true);
        downloadProgressRow.setManaged(true);
        modelDownloadProgressLabel.setText("Preparing " + modelId + "...");
        modelDownloadProgressBar.setProgress(-1);
        progressPolling = progressExecutor.scheduleWithFixedDelay(() -> {
            try {
                ModelDownloadProgress progress = apiClient.getModelDownloadProgress(modelId);
                Platform.runLater(() -> updateModelDownloadProgress(progress));
            } catch (RuntimeException e) {
                Platform.runLater(() -> {
                    if (modelId.equals(progressModelId)) {
                        modelDownloadProgressLabel.setText("Checking download progress...");
                    }
                });
            }
        }, 250, 500, TimeUnit.MILLISECONDS);
    }

    private void updateModelDownloadProgress(ModelDownloadProgress progress) {
        if (!progress.modelId().equals(progressModelId)) {
            return;
        }
        String text = progress.message();
        if (progress.hasByteProgress()) {
            double fraction = Math.min(1.0, (double) progress.completedBytes() / progress.totalBytes());
            String size = formatBytes(progress.completedBytes()) + " / " + formatBytes(progress.totalBytes());
            modelDownloadProgressBar.setProgress(fraction);
            modelDownloadProgressLabel.setText(progress.message() + " · " + size);
            ProgressBar rowBar = libraryProgressBars.get(progress.modelId());
            Label rowLabel = libraryProgressLabels.get(progress.modelId());
            if (rowBar != null && rowLabel != null) {
                rowBar.setProgress(fraction);
                rowLabel.setText(progress.message() + " · " + size);
            }
        } else {
            modelDownloadProgressBar.setProgress(-1);
            modelDownloadProgressLabel.setText(text.isBlank() ? "Preparing download..." : text);
            ProgressBar rowBar = libraryProgressBars.get(progress.modelId());
            Label rowLabel = libraryProgressLabels.get(progress.modelId());
            if (rowBar != null && rowLabel != null) {
                rowBar.setProgress(-1);
                rowLabel.setText(text.isBlank() ? "Preparing download..." : text);
            }
        }
    }

    private void stopModelProgressPolling() {
        ScheduledFuture<?> polling = progressPolling;
        progressPolling = null;
        if (polling != null) {
            polling.cancel(false);
        }
    }

    private void hideModelDownloadProgress() {
        downloadProgressRow.setVisible(false);
        downloadProgressRow.setManaged(false);
        modelDownloadProgressBar.setProgress(0);
        progressModelId = null;
    }

    private String formatBytes(long bytes) {
        double gibibytes = bytes / (1024d * 1024 * 1024);
        return String.format(java.util.Locale.ROOT, "%.1f GiB", gibibytes);
    }

    private void showWebContextDialog() {
        java.util.UUID selectedConversationId = conversationId;
        if (selectedConversationId == null) {
            return;
        }
        Dialog<Void> dialog = new Dialog<>();
        applyDialogStyles(dialog);
        dialog.setTitle("Add web context");
        dialog.setHeaderText("Paste up to five public page URLs, one per line");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        TextArea urls = new TextArea();
        urls.setPromptText("https://example.com/article");
        urls.setPrefRowCount(5);
        Label guidance = new Label(
                "Pages will be fetched and stored in this conversation. The local model receives "
                        + "the extracted text as untrusted reference material. Google search API keys "
                        + "are not needed; paste individual result-page URLs.");
        guidance.setWrapText(true);
        Button add = new Button("Fetch and add");
        Label result = new Label();
        HBox actions = new HBox(8, add, result);
        VBox content = new VBox(10, guidance, urls, actions);
        content.setPadding(new Insets(8));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefSize(600, 380);
        add.setOnAction(event -> {
            List<String> requestedUrls = urls.getText().lines()
                    .map(String::strip)
                    .filter(line -> !line.isBlank())
                    .toList();
            if (requestedUrls.isEmpty() || requestedUrls.size() > 5) {
                result.setText("Enter between one and five URLs.");
                return;
            }
            add.setDisable(true);
            result.setText("Fetching pages...");
            controlExecutor.execute(() -> {
                try {
                    List<com.localai.contracts.MessageRecord> sources =
                            apiClient.addWebContext(selectedConversationId, requestedUrls);
                    Platform.runLater(() -> {
                        for (var source : sources) {
                            appendChatMessage(com.localai.contracts.MessageRole.ASSISTANT,
                                    "Web source\n" + source.content()).getStyleClass().add("chat-source-message");
                        }
                        result.setText("Added " + sources.size() + " source(s) to conversation history.");
                        add.setDisable(false);
                    });
                } catch (RuntimeException e) {
                    Platform.runLater(() -> {
                        result.setText("Could not add pages: " + e.getMessage());
                        add.setDisable(false);
                    });
                }
            });
        });
        dialog.show();
    }

    private void submitMessage() {
        String message = input.getText();
        if (message == null || message.isBlank() || sendButton.isDisabled()) {
            return;
        }
        sendButton.setDisable(true);
        input.setDisable(true);
        conversationPicker.setDisable(true);
        newConversationButton.setDisable(true);
        status.setText("Generating...");
        input.clear();
        String clientRequestId = java.util.UUID.randomUUID().toString();
        java.util.UUID submittedConversationId = conversationId;
        appendChatMessage(com.localai.contracts.MessageRole.USER, message);
        Label assistantMessage = appendChatMessage(
                com.localai.contracts.MessageRole.ASSISTANT, "Waiting for the local model...");
        assistantMessage.getStyleClass().add("chat-assistant-pending");
        StringBuilder assistantStreamedText = new StringBuilder();
        startChatWarmupIndicator();
        cancelButton.setDisable(true);
        apiExecutor.execute(() -> {
            try {
                apiClient.streamTurn(submittedConversationId, message, clientRequestId, event -> {
                    switch (event.type()) {
                        case "turn.started" -> Platform.runLater(() -> {
                            activeTurnId = event.turnId();
                            cancelButton.setDisable(false);
                        });
                        case "token.delta" -> {
                            String token = event.payload().getOrDefault("text", "");
                            assistantStreamedText.append(token);
                            Platform.runLater(() -> {
                                stopChatWarmupIndicator();
                                if (assistantMessage.getStyleClass().contains("chat-assistant-pending")) {
                                    assistantMessage.setText("");
                                    assistantMessage.getStyleClass().remove("chat-assistant-pending");
                                    status.setText("Generating response...");
                                }
                                assistantMessage.setText(assistantMessage.getText() + token);
                            });
                        }
                        case "turn.completed" -> Platform.runLater(() -> {
                            stopChatWarmupIndicator();
                            if (assistantMessage.getStyleClass().contains("chat-assistant-pending")) {
                                assistantMessage.setText("The model returned an empty response.");
                                assistantMessage.getStyleClass().remove("chat-assistant-pending");
                            }
                            status.setText("Connected");
                            setConversationControlsDisabled(false);
                            cancelButton.setDisable(true);
                            activeTurnId = null;
                            refreshConversationTitles(submittedConversationId);
                        });
                        case "turn.failed" -> Platform.runLater(() -> {
                            stopChatWarmupIndicator();
                            String failure = event.payload().getOrDefault("message", "Generation failed");
                            if (assistantStreamedText.isEmpty()) {
                                assistantMessage.setText("Could not generate a response: " + failure);
                                assistantMessage.getStyleClass().remove("chat-assistant-pending");
                                assistantMessage.getStyleClass().add("chat-error-message");
                            } else {
                                appendChatError("Generation stopped: " + failure);
                            }
                            status.setText("Generation failed");
                            setConversationControlsDisabled(false);
                            cancelButton.setDisable(true);
                            activeTurnId = null;
                            refreshConversationTitles(submittedConversationId);
                        });
                        case "turn.cancelled" -> Platform.runLater(() -> {
                            stopChatWarmupIndicator();
                            assistantMessage.setText(assistantStreamedText.isEmpty()
                                    ? "Response cancelled." : assistantStreamedText + "\n\nResponse cancelled.");
                            assistantMessage.getStyleClass().remove("chat-assistant-pending");
                            status.setText("Generation cancelled");
                            setConversationControlsDisabled(false);
                            cancelButton.setDisable(true);
                            activeTurnId = null;
                            refreshConversationTitles(submittedConversationId);
                        });
                        case "turn.interrupted", "turn.needs_review" -> {
                            java.util.Optional<com.localai.contracts.TurnCheckpoint> checkpoint;
                            List<com.localai.contracts.ToolInvocationStatusResponse> invocations = List.of();
                            try {
                                checkpoint = apiClient.getTurnCheckpoint(event.turnId());
                                if ("turn.needs_review".equals(event.type())) {
                                    invocations = apiClient.listToolInvocations(event.turnId());
                                }
                            } catch (LocalApiClient.LocalApiException e) {
                                Platform.runLater(() -> {
                                    status.setText("Turn recovery status could not be loaded");
                                    setConversationControlsDisabled(false);
                                    cancelButton.setDisable(true);
                                    activeTurnId = null;
                                });
                                break;
                            }
                            List<com.localai.contracts.ToolInvocationStatusResponse> recoveredInvocations = invocations;
                            Platform.runLater(() -> {
                                checkpoint.ifPresent(saved -> {
                                    String streamed = assistantStreamedText.toString();
                                    String partial = saved.partialAssistantText();
                                    if (partial.startsWith(streamed)) {
                                        String remaining = partial.substring(streamed.length());
                                        if (!remaining.isEmpty()) {
                                            if (assistantMessage.getStyleClass().contains("chat-assistant-pending")) {
                                                assistantMessage.setText("");
                                                assistantMessage.getStyleClass().remove("chat-assistant-pending");
                                            }
                                            assistantMessage.setText(assistantMessage.getText() + remaining);
                                        }
                                    }
                                    appendChatNotice("Partial response saved; generation was interrupted.");
                                });
                                if ("turn.needs_review".equals(event.type())) {
                                    for (var invocation : recoveredInvocations) {
                                        appendChatNotice(
                                                "Review required: " + invocation.operation()
                                                        + "; side-effecting=" + invocation.sideEffecting()
                                                        + "; permissions=" + invocation.requiredPermissions()
                                                        + "; status=" + invocation.status());
                                    }
                                }
                                if (checkpoint.isEmpty()) {
                                    assistantMessage.setText("turn.needs_review".equals(event.type())
                                            ? "Response paused for required review."
                                            : "Generation interrupted because the backend restarted.");
                                    assistantMessage.getStyleClass().remove("chat-assistant-pending");
                                }
                                stopChatWarmupIndicator();
                                if ("turn.needs_review".equals(event.type())) {
                                    status.setText("Review required: a side effect may have occurred; inspect before retrying");
                                } else {
                                    status.setText("Turn interrupted because the backend restarted");
                                }
                                setConversationControlsDisabled(false);
                                cancelButton.setDisable(true);
                                activeTurnId = null;
                            });
                        }
                        default -> {
                        }
                    }
                });
            } catch (RuntimeException e) {
                Platform.runLater(() -> {
                    stopChatWarmupIndicator();
                    String failure = e.getMessage() == null ? "The local API request failed." : e.getMessage();
                    if (assistantStreamedText.isEmpty()) {
                        assistantMessage.setText("Could not connect to the model: " + failure);
                        assistantMessage.getStyleClass().remove("chat-assistant-pending");
                        assistantMessage.getStyleClass().add("chat-error-message");
                    } else {
                        appendChatError("Connection stopped: " + failure);
                    }
                    status.setText("Message failed");
                    setConversationControlsDisabled(false);
                    cancelButton.setDisable(true);
                    activeTurnId = null;
                });
            }

        });
    }

    private void startChatWarmupIndicator() {
        stopChatWarmupIndicator();
        long generation = ++chatWarmupGeneration;
        String modelName = modelPicker.getValue() == null
                ? (activeModelId == null ? "The local model" : activeModelId)
                : modelPicker.getValue().displayName();
        long startedAt = System.nanoTime();
        chatWarmupTask = progressExecutor.scheduleAtFixedRate(() -> {
            long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAt);
            Platform.runLater(() -> {
                if (generation == chatWarmupGeneration) {
                    status.setText(modelName + " is loading or thinking (" + elapsedSeconds
                            + "s). The first response can take longer.");
                }
            });
        }, 5, 5, TimeUnit.SECONDS);
    }

    private void stopChatWarmupIndicator() {
        ScheduledFuture<?> task = chatWarmupTask;
        chatWarmupTask = null;
        chatWarmupGeneration++;
        if (task != null) {
            task.cancel(false);
        }
    }

    private void cancelActiveTurn() {
        java.util.UUID turnId = activeTurnId;
        if (turnId == null) {
            return;
        }
        cancelButton.setDisable(true);
        status.setText("Cancelling...");
        controlExecutor.execute(() -> {
            try {
                apiClient.cancelTurn(turnId);
            } catch (RuntimeException e) {
                Platform.runLater(() -> {
                    status.setText("Cancellation request failed: " + e.getMessage());
                    cancelButton.setDisable(false);
                });
            }
        });
    }

    private void showConversationMemory() {
        java.util.UUID selectedConversationId = conversationId;
        if (selectedConversationId == null) {
            return;
        }
        Dialog<Void> dialog = new Dialog<>();
        applyDialogStyles(dialog);
        dialog.setTitle("Conversation memory");
        dialog.setHeaderText("Sourced summaries for this conversation");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        VBox summaries = new VBox(8);
        summaries.setPadding(new Insets(8));
        Button createSummary = new Button("Summarize older history");
        createSummary.setOnAction(event -> createSummaryFromOlderHistory(selectedConversationId, summaries));
        VBox content = new VBox(10, createSummary, new ScrollPane(summaries));
        content.setPrefSize(560, 360);
        dialog.getDialogPane().setContent(content);
        dialog.show();
        loadContextSummaries(selectedConversationId, summaries);
    }

    private void createSummaryFromOlderHistory(java.util.UUID selectedConversationId, VBox summaries) {
        apiExecutor.execute(() -> {
            List<com.localai.contracts.MessageRecord> messages;
            try {
                messages = apiClient.listMessages(selectedConversationId);
            } catch (RuntimeException e) {
                Platform.runLater(() -> status.setText("Could not load summary sources: " + e.getMessage()));
                return;
            }
            int sourceCount = Math.min(200, Math.max(0, messages.size() - 2));
            if (sourceCount < 2) {
                Platform.runLater(() -> status.setText("At least four messages are needed to summarize older history"));
                return;
            }
            List<com.localai.contracts.MessageRecord> sources = messages.subList(0, sourceCount);
            Platform.runLater(() -> {
                Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
                applyDialogStyles(confirmation);
                confirmation.setTitle("Create conversation summary");
                confirmation.setHeaderText("Summarize " + sources.size() + " older messages?");
                confirmation.setContentText(
                        "The selected messages will be sent to the configured local model. "
                                + "The resulting summary will retain these source message IDs and can be deleted.");
                if (confirmation.showAndWait().filter(ButtonType.OK::equals).isEmpty()) {
                    return;
                }
                status.setText("Creating sourced summary...");
                controlExecutor.execute(() -> {
                    try {
                        apiClient.createContextSummary(
                                selectedConversationId,
                                sources.stream().map(com.localai.contracts.MessageRecord::id).toList());
                        Platform.runLater(() -> {
                            status.setText("Sourced summary created");
                            loadContextSummaries(selectedConversationId, summaries);
                        });
                    } catch (RuntimeException e) {
                        Platform.runLater(() -> status.setText("Summary creation failed: " + e.getMessage()));
                    }
                });
            });
        });
    }

    private void loadContextSummaries(java.util.UUID selectedConversationId, VBox summaries) {
        summaries.getChildren().setAll(new Label("Loading summaries..."));
        apiExecutor.execute(() -> {
            try {
                List<com.localai.contracts.ContextSummary> savedSummaries =
                        apiClient.listContextSummaries(selectedConversationId);
                Platform.runLater(() -> {
                    summaries.getChildren().clear();
                    if (savedSummaries.isEmpty()) {
                        summaries.getChildren().add(new Label("No saved summaries for this conversation."));
                        return;
                    }
                    for (com.localai.contracts.ContextSummary summary : savedSummaries) {
                        Label details = new Label(summary.content()
                                + "\n\nSources: " + summary.sourceMessageIds()
                                + "\nModel: " + summary.modelId()
                                + "\nCreated: " + summary.createdAt());
                        details.setWrapText(true);
                        Button delete = new Button("Delete summary");
                        delete.setOnAction(event -> deleteContextSummary(
                                selectedConversationId, summary.id(), summaries));
                        summaries.getChildren().add(new TitledPane(
                                summary.createdAt().toString(), new VBox(8, details, delete)));
                    }
                });
            } catch (RuntimeException e) {
                Platform.runLater(() -> summaries.getChildren().setAll(
                        new Label("Could not load summaries: " + e.getMessage())));
            }
        });
    }

    private void deleteContextSummary(
            java.util.UUID selectedConversationId,
            java.util.UUID summaryId,
            VBox summaries) {
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        applyDialogStyles(confirmation);
        confirmation.setTitle("Delete conversation summary");
        confirmation.setHeaderText("Delete this derived summary?");
        confirmation.setContentText("Original conversation messages will not be changed.");
        if (confirmation.showAndWait().filter(ButtonType.OK::equals).isEmpty()) {
            return;
        }
        controlExecutor.execute(() -> {
            try {
                apiClient.deleteContextSummary(selectedConversationId, summaryId);
                Platform.runLater(() -> {
                    status.setText("Summary deleted");
                    loadContextSummaries(selectedConversationId, summaries);
                });
            } catch (RuntimeException e) {
                Platform.runLater(() -> status.setText("Summary deletion failed: " + e.getMessage()));
            }
        });
    }

    private record RecoveryDetails(
            java.util.Map<java.util.UUID, String> checkpoints,
            java.util.Map<java.util.UUID, List<com.localai.contracts.ToolInvocationStatusResponse>> toolInvocations) {
        private static RecoveryDetails empty() {
            return new RecoveryDetails(java.util.Map.of(), java.util.Map.of());
        }
    }
}
