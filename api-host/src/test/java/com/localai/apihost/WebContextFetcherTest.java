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

    private static String fetchFrom(String path, String contentType, byte[] payload) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            if (contentType != null) {
                exchange.getResponseHeaders().add("Content-Type", contentType);
            }
            exchange.sendResponseHeaders(200, payload.length);
            try (var output = exchange.getResponseBody()) {
                output.write(payload);
            }
        });
        server.start();
        try {
            var fetcher = new WebContextFetcher(
                    HttpClient.newHttpClient(),
                    host -> new InetAddress[] {InetAddress.getByAddress(new byte[] {8, 8, 8, 8})});
            return fetcher.fetchPages(List.of("http://localhost:" + server.getAddress().getPort() + path))
                    .getFirst();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void extractsTextFromPdfDocuments() throws Exception {
        var output = new java.io.ByteArrayOutputStream();
        try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage();
            document.addPage(page);
            try (var stream = new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(72, 700);
                stream.showText("Quarterly report numbers");
                stream.endText();
            }
            document.save(output);
        }

        String source = fetchFrom("/report.pdf", "application/pdf", output.toByteArray());

        assertTrue(source.contains("Type: PDF document"));
        assertTrue(source.contains("Quarterly report numbers"));
    }

    @Test
    void extractsTextFromWordDocumentsAndPlainDataFiles() throws Exception {
        var docx = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(docx)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("word/document.xml"));
            zip.write(("<w:document xmlns:w=\"w\"><w:body><w:p><w:r><w:t>Meeting </w:t></w:r>"
                    + "<w:r><w:t>notes</w:t></w:r></w:p></w:body></w:document>")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        String word = fetchFrom("/notes.docx", "application/octet-stream", docx.toByteArray());
        String csv = fetchFrom("/data.csv", "text/csv", "name,score\nAda,10\n".getBytes(StandardCharsets.UTF_8));
        String json = fetchFrom("/data.json", "application/json", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));

        assertTrue(word.contains("Type: Word document") && word.contains("Meeting notes"));
        assertTrue(csv.contains("Ada,10"));
        assertTrue(json.contains("\"ok\":true"));
        assertThrows(WebContextFetchException.class,
                () -> fetchFrom("/image.png", "image/png", new byte[] {1, 2, 3}));
        assertThrows(WebContextFetchException.class,
                () -> fetchFrom("/broken.pdf", "application/pdf", new byte[] {1, 2, 3}));
    }
}