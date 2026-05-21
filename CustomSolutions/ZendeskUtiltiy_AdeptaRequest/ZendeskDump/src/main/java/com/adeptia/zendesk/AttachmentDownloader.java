package com.adeptia.zendesk;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.Base64;

public final class AttachmentDownloader {
    private final HttpClient client;
    private final String authHeader;
    private final Path baseDir;
    private final int maxRetries;

    public AttachmentDownloader(String emailWithToken, String apiToken, Path baseDir, int maxRetries) throws IOException {
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)         // safer behind some proxies
                .followRedirects(HttpClient.Redirect.ALWAYS)  // Zendesk → S3
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        String creds = emailWithToken + ":" + apiToken;      // "user@x/token:apitoken"
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString(
                creds.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        this.baseDir = baseDir.toAbsolutePath().normalize();
        this.maxRetries = Math.max(1, maxRetries);
        Files.createDirectories(this.baseDir);
    }

    public Path download(String contentUrl, String originalName, long ticketId, long commentId) throws Exception {
        String safeName = sanitize(originalName);
        Path dir = baseDir.resolve(Long.toString(ticketId)).resolve(Long.toString(commentId));
        Files.createDirectories(dir);

        Path target = uniquePath(dir.resolve(safeName));
        Path tmp = target.resolveSibling(target.getFileName() + ".part");

        int attempt = 0;
        while (true) {
            attempt++;
            try {
                System.out.println("[att] GET " + contentUrl + " → " + target.getFileName() + " (try " + attempt + ")");
                HttpRequest req = HttpRequest.newBuilder(URI.create(contentUrl))
                        .header("Authorization", authHeader) // first hop needs Zendesk auth
                        .header("User-Agent", "Adeptia-ZendeskDump/1.0")
                        .timeout(Duration.ofMinutes(2))
                        .GET()
                        .build();

                HttpResponse<InputStream> res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
                int code = res.statusCode();
                if (code != 200) {
                    byte[] peek = res.body() != null ? res.body().readNBytes(4096) : new byte[0];
                    System.err.println("[att] HTTP " + code + " Body first 4KB:\n" +
                            new String(peek, java.nio.charset.StandardCharsets.UTF_8));
                    throw new IOException("HTTP " + code);
                }

                // Write to temp, then atomic move
                long bytes;
                try (InputStream in = res.body();
                     OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    bytes = in.transferTo(out);
                }
                if (bytes <= 0) throw new IOException("0 bytes downloaded");

                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[att] saved " + bytes + " bytes → " + target);
                return target;

            } catch (Exception ex) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignore) {}
                if (attempt >= maxRetries) {
                    System.err.println("[att] FAILED after " + attempt + " tries: " + ex.getMessage());
                    throw ex;
                }
                // backoff
                Thread.sleep(500L * attempt);
            }
        }
    }

    private static String sanitize(String n) {
        if (n == null || n.isBlank()) n = "attachment.bin";
        String s = n.replaceAll("[\\\\/:*?\"<>|]", "_").trim(); // Windows illegal chars
        if (s.length() > 180) {
            int dot = s.lastIndexOf('.');
            if (dot > 0 && dot >= s.length() - 10) {
                String ext = s.substring(dot);
                s = s.substring(0, 180 - ext.length()) + ext;
            } else {
                s = s.substring(0, 180);
            }
        }
        String u = s.toUpperCase();
        if (u.matches("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$")) s = "_" + s;
        return s;
    }

    private static Path uniquePath(Path target) throws IOException {
        if (!Files.exists(target)) return target;
        String f = target.getFileName().toString();
        String base = f, ext = "";
        int dot = f.lastIndexOf('.');
        if (dot > 0) { base = f.substring(0, dot); ext = f.substring(dot); }
        for (int i = 1; i < 1000; i++) {
            Path p = target.getParent().resolve(base + " (" + i + ")" + ext);
            if (!Files.exists(p)) return p;
        }
        throw new IOException("Cannot create unique filename for " + target);
    }
}
