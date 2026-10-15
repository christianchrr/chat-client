package com.localai.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;

public final class OllamaFirstRunSetup {
    static final String RUNTIME_VERSION = "0.40.4";
    static final String RUNTIME_URL =
            "https://github.com/ollama/ollama/releases/download/v0.40.4/ollama-windows-amd64.zip";
    static final String RUNTIME_SHA256 =
            "7485a1172f8527db4342a63521b9342fc466185ff0855da378cc864ecf6354cf";
    static final long RUNTIME_ARCHIVE_BYTES = 1_469_916_992L;
    static final String RUNTIME_HOST = "127.0.0.1";
    private static final long MAX_RUNTIME_ARCHIVE_BYTES = 2_500_000_000L;
    private static final long MAX_RUNTIME_EXPANDED_BYTES = 8_000_000_000L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path dataDirectory;
    private final String modelId;
    private final HttpClient httpClient;
    private final String runtimeUrl;
    private final String runtimeSha256;
    private final RuntimeStarter runtimeStarter;
    private final boolean allowLoopbackTestSource;
    private int runtimePort;
    private Process process;

    public OllamaFirstRunSetup(Path dataDirectory, String modelId) {
        this(dataDirectory, modelId,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                        .followRedirects(HttpClient.Redirect.NORMAL).build(),
                RUNTIME_URL, RUNTIME_SHA256, OllamaFirstRunSetup::startProcess, 0, false);
    }

    OllamaFirstRunSetup(
            Path dataDirectory,
            String modelId,
            HttpClient httpClient,
            String runtimeUrl,
            String runtimeSha256,
            RuntimeStarter runtimeStarter,
            int runtimePort,
            boolean allowLoopbackTestSource) {
        if (dataDirectory == null || (modelId != null
                && !modelId.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}"))
                || httpClient == null || runtimeUrl == null || runtimeSha256 == null
                || !runtimeSha256.matches("[0-9a-f]{64}") || runtimeStarter == null
                || runtimePort < 0 || runtimePort > 65535) {
            throw new IllegalArgumentException("Invalid local model setup configuration");
        }
        this.dataDirectory = dataDirectory;
        this.modelId = modelId;
        this.httpClient = httpClient;
        this.runtimeUrl = runtimeUrl;
        this.runtimeSha256 = runtimeSha256;
        this.runtimeStarter = runtimeStarter;
        this.runtimePort = runtimePort;
        this.allowLoopbackTestSource = allowLoopbackTestSource;
    }

    public void prepare(SetupProgress progress) throws IOException {
        if (progress == null) {
            throw new IllegalArgumentException("Setup progress callback is required");
        }
        Files.createDirectories(dataDirectory);
        Path runtimeDirectory = dataDirectory.resolve("runtime").resolve("ollama-" + RUNTIME_VERSION);
        Path executable = runtimeDirectory.resolve("ollama.exe");
        if (!Files.isRegularFile(executable)) {
            installRuntime(runtimeDirectory, progress);
        }

        Path modelDirectory = dataDirectory.resolve("models").resolve("ollama");
        Path logFile = dataDirectory.resolve("logs").resolve("ollama.log");
        Files.createDirectories(modelDirectory);
        Files.createDirectories(logFile.getParent());
        if (runtimePort == 0) {
            runtimePort = findAvailablePort();
        }
        progress.update("Starting the private local model runtime...", 0, 0);
        process = runtimeStarter.start(
                executable, runtimeDirectory, modelDirectory, logFile, RUNTIME_HOST, runtimePort);
        if (!waitForReady(Duration.ofMinutes(2))) {
            stop();
            throw new IOException("The managed Ollama runtime did not become ready on its private port.");
        }
        if (modelId == null) {
            progress.update("Local model runtime ready. No model selected.", 1, 1);
        } else if (!modelIsInstalled()) {
            pullModel(progress);
        } else {
            progress.update("The local model is already installed.", 1, 1);
        }
    }

    public String baseUrl() {
        if (runtimePort < 1) {
            throw new IllegalStateException("The private model runtime has not been assigned a port yet.");
        }
        return "http://" + RUNTIME_HOST + ":" + runtimePort;
    }

    public void stop() {
        Process owned = process;
        process = null;
        if (owned != null && owned.isAlive()) {
            owned.destroy();
            try {
                if (!owned.waitFor(5, TimeUnit.SECONDS)) {
                    owned.destroyForcibly();
                    owned.waitFor(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                owned.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }

    private void installRuntime(Path runtimeDirectory, SetupProgress progress) throws IOException {
        URI source = URI.create(runtimeUrl);
        boolean approvedRelease = "https".equalsIgnoreCase(source.getScheme())
                && "github.com".equalsIgnoreCase(source.getHost());
        boolean testLoopback = allowLoopbackTestSource
                && "http".equalsIgnoreCase(source.getScheme())
                && ("127.0.0.1".equals(source.getHost()) || "localhost".equals(source.getHost()));
        if (!approvedRelease && !testLoopback) {
            throw new IOException("The Ollama runtime download URL is not an approved HTTPS release URL.");
        }
        Path runtimeRoot = runtimeDirectory.getParent();
        Files.createDirectories(runtimeRoot);
        Path archive = runtimeRoot.resolve("ollama-" + RUNTIME_VERSION + ".zip.part");
        Path staging = runtimeRoot.resolve("ollama-" + RUNTIME_VERSION + ".staging");
        deleteTree(staging);

        try {
            if (Files.exists(archive) && Files.size(archive) == RUNTIME_ARCHIVE_BYTES
                    && !sha256(archive).equals(runtimeSha256)) {
                Files.delete(archive);
            }
            if (Files.exists(archive) && Files.size(archive) > RUNTIME_ARCHIVE_BYTES) {
                Files.delete(archive);
            }
            if (!Files.exists(archive) || Files.size(archive) < RUNTIME_ARCHIVE_BYTES) {
                downloadArchive(source, archive, progress);
            }
            String actualDigest = sha256(archive);
            if (!MessageDigest.isEqual(
                    actualDigest.getBytes(StandardCharsets.US_ASCII),
                    runtimeSha256.getBytes(StandardCharsets.US_ASCII))) {
                throw new IOException("The downloaded Ollama runtime failed its SHA-256 integrity check.");
            }
            progress.update("Verifying and installing the local runtime...", 0, 0);
            Files.createDirectories(staging);
            extractArchive(archive, staging, progress);
            if (!Files.isRegularFile(staging.resolve("ollama.exe"))) {
                throw new IOException("The Ollama release archive did not contain ollama.exe.");
            }
            Files.createDirectories(runtimeDirectory.getParent());
            try {
                Files.move(staging, runtimeDirectory, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(staging, runtimeDirectory);
            }
            Files.deleteIfExists(archive);
        } finally {
            deleteTree(staging);
        }
    }

    private void downloadArchive(URI source, Path archive, SetupProgress progress) throws IOException {
        long offset = Files.exists(archive) ? Files.size(archive) : 0;
        if (offset >= MAX_RUNTIME_ARCHIVE_BYTES) {
            Files.delete(archive);
            offset = 0;
        }
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(source)
                .timeout(Duration.ofHours(2))
                .header("Accept", "application/octet-stream");
        if (offset > 0) {
            requestBuilder.header("Range", "bytes=" + offset + "-");
        }
        HttpRequest request = requestBuilder.GET().build();
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            boolean responseIsHttps = "https".equalsIgnoreCase(response.uri().getScheme());
            boolean responseIsLocalTest = allowLoopbackTestSource
                    && "http".equalsIgnoreCase(response.uri().getScheme())
                    && ("127.0.0.1".equals(response.uri().getHost())
                            || "localhost".equals(response.uri().getHost()));
            if ((!responseIsHttps && !responseIsLocalTest)
                    || response.statusCode() != java.net.HttpURLConnection.HTTP_OK
                            && response.statusCode() != java.net.HttpURLConnection.HTTP_PARTIAL) {
                response.body().close();
                throw new IOException("The official Ollama runtime download failed (HTTP "
                        + response.statusCode() + ").");
            }
            boolean append = offset > 0
                    && response.statusCode() == java.net.HttpURLConnection.HTTP_PARTIAL;
            if (append) {
                String contentRange = response.headers().firstValue("Content-Range").orElse("");
                if (!contentRange.startsWith("bytes " + offset + "-")) {
                    response.body().close();
                    throw new IOException("The Ollama runtime server returned an invalid resume range.");
                }
            }
            if (!append) {
                offset = 0;
            }
            long responseLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            long expectedTotal = responseLength < 0 ? -1 : offset + responseLength;
            if (expectedTotal > MAX_RUNTIME_ARCHIVE_BYTES) {
                response.body().close();
                throw new IOException("The Ollama runtime archive exceeded the allowed download size.");
            }
            var writeOptions = append
                    ? new java.nio.file.OpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.APPEND}
                    : new java.nio.file.OpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING};
            try (InputStream input = response.body();
                 OutputStream output = Files.newOutputStream(archive, writeOptions)) {
                byte[] buffer = new byte[1024 * 1024];
                long downloaded = offset;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    downloaded += read;
                    if (downloaded > MAX_RUNTIME_ARCHIVE_BYTES) {
                        throw new IOException("The Ollama runtime archive exceeded the allowed download size.");
                    }
                    output.write(buffer, 0, read);
                    progress.update("Downloading Ollama runtime: " + formatBytes(downloaded)
                            + (expectedTotal > 0 ? " of " + formatBytes(expectedTotal) : ""),
                            downloaded, expectedTotal);
                }
                if (expectedTotal >= 0 && downloaded != expectedTotal) {
                    throw new IOException("The Ollama runtime download ended before it was complete. "
                            + "The partial download is saved and will resume on the next launch.");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("The Ollama runtime download was interrupted. "
                    + "The partial download is saved and will resume on the next launch.", e);
        }
    }

    private void extractArchive(Path archive, Path staging, SetupProgress progress) throws IOException {
        long expanded = 0;
        try (InputStream file = Files.newInputStream(archive);
             ZipInputStream zip = new ZipInputStream(file)) {
            java.util.zip.ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                String entryName = entry.getName().replace('\\', '/');
                Path destination = staging.resolve(entryName).normalize();
                if (!destination.startsWith(staging) || entryName.startsWith("/")
                        || entryName.contains(":")) {
                    throw new IOException("The Ollama archive contains an unsafe file path.");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                    continue;
                }
                Files.createDirectories(destination.getParent());
                try (OutputStream output = Files.newOutputStream(
                        destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        expanded += read;
                        if (expanded > MAX_RUNTIME_EXPANDED_BYTES) {
                            throw new IOException("The expanded Ollama runtime exceeded the allowed size.");
                        }
                        output.write(buffer, 0, read);
                    }
                }
                progress.update("Installing Ollama runtime files...", expanded, -1);
            }
        }
    }

    private boolean waitForReady(Duration timeout) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/version"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
        while (System.nanoTime() < deadline) {
            if (process == null || !process.isAlive()) {
                return false;
            }
            try {
                HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return true;
                }
            } catch (IOException ignored) {
                // The owned server is still starting; retry until the bounded deadline.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Waiting for the local model runtime was interrupted.", e);
            }
            try {
                TimeUnit.MILLISECONDS.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Waiting for the local model runtime was interrupted.", e);
            }
        }
        return false;
    }

    private boolean modelIsInstalled() throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/tags"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Could not check installed local models (HTTP " + response.statusCode() + ").");
            }
            JsonNode models = MAPPER.readTree(response.body()).path("models");
            if (!models.isArray()) {
                throw new IOException("The local model runtime returned an invalid installed-model list.");
            }
            for (JsonNode model : models) {
                String name = model.path("name").asText(model.path("model").asText(""));
                if (modelId.equals(name) || (modelId + ":latest").equals(name)) {
                    return true;
                }
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Checking installed local models was interrupted.", e);
        }
    }

    private void pullModel(SetupProgress progress) throws IOException {
        if (modelId.endsWith(":cloud")) {
            throw new IOException("Cloud models are not supported by the local-only first-run setup.");
        }
        String requestBody;
        try {
            requestBody = MAPPER.createObjectNode().put("name", modelId).put("stream", true).toString();
        } catch (RuntimeException e) {
            throw new IOException("Could not prepare the local model download request.", e);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/pull"))
                .timeout(Duration.ofHours(12))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response.body().close();
                throw new IOException("The local model download failed (HTTP " + response.statusCode() + ").");
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                boolean completed = false;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode event = MAPPER.readTree(line);
                    if (event.path("error").isTextual()) {
                        throw new IOException("The local model download failed: " + event.path("error").asText());
                    }
                    String status = event.path("status").asText("Downloading local model...");
                    long total = event.path("total").asLong(0);
                    long downloaded = event.path("completed").asLong(0);
                    progress.update(modelProgressMessage(status, downloaded, total), downloaded, total);
                    if ("success".equalsIgnoreCase(status)) {
                        completed = true;
                    }
                }
                if (!completed) {
                    throw new IOException("The local model download ended before Ollama reported success.");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("The local model download was interrupted.", e);
        }
    }

    private String modelProgressMessage(String status, long completed, long total) {
        if (total > 0) {
            long percentage = Math.min(100, completed * 100 / total);
            return "Downloading " + modelId + ": " + status + " (" + percentage + "%)";
        }
        return "Downloading " + modelId + ": " + status;
    }

    private static Process startProcess(
            Path executable,
            Path workingDirectory,
            Path modelDirectory,
            Path logFile,
            String host,
            int port) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(executable.toString(), "serve")
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile());
        builder.environment().put("OLLAMA_HOST", host + ":" + port);
        builder.environment().put("OLLAMA_MODELS", modelDirectory.toString());
        builder.environment().put("OLLAMA_NO_CLOUD", "1");
        builder.environment().put("OLLAMA_FLASH_ATTENTION", "1");
        builder.environment().put("OLLAMA_MAX_LOADED_MODELS", "1");
        builder.environment().put("OLLAMA_NUM_PARALLEL", "1");
        return builder.start();
    }

    private int findAvailablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(InetAddress.getByName(RUNTIME_HOST), 0));
            return socket.getLocalPort();
        }
    }

    private String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[1024 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available in this Java runtime.", e);
        }
    }

    private String formatBytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @FunctionalInterface
    interface RuntimeStarter {
        Process start(Path executable, Path workingDirectory, Path modelDirectory, Path logFile,
                String host, int port) throws IOException;
    }
}

