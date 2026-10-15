package com.localai.apihost;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebContextFetcherTest {

    @Test
    void extractsBoundedPageTextAndLabelsItAsUntrusted() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/article", exchange -> {
            byte[] page = """
                    <!doctype html><html><head><title>Useful article</title>
                    <script>ignoreThisSecret()</script></head>
                    <body><nav>navigation words</nav><main><p>Useful public information.</p></main>
                    </body></html>
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, page.length);
            try (var output = exchange.getResponseBody()) {
                output.write(page);
            }
        });
        server.start();

        try {
            var fetcher = new WebContextFetcher(
                    HttpClient.newHttpClient(),
                    host -> new InetAddress[] {InetAddress.getByAddress(new byte[] {8, 8, 8, 8})});
            String url = "http://localhost:" + server.getAddress().getPort() + "/article";

            String source = fetcher.fetchPages(List.of(url)).getFirst();

            assertTrue(source.contains("Title: Useful article"));
            assertTrue(source.contains("Useful public information."));
            assertTrue(source.contains("[Untrusted web source"));
            assertTrue(!source.contains("ignoreThisSecret"));
            assertTrue(!source.contains("navigation words"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void blocksPrivateHostsBeforeMakingRequests() throws Exception {
        var fetcher = new WebContextFetcher(
                HttpClient.newHttpClient(),
                host -> new InetAddress[] {InetAddress.getByAddress(new byte[] {127, 0, 0, 1})});

        WebContextFetchException error = assertThrows(
                WebContextFetchException.class,
                () -> fetcher.fetchPages(List.of("http://private.example/article")));

        assertEquals("private_or_local_web_hosts_are_blocked", error.errorCode());
    }

    @Test
    void rejectsNonHttpSchemes() {
        var fetcher = new WebContextFetcher(
                HttpClient.newHttpClient(),
                host -> new InetAddress[] {InetAddress.getByAddress(new byte[] {8, 8, 8, 8})});

        WebContextFetchException error = assertThrows(
                WebContextFetchException.class,
                () -> fetcher.fetchPages(List.of("file:///C:/Windows/win.ini")));

        assertEquals("only_public_http_urls_are_supported", error.errorCode());
    }
}
