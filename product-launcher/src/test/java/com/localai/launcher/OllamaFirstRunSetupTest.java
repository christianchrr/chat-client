package com.localai.launcher;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OllamaFirstRunSetupTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void downloadsVerifiesInstallsRuntimeAndPullsModelWithProgress() throws Exception {
        byte[] archive = runtimeArchive("ollama.exe", "test executable");
        String digest = sha256(archive);
        HttpServer downloads = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        downloads.createContext("/runtime.zip", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/zip");
            exchange.sendResponseHeaders(200, archive.length);
            try (var output = exchange.getResponseBody()) {
                output.write(archive);
            }
        });
        downloads.start();

        int runtimePort = availablePort();
        HttpServer runtime = HttpServer.create(new InetSocketAddress("127.0.0.1", runtimePort), 0);
        AtomicInteger pullRequests = new AtomicInteger();
        runtime.createContext("/api/version", exchange -> respond(exchange, 200, "{\"version\":\"test\"}"));
        runtime.createContext("/api/tags", exchange -> respond(exchange, 200, "{\"models\":[]}"));
        runtime.createContext("/api/pull", exchange -> {
            pullRequests.incrementAndGet();
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(request.contains("\"name\":\"llama3.2\""));
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write("{\"status\":\"pulling manifest\"}\n".getBytes(StandardCharsets.UTF_8));
                output.write("{\"status\":\"downloading\",\"total\":100,\"completed\":50}\n"
                        .getBytes(StandardCharsets.UTF_8));
                output.write("{\"status\":\"success\"}\n".getBytes(StandardCharsets.UTF_8));
            }
        });
        runtime.start();

        FakeProcess ownedProcess = new FakeProcess();
        AtomicInteger starts = new AtomicInteger();
        List<String> progressMessages = new ArrayList<>();
        OllamaFirstRunSetup setup = new OllamaFirstRunSetup(
                temporaryDirectory,
                "llama3.2",
                HttpClient.newHttpClient(),
                "http://127.0.0.1:" + downloads.getAddress().getPort() + "/runtime.zip",
                digest,
                (executable, workingDirectory, modelDirectory, logFile, host, port) -> {
                    starts.incrementAndGet();
                    assertTrue(Files.isRegularFile(executable));
                    assertEquals(runtimePort, port);
                    assertTrue(modelDirectory.startsWith(temporaryDirectory.resolve("models")));
                    return ownedProcess;
                },
                runtimePort,
                true);
        try {
            setup.prepare((message, completed, total) -> progressMessages.add(message));

            assertEquals(1, starts.get());
            assertEquals(1, pullRequests.get());
            assertTrue(progressMessages.stream().anyMatch(message -> message.contains("Downloading Ollama runtime")));
            assertTrue(progressMessages.stream().anyMatch(message -> message.contains("50%")));
            assertEquals("http://127.0.0.1:" + runtimePort, setup.baseUrl());
            assertTrue(Files.isRegularFile(temporaryDirectory.resolve(
                    "runtime/ollama-" + OllamaFirstRunSetup.RUNTIME_VERSION + "/ollama.exe")));
            assertFalse(Files.exists(temporaryDirectory.resolve(
                    "runtime/ollama-" + OllamaFirstRunSetup.RUNTIME_VERSION + ".zip.part")));
        } finally {
            setup.stop();
            runtime.stop(0);
            downloads.stop(0);
        }
        assertTrue(ownedProcess.destroyed);
    }

    @Test
    void rejectsRuntimeDownloadWhenPinnedDigestDoesNotMatch() throws Exception {
        byte[] archive = runtimeArchive("ollama.exe", "tampered");
        HttpServer downloads = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        downloads.createContext("/runtime.zip", exchange -> {
            exchange.sendResponseHeaders(200, archive.length);
            try (var output = exchange.getResponseBody()) {
                output.write(archive);
            }
        });
        downloads.start();
        AtomicInteger starts = new AtomicInteger();
        OllamaFirstRunSetup setup = new OllamaFirstRunSetup(
                temporaryDirectory,
                "llama3.2",
                HttpClient.newHttpClient(),
                "http://127.0.0.1:" + downloads.getAddress().getPort() + "/runtime.zip",
                "0".repeat(64),
                (executable, workingDirectory, modelDirectory, logFile, host, port) -> {
                    starts.incrementAndGet();
                    return new FakeProcess();
                },
                availablePort(),
                true);
        try {
            IOException error = assertThrows(IOException.class, () -> setup.prepare((message, done, total) -> { }));
            assertTrue(error.getMessage().contains("SHA-256"));
            assertEquals(0, starts.get());
            assertFalse(Files.exists(temporaryDirectory.resolve(
                    "runtime/ollama-" + OllamaFirstRunSetup.RUNTIME_VERSION)));
        } finally {
            downloads.stop(0);
        }
    }

    @Test
    void rejectsZipTraversalBeforeInstallingAnything() throws Exception {
        byte[] archive = runtimeArchive("../escaped.txt", "outside");
        HttpServer downloads = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        downloads.createContext("/runtime.zip", exchange -> {
            exchange.sendResponseHeaders(200, archive.length);
            try (var output = exchange.getResponseBody()) {
                output.write(archive);
            }
        });
        downloads.start();
        OllamaFirstRunSetup setup = new OllamaFirstRunSetup(
                temporaryDirectory,
                "llama3.2",
                HttpClient.newHttpClient(),
                "http://127.0.0.1:" + downloads.getAddress().getPort() + "/runtime.zip",
                sha256(archive),
                (executable, workingDirectory, modelDirectory, logFile, host, port) -> new FakeProcess(),
                availablePort(),
                true);
        try {
            IOException error = assertThrows(IOException.class, () -> setup.prepare((message, done, total) -> { }));
            assertTrue(error.getMessage().contains("unsafe file path"));
            assertFalse(Files.exists(temporaryDirectory.resolve("runtime/escaped.txt")));
        } finally {
            downloads.stop(0);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static byte[] runtimeArchive(String fileName, String content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(fileName));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            return socket.getLocalPort();
        }
    }

    private static final class FakeProcess extends Process {
        private boolean destroyed;

        @Override
        public java.io.OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public java.io.InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public java.io.InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit) {
            return destroyed;
        }

        @Override
        public int exitValue() {
            if (!destroyed) {
                throw new IllegalThreadStateException();
            }
            return 0;
        }

        @Override
        public void destroy() {
            destroyed = true;
        }

        @Override
        public Process destroyForcibly() {
            destroyed = true;
            return this;
        }

        @Override
        public boolean isAlive() {
            return !destroyed;
        }
    }
}
