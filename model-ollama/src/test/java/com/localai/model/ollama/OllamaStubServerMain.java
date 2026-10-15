package com.localai.model.ollama;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class OllamaStubServerMain {
    private OllamaStubServerMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: OllamaStubServerMain <host> <port> <modelRoot>");
        }

        String host = args[0];
        int port = Integer.parseInt(args[1]);
        Path modelRoot = Path.of(args[2]);
        Files.createDirectories(modelRoot);

        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/api/version", exchange -> {
            byte[] payload = "{\"version\":\"stub\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(payload);
            }
        });

        server.createContext("/api/tags", exchange -> {
            byte[] payload = "{\"models\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(payload);
            }
        });

        server.start();
        Thread.currentThread().join();
    }
}
