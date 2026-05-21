package com.adeptia.zendesk;

import java.io.*;
import java.nio.file.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLSession;
import java.util.zip.GZIPInputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Generates printable PDFs for Zendesk tickets using:
 *  - Zendesk REST API (tickets, comments, users, groups)
 *  - OpenHTMLtoPDF (HTML ➜ PDF rendering)
 *  - Embedded (classpath) HTML template + CSS
 *
 * Robust for CLI runs (CMD/PowerShell):
 *  - System proxies enabled, HTTP/2 disabled (HTTP/1.1)
 *  - Absolute, writable outDir resolution
 *  - Redirects followed (Zendesk content_url ➜ S3)
 *  - Windows-safe filenames + atomic writes
 *  - Comment pagination (per_page=100)
 *
 * Author: Saurabh Sharma
 * Since : 2025-08-28
 */
public class PrintTicketPdf {

    // ===================== CONFIG =====================

    private static final Logger logger = LoggerFactory.getLogger(PrintTicketPdf.class);

    private static Properties PROPS;
    private static String SUBDOMAIN, EMAIL, API_TOKEN, AUTH;
    private static File OUT_DIR;
    private static int THREADS;
    private static Path ERROR_CSV;
    private static HttpClient HTTP;
    private static int PDF_SUBJECT_MAX = 150;

    /** Classpath resources for the embedded template and stylesheet. */
    private static final String TEMPLATE_HTML_RES = "/templates/ticket-template.html";
    private static final String TEMPLATE_CSS_RES  = "/templates/ticket.css";

    // cached after init
    private static String TEMPLATE_HTML_RAW;
    private static String TEMPLATE_CSS_RAW;

    // Jackson
    private static final ObjectMapper M = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // Cached constants/formatters
    private static final DateTimeFormatter ISO_OUT =
            DateTimeFormatter.ofPattern("MMMM d, uuuu 'at' h:mm a", Locale.ENGLISH);
    private static final String UA_MAIN = "Adeptia-Zendesk-PDF/1.2";
    private static final String UA_DL   = "Adeptia-ZendeskDownloader/1.0";
    private static final ThreadLocal<java.util.Random> RNG =
            ThreadLocal.withInitial(java.util.Random::new);
    private static final List<ErrorRow> ERRORS = Collections.synchronizedList(new ArrayList<>());

    // Reused JSoup safelist (built once)
    private static final Safelist SAFE_HTML =
            Safelist.relaxed()
                    .addTags("table","thead","tbody","tfoot","tr","th","td","pre","code","hr","blockquote")
                    .addAttributes("a","href","title","target")
                    .addProtocols("a","href","http","https","mailto")
                    .addAttributes("img","src","alt","title","width","height")
                    .addProtocols("img","src","http","https","data");

    // ===================== ENTRYPOINT =====================

    /**
     * Entry point invoked by ZendeskSearchExportIds.
     * @param ticketIds  list of ticket IDs to render
     * @param configFile path to properties file (required)
     */
    public static void printTicket(List<Long> ticketIds, String configFile) throws Exception {
        if (configFile == null || configFile.trim().isEmpty()) {
            throw new IllegalArgumentException("Config file path is required. Pass it from ZendeskSearchExportIds.");
        }

        // Parity with IDE for networking

        System.setProperty("jdk.httpclient.http2", "false");
        System.setProperty("jdk.httpclient.enableallownonstandardredirects", "true");

        // STRICT load (throws if file missing or unreadable)
        PROPS = loadPropsStrict(configFile);

        // REQUIRED
        SUBDOMAIN = require("subdomain");
        EMAIL     = require("email");      // include "/token" if using API token auth
        API_TOKEN = require("apiToken");

        // Resolve outDir to ABSOLUTE, WRITABLE path
        final Path OUT_DIR_PATH = resolveOutDir(require("outDir"));
        OUT_DIR = OUT_DIR_PATH.toFile();

        // OPTIONAL
        THREADS = parseInt(
                propOrDefault("threads",
                        String.valueOf(Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors())))),
                5
        );
        PDF_SUBJECT_MAX = parseInt(propOrDefault("pdfSubjectMax", "150"), 150);
        // errorCsv near outDir (absolute)
        ERROR_CSV = Paths.get(propOrDefault(
                "errorCsv",
                OUT_DIR_PATH.resolve("ticket-errors.csv").toAbsolutePath().toString()
        ));
        // Quiet noisy renderer logs
        suppressOpenHtmlToPdfLogs();

        // Load template resources
        initTemplateResources();

        // Derived HTTP state
        AUTH = "Basic " + java.util.Base64.getEncoder()
                .encodeToString((EMAIL + ":" + API_TOKEN).getBytes(StandardCharsets.UTF_8));

        // Robust HttpClient (HTTP/1.1 + redirects)
        Executor httpExec = Executors.newFixedThreadPool(Math.max(THREADS, 8), r -> {
            Thread t = new Thread(r);
            t.setName("http-client-" + t.getId());
            t.setDaemon(true);
            return t;
        });
        HTTP = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .version(HttpClient.Version.HTTP_1_1)
                .executor(httpExec)
                .build();

        // Diagnostics
        logger.info("[env] java.version=" + System.getProperty("java.version"));
        logger.info("[env] java.home=" + System.getProperty("java.home"));
        logger.info("[env] user.dir=" + java.nio.file.Paths.get("").toAbsolutePath());
        logger.info("[env] outDir=" + OUT_DIR_PATH.toAbsolutePath() + " writable=" + Files.isWritable(OUT_DIR_PATH));
        logger.info("[env] errorCSV=" + ERROR_CSV.toAbsolutePath() + " writable=" + Files.isWritable(ERROR_CSV));
        Objects.requireNonNull(ticketIds, "ticketIds");
        long t0 = System.currentTimeMillis();
        ensureDir(OUT_DIR_PATH);

        final Map<Long, UserDisplay> USER_CACHE  = new ConcurrentHashMap<>();
        final Map<Long, String>      GROUP_CACHE = new ConcurrentHashMap<>();

        final java.util.concurrent.atomic.AtomicInteger success = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger failed  = new java.util.concurrent.atomic.AtomicInteger();


        // Bounded pool
        ThreadFactory tf = r -> {
            Thread t = new Thread(r);
            t.setName("ticket-worker-" + t.getId());
            t.setDaemon(true);
            return t;
        };
        BlockingQueue<Runnable> q = new ArrayBlockingQueue<>(THREADS * 4);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                THREADS, THREADS, 0L, TimeUnit.MILLISECONDS, q, tf, new ThreadPoolExecutor.CallerRunsPolicy());

        for (long ticketId : ticketIds) {
            pool.submit(() -> {
                try {
                    processOne(ticketId, USER_CACHE, GROUP_CACHE);
                    success.incrementAndGet();
                } catch (Exception e) {
                    failed.incrementAndGet();
                    logger.error("Error for ticket " + ticketId + ": " + e.getMessage(), e);
                    ERRORS.add(new ErrorRow(
                            ticketId,
                            "", // subject unavailable if fetch failed
                            "Ticket processing failed: " + e.getMessage()
                    ));
                }
            });
        }

        pool.shutdown();
        try {
            pool.awaitTermination(7, TimeUnit.DAYS);

        }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        if (HTTP != null) {
            Optional<Executor> optExec = HTTP.executor();
            if (optExec.isPresent() && optExec.get() instanceof ExecutorService) {
                ExecutorService es = (ExecutorService) optExec.get();
                es.shutdown();
                try {
                    if (!es.awaitTermination(5, TimeUnit.MINUTES)) {
                        logger.warn("HTTP executor did not terminate cleanly within timeout");
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        if (!ERRORS.isEmpty()) {
            try {
                File csv = resolveErrorCsvFile();
                File retry = resolveRetryIdsFile();
                writeErrorsCsv(csv, ERRORS);
                writeRetryList(retry, ERRORS);
                logger.error("{} ticket(s) failed. Error CSV: {}", ERRORS.size(), csv.getAbsolutePath());
                logger.error("↻ Retry IDs written to: {}", retry.getAbsolutePath());
            } catch (Throwable t) {
                logger.error("Failed writing error artifacts: {}", t.getMessage());
            }
        }

        long ms = System.currentTimeMillis() - t0;
        logger.info("======================================");
        logger.info("Tickets total:   {}", ticketIds.size());
        logger.info("Succeeded:       {}", success.get());
        logger.info("Failed:          {}", failed.get());
        logger.info("Elapsed:         {} ({} ms)", formatDuration(ms), ms);
        logger.info("======================================");
    }

    private static void processOne(
            long ticketId,
            Map<Long, UserDisplay> userCache,
            Map<Long, String> groupCache
    ) throws Exception {

        // ticket directory
        Path ticketDir = OUT_DIR.toPath().resolve(Long.toString(ticketId));
        Files.createDirectories(ticketDir);
       // attachment directory
        Path attachmentDir = ticketDir.resolve("attachment");
        Files.createDirectories(attachmentDir);

        // ticket JSON
        JsonObject ticket = fetchTicket(ticketId);
        String subject =
                ticket.has("subject") && !ticket.get("subject").isJsonNull()
                        ? ticket.get("subject").getAsString()
                        : "";

        // comments (all pages)
        List<JsonObject> comments = fetchAllComments(ticketId);


        // ================= Attachments =================
        for (JsonObject comment : comments) {
            long commentId = comment.get("id").getAsLong();
            JsonArray atts = comment.getAsJsonArray("attachments");
            if (atts == null || atts.size() == 0) continue;

            for (JsonElement a : atts) {
                JsonObject att = a.getAsJsonObject();

                String fileUrl = (att.has("content_url") && !att.get("content_url").isJsonNull())
                        ? att.get("content_url").getAsString()
                        : null;

                String fileName = (att.has("file_name") && !att.get("file_name").isJsonNull())
                        ? att.get("file_name").getAsString()
                        : "attachment.bin";

                if (fileUrl == null || fileUrl.isBlank()) {
                    logger.warn("Ticket {} comment {} has attachment without content_url",
                            ticketId, commentId);
                    continue;
                }

                try {
                    saveAttachment(
                            HTTP,
                            AUTH,
                            URI.create(fileUrl),
                            attachmentDir,
                            fileName
                    );
                } catch (Exception e) {
                    logger.error(
                            "Failed attachment for ticket {} comment {} ({}): {}",
                            ticketId, commentId, fileName, e.getMessage()
                    );
                    ERRORS.add(new ErrorRow(
                            ticketId,
                            subject,
                            "Attachment download failed (" + fileName + "): " + e.getMessage()
                    ));

                }
            }
        }

        // ================= Resolve users =================
        Set<Long> userIds = new HashSet<>();
        if (ticket.has("requester_id")) userIds.add(ticket.get("requester_id").getAsLong());
        if (ticket.has("assignee_id"))  userIds.add(ticket.get("assignee_id").getAsLong());

        if (ticket.has("email_cc_ids")) {
            for (JsonElement e : ticket.getAsJsonArray("email_cc_ids")) {
                if (!e.isJsonNull()) userIds.add(e.getAsLong());
            }
        }

        for (JsonObject c : comments) {
            if (c.has("author_id") && !c.get("author_id").isJsonNull()) {
                userIds.add(c.get("author_id").getAsLong());
            }
        }

        Map<Long, UserDisplay> usersResolved =
                fetchUsersByIdsWithCache(userIds, userCache);

        String groupName = "";
        if (ticket.has("group_id") && !ticket.get("group_id").isJsonNull()) {
            long gid = ticket.get("group_id").getAsLong();
            groupName = fetchGroupNameCached(gid, groupCache);
        }

        // ================= Build HTML =================
        JsonNode tNode = M.readTree(ticket.toString());
        List<JsonNode> cNodes = new ArrayList<>(comments.size());
        for (JsonObject c : comments) {
            cNodes.add(M.readTree(c.toString()));
        }

        String printUrl =
                "https://" + SUBDOMAIN + ".zendesk.com/agent/tickets/" + ticketId;

        String html = buildHtml(tNode, cNodes, printUrl, usersResolved, groupName);

        // ================= PDF =================
        subject = ticket.has("subject") && !ticket.get("subject").isJsonNull()
                        ? ticket.get("subject").getAsString()
                        : "";

        String safeSubject = safeFilename(subject, PDF_SUBJECT_MAX);

        String pdfName =
                "ticket_" + ticketId + "_" + safeSubject + ".pdf";

        Path pdfPath = ticketDir.resolve(pdfName);

        htmlToPdf(html, pdfPath.toFile());
    }

    /* ======================= Filename Sanitization ======================= */

    private static String safeFilename(String s, int maxCodePoints) {
        if (s == null) return "no-subject";
        String norm = normalizeUtf8(s);

        // Remove illegal filename characters and tidy whitespace
        String cleaned = norm
                .replaceAll("[\\\\/:*?\"<>|]+", " ")
                .replaceAll("\\s+", " ")
                .trim();

        // Windows quirk: no trailing dot/space
        cleaned = cleaned.replaceAll("[. ]+$", "");

        if (cleaned.isEmpty()) cleaned = "no-subject";

        // Trim by Unicode code points so we don't cut surrogate pairs in half
        if (maxCodePoints > 0) {
            int cpCount = cleaned.codePointCount(0, cleaned.length());
            if (cpCount > maxCodePoints) {
                int end = cleaned.offsetByCodePoints(0, maxCodePoints);
                cleaned = cleaned.substring(0, end).trim();
            }
        }

        // Final sanity: if it somehow ends empty, fallback
        if (cleaned.isEmpty()) cleaned = "no-subject";
        return cleaned;
    }
    // ===================== RESOURCES / TEMPLATES =====================

    private static void initTemplateResources() {
        TEMPLATE_HTML_RAW = readResource(TEMPLATE_HTML_RES);
        TEMPLATE_CSS_RAW  = readResource(TEMPLATE_CSS_RES);
    }

    private static String readResource(String resPath) {
        try (InputStream in = PrintTicketPdf.class.getResourceAsStream(resPath)) {
            if (in == null) throw new RuntimeException("Resource not found in jar: " + resPath);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read resource: " + resPath, e);
        }
    }

    static Path resolveOutDir(String configured) throws Exception {
        Path p = Paths.get(configured);
        if (!p.isAbsolute()) {
            p = Paths.get(System.getProperty("user.home")).resolve(p).normalize();
        }
        Files.createDirectories(p);
        if (!Files.isWritable(p)) {
            throw new IllegalStateException("Output directory is not writable: " + p);
        }
        logger.info("[env] outDir=" + p.toAbsolutePath());
        return p;
    }

    // ===================== HTTP / JSON CORE =====================

    private static JsonObject fetchTicket(long ticketId) throws Exception {
        String url = "https://" + SUBDOMAIN + ".zendesk.com/api/v2/tickets/" + ticketId + ".json";
        HttpResponse<String> res = sendGetWithRetry(url, 3);
        if (res.statusCode() != 200) {
            throw new IOException("ticket HTTP " + res.statusCode() + " :: " + res.body());
        }
        JsonObject root = JsonParser.parseString(res.body()).getAsJsonObject();
        JsonObject t = root.getAsJsonObject("ticket");
        if (t == null) throw new IOException("No 'ticket' field in response for " + ticketId);
        return t;
    }

    private static HttpResponse<String> sendGetWithRetry(String url, int attempts) throws Exception {
        int tries = 0;
        long backoffMs = 800L;
        IOException lastIo = null;
        InterruptedException lastInt = null;

        while (tries < attempts) {
            tries++;
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(60))
                        .header("Authorization", AUTH)
                        .header("Accept", "application/json")
                        .header("Accept-Encoding", "gzip")
                        .header("User-Agent", UA_MAIN)
                        .GET().build();

                HttpResponse<byte[]> r = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
                int code = r.statusCode();

                if (code == 429 || (code >= 500 && code < 600)) {
                    long retryAfter = r.headers().firstValueAsLong("Retry-After").orElse(-1L);
                    long sleep = retryAfter > 0 ? retryAfter * 1000L : backoffMs;
                    long jitter = (long)(sleep * (0.2 * (RNG.get().nextDouble() - 0.5)));
                    Thread.sleep(Math.max(300L, sleep + jitter));
                    backoffMs = Math.min(backoffMs * 2, 8000L);
                    continue;
                }

                String body = decodeBody(r);
                return new StringResponse(r, body);
            } catch (IOException ioe) {
                lastIo = ioe;
                long sleep = Math.min(backoffMs, 5000L);
                long jitter = (long)(sleep * (0.2 * (RNG.get().nextDouble() - 0.5)));
                Thread.sleep(Math.max(200L, sleep + jitter));
                backoffMs *= 2;
            } catch (InterruptedException ie) {
                lastInt = ie;
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (lastIo != null) throw lastIo;
        if (lastInt != null) throw lastInt;
        throw new IOException("HTTP GET failed after retries: " + url);
    }

    private static String decodeBody(HttpResponse<byte[]> r) throws IOException {
        byte[] bytes = r.body();
        String enc = r.headers().firstValue("Content-Encoding").orElse("");
        if (enc.toLowerCase(Locale.ROOT).contains("gzip")) {
            try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(bytes));
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                gis.transferTo(bos);
                bytes = bos.toByteArray();
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void ensureDir(Path dir) {
        try {
            if (Files.notExists(dir)) Files.createDirectories(dir);
            if (!Files.isDirectory(dir)) throw new IllegalStateException("Not a directory: " + dir.toAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException("Unable to create output directory: " + dir, e);
        }
    }

    // ===================== PAGINATION =====================

    private static List<JsonObject> fetchAllComments(long ticketId) throws Exception {
        List<JsonObject> out = new ArrayList<>(128);
        String url = "https://" + SUBDOMAIN + ".zendesk.com/api/v2/tickets/" + ticketId +
                "/comments?sort_order=asc&per_page=100&include=users";
        while (url != null) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", AUTH)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new IOException("comments HTTP " + res.statusCode() + " for " + url + " :: " + res.body());
            }
            JsonObject body = JsonParser.parseString(res.body()).getAsJsonObject();

            JsonArray comments = body.getAsJsonArray("comments");
            if (comments != null) {
                for (JsonElement e : comments) out.add(e.getAsJsonObject());
            }

            // follow pagination
            url = body.has("next_page") && !body.get("next_page").isJsonNull()
                    ? body.get("next_page").getAsString()
                    : null;
            if (url != null && url.isBlank()) url = null;
        }
        return out;
    }

    // ===================== USERS / GROUPS =====================

    /** Lightweight holder for user display info (name/email). */
    static final class UserDisplay {
        final String name;
        final String email;

        UserDisplay(String n, String e) {
            this.name  = n == null ? "" : n;
            this.email = e == null ? "" : e;
        }

        String label() {
            if (!name.isEmpty() && !email.isEmpty()) return name + " <" + email + ">";
            if (!name.isEmpty()) return name;
            if (!email.isEmpty()) return email;
            return "";
        }
    }

    private static String userLabel(Map<Long, UserDisplay> users, long id) {
        if (id <= 0) return "";
        UserDisplay u = users.get(id);
        return (u == null) ? String.valueOf(id) : u.label();
    }

    private static Map<Long, UserDisplay> fetchUsersByIdsWithCache(Set<Long> ids,
                                                                   Map<Long, UserDisplay> cache) throws Exception {
        Map<Long, UserDisplay> map = new HashMap<>();
        if (ids == null || ids.isEmpty()) return map;

        List<Long> needed = new ArrayList<>();
        for (Long id : ids) {
            UserDisplay cached = cache.get(id);
            if (cached != null) {
                map.put(id, cached);
            } else {
                needed.add(id);
            }
        }
        if (needed.isEmpty()) return map;

        int from = 0, batch = 100;
        while (from < needed.size()) {
            int to = Math.min(from + batch, needed.size());
            String csv = needed.subList(from, to).stream().map(String::valueOf).collect(Collectors.joining(","));
            String url = "https://" + SUBDOMAIN + ".zendesk.com/api/v2/users/show_many.json?ids=" + csv;

            HttpResponse<String> res = sendGetWithRetry(url, 3);
            if (res.statusCode() != 200)
                throw new IOException("users/show_many -> " + res.statusCode() + " " + res.body());

            JsonNode root = M.readTree(res.body());
            for (JsonNode u : root.withArray("users")) {
                long uid = u.path("id").asLong();
                String name = u.path("name").asText("");
                String mail = u.path("email").asText("");
                UserDisplay ud = new UserDisplay(name, mail);
                cache.put(uid, ud);
                map.put(uid, ud);
            }
            from = to;
        }
        return map;
    }

    private static String fetchGroupNameCached(long groupId, Map<Long, String> cache) throws Exception {
        if (groupId <= 0) return "";
        String cached = cache.get(groupId);
        if (cached != null) return cached;
        String url = "https://" + SUBDOMAIN + ".zendesk.com/api/v2/groups/" + groupId + ".json";
        HttpResponse<String> res = sendGetWithRetry(url, 3);
        String name;
        if (res.statusCode() != 200) {
            name = String.valueOf(groupId);
        } else {
            name = M.readTree(res.body()).path("group").path("name").asText("");
        }
        cache.put(groupId, name);
        return name;
    }

    // ===================== ERROR ARTIFACTS =====================

    private static File resolveErrorCsvFile() {
        if (ERROR_CSV != null && !ERROR_CSV.toString().isBlank()) {
            return ERROR_CSV.toFile();
        }
        String ts = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return new File(OUT_DIR, "ticket-errors-" + ts + ".csv");
    }

    private static File resolveRetryIdsFile() {
        String ts = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return new File(OUT_DIR, "ticket-retry-" + ts + ".txt");
    }

    // ===================== HTML BUILDING =====================

    private static String buildHtml(JsonNode t, List<JsonNode> comments, String printUrl,
                                    Map<Long, UserDisplay> users, String groupName) {
        String id = t.path("id").asText("");
        String subject = t.path("subject").asText("");
        String submitted = iso(t.path("created_at").asText(""));
        String receivedVia = t.path("via").path("channel").asText("Web Form");

        long requesterId = t.path("requester_id").asLong(0);
        long assigneeId  = t.path("assignee_id").asLong(0);
        String requester = userLabel(users, requesterId);
        String assignee  = userLabel(users, assigneeId);

        List<String> ccLabels = new ArrayList<>();
        for (JsonNode cc : t.withArray("email_cc_ids")) ccLabels.add(userLabel(users, cc.asLong(0)));
        String ccs = String.join(", ", ccLabels);

        String priority = text(t.path("priority"));
        String group = (groupName == null || groupName.isEmpty()) ? text(t.path("group_id")) : groupName;
        String status = text(t.path("status"));
        String statusCat = statusToCategory(status);

        // META
        StringBuilder meta = new StringBuilder(512);
        meta.append(row("Submitted", submitted, "Received via", receivedVia));
        meta.append(row("Requester", requester, "CCs", ccs));
        meta.append(row("Priority", priority, "Group", group));
        meta.append(row("Assignee", assignee, "Status category", statusCat));
        meta.append(row("Ticket status", status, "Business Impact", ""));
        meta.append(row("Frequency of Occurrence", "", "Build Tag", ""));
        meta.append(row("Issue Code", "", "Severity Tier", ""));
        meta.append(row("Internal Status", "Assigned", "Total time spent (sec)", ""));
        meta.append(row("Time spent last update (sec)", "", "Support Type", ""));
        meta.append(row("Product", "", "", ""));

        // MESSAGES
        StringBuilder msgs = new StringBuilder(2048);
        for (int i = 0; i < comments.size(); i++) {
            JsonNode c = comments.get(i);
            long authorId = c.path("author_id").asLong(0);
            String author = userLabel(users, authorId);
            String when = iso(c.path("created_at").asText(""));
            String bodyHtml = renderCommentBody(c);

            msgs.append("<div class='msg'>")
                    .append("<div class='hdr'>").append(esc(author))
                    .append("<span class='when'>").append(esc(when)).append("</span></div>")
                    .append("<div class='body html'>").append(bodyHtml).append("</div>");

            JsonNode atts = c.path("attachments");
            if (atts.isArray() && !atts.isEmpty()) {
                msgs.append("<div class='small'>Attachments:<ul>");
                for (JsonNode a : atts) {
                    String fname = a.path("file_name").asText("");
                    String url   = a.path("content_url").asText("");
                    msgs.append("<li>").append(esc(fname)).append(" — ").append(esc(url)).append("</li>");
                }
                msgs.append("</ul></div>");
            }
            msgs.append("</div>");
            if (i < comments.size() - 1) msgs.append("<div class='dotted'></div>");
        }

        String tpl = TEMPLATE_HTML_RAW;
        if (tpl == null || tpl.isBlank())
            throw new IllegalStateException("Template HTML is empty (resource missing?)");

        return tpl
                .replace("{{INLINE_CSS}}", TEMPLATE_CSS_RAW == null ? "" : TEMPLATE_CSS_RAW)
                .replace("{{TITLE}}", esc("#" + id + " " + subject))
                .replace("{{TICKET_ID}}", esc(id))
                .replace("{{SUBJECT}}", esc(subject))
                .replace("{{META_ROWS}}", meta.toString())
                .replace("{{MESSAGES_HTML}}", msgs.toString())
                .replace("{{PRINT_URL}}", esc(printUrl));
    }

    private static String renderCommentBody(JsonNode c) {
        String html = c.path("html_body").asText("");
        if (html != null && !html.isBlank()) {
            return sanitizeUserHtml(html);
        }
        String text = c.path("body").asText("");
        return textToHtml(text);
    }

    private static String sanitizeUserHtml(String html) {
        if (html == null || html.isBlank()) return "";
        org.jsoup.nodes.Document doc = Jsoup.parseBodyFragment(stripBomAndControls(html));
        org.jsoup.safety.Cleaner cleaner = new org.jsoup.safety.Cleaner(SAFE_HTML);
        doc = cleaner.clean(doc);

        // Drop all non-data images
        for (org.jsoup.nodes.Element img : doc.select("img[src]")) {
            String src = img.attr("src").trim().toLowerCase(Locale.ROOT);
            if (!src.startsWith("data:image/")) {
                img.remove();
            }
        }
        // Remove background images via CSS url(...)
        for (org.jsoup.nodes.Element el : doc.select("[style]")) {
            String style = el.attr("style");
            if (style != null && style.toLowerCase(Locale.ROOT).contains("url(")) {
                String sanitizedStyle = style.replaceAll("(?i)url\\s*\\([^)]*\\)", "");
                if (sanitizedStyle.trim().isEmpty()) el.removeAttr("style");
                else el.attr("style", sanitizedStyle);
            }
        }
        return doc.body().html();
    }

    private static String textToHtml(String text) {
        if (text == null || text.isEmpty()) return "";
        text = stripBomAndControls(text);
        String esc = text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
        return esc.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "<br/>");
    }

    private static String row(String l1, String v1, String l2, String v2) {
        StringBuilder r = new StringBuilder();
        r.append("<tr>")
                .append("<td class='lbl'>").append(esc(l1)).append("</td>")
                .append("<td class='val'>").append(esc(nullToEmpty(v1))).append("</td>")
                .append("<td class='lbl'>").append(esc(l2)).append("</td>")
                .append("<td class='val'>").append(esc(nullToEmpty(v2))).append("</td>")
                .append("</tr>");
        return r.toString();
    }

    private static String statusToCategory(String status) {
        if (status == null) return "";
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "new", "open" -> "Open";
            case "pending", "on-hold" -> "Pending";
            case "solved", "closed" -> "Closed";
            default -> capitalize(status);
        };
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.substring(0,1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    private static String text(JsonNode n) { return (n == null || n.isMissingNode()) ? "" : n.asText(""); }

    private static String iso(String s) {
        if (s == null || s.isEmpty()) return "";
        try { return OffsetDateTime.parse(s).format(ISO_OUT); }
        catch (Exception e) { return s; }
    }

    private static String esc(String s) {
        if (s == null) return "";
        s = normalizeUtf8(s);
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
    }

    private static String nullToEmpty(String s) { return (s == null) ? "" : s; }

    // ===================== HTML ➜ PDF =====================

    private static void htmlToPdf(String html, File pdfFile) throws Exception {
        if (html == null) throw new IllegalArgumentException("HTML is null");

        String cleaned = stripBomAndControls(html);
        String xhtml = toXhtml(cleaned);

        try (FileOutputStream os = new FileOutputStream(pdfFile)) {
            com.openhtmltopdf.pdfboxout.PdfRendererBuilder b = new com.openhtmltopdf.pdfboxout.PdfRendererBuilder();
            b.withHtmlContent(xhtml, null);
            registerFonts(b); // optional fontDir support
            b.toStream(os);
            b.run();
        }
    }

    private static String stripBomAndControls(String s) {
        if (s == null) return "";
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') s = s.substring(1); // BOM
        s = Normalizer.normalize(s, Normalizer.Form.NFC);
        s = s.replace('\u00A0',' ')
                .replace("\u200B","")
                .replace("\u200C","")
                .replace("\u200D","")
                .replace("\u2060","");
        s = s.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", " ");
        s = s.replaceAll("(?<![\\uD800-\\uDBFF])[\\uDC00-\\uDFFF]|[\\uD800-\\uDBFF](?![\\uDC00-\\uDFFF])", " ");
        return s;
    }

    private static String normalizeUtf8(String s) {
        if (s == null) return "";
        s = Normalizer.normalize(s, Normalizer.Form.NFC);
        return s.replace('\u00A0',' ')
                .replace("\u200B","")
                .replace("\u200C","")
                .replace("\u200D","")
                .replace("\u2060","");
    }

    private static String toXhtml(String htmlOrFragment) {
        String s = htmlOrFragment;
        int i = Math.min(256, s.length());
        String head = s.substring(0, i).toLowerCase(Locale.ROOT);
        boolean hasHtmlTag = head.contains("<html");
        String wrapped = hasHtmlTag ? s
                : "<!doctype html><html><head><meta charset='utf-8'></head><body>"+s+"</body></html>";
        Document doc = Jsoup.parse(wrapped);
        doc.outputSettings()
                .syntax(Document.OutputSettings.Syntax.xml)
                .escapeMode(org.jsoup.nodes.Entities.EscapeMode.xhtml)
                .charset(StandardCharsets.UTF_8)
                .prettyPrint(false);
        return doc.outerHtml();
    }

    private static void registerFonts(com.openhtmltopdf.pdfboxout.PdfRendererBuilder b) {
        String dir = propOrDefault("fontDir", "").trim();
        if (dir.isEmpty()) return;
        File fontDir = new File(dir);
        if (!fontDir.isDirectory()) {
            logger.warn("fontDir is not a directory: {}", dir);
            return;
        }
        File[] files = fontDir.listFiles(f -> {
            String n = f.getName().toLowerCase(Locale.ROOT);
            return f.isFile() && (n.endsWith(".ttf") || n.endsWith(".otf"));
        });
        if (files == null) return;
        for (File f : files) {
            try {
                String alias = f.getName().replaceFirst("\\.[^.]+$", "");
                b.useFont(f, alias);
                logger.info("Registered font: {}", f.getAbsolutePath());
            } catch (Exception ex) {
                logger.warn("Failed to register font {}: {}", f.getName(), ex.getMessage());
            }
        }
    }

    // ===================== ERROR CSV / RETRY LIST =====================

    private static void writeErrorsCsv(File file, List<ErrorRow> rows) {
        ensureDir(file.getParentFile().toPath());

        try (PrintWriter pw = new PrintWriter(file, StandardCharsets.UTF_8)) {
            pw.println("ticket_id,ticket_subject,error_message");

            rows.stream()
                    .sorted(Comparator.comparingLong(r -> r.ticketId))
                    .forEach(r -> pw.println(
                            r.ticketId + "," +
                                    csv(r.subject) + "," +
                                    csv(r.message)
                    ));
        } catch (IOException ioe) {
            logger.error("Failed to write error CSV: {}", ioe.getMessage());
        }
    }


    private static void writeRetryList(File file, List<ErrorRow> rows) {
        ensureDir(file.getParentFile().toPath());
        try (PrintWriter pw = new PrintWriter(file, StandardCharsets.UTF_8)) {
            rows.stream().map(r -> r.ticketId).distinct().sorted().forEach(pw::println);
        } catch (IOException ioe) {
            logger.error("Failed to write retry list: " + ioe.getMessage());
        }
    }

    private static String csv(String s) {
        if (s == null) return "\"\"";
        s = s.replace("\r"," ").replace("\n"," ").replace("\t"," ");
        s = s.replace("\"","\"\"");
        return "\"" + s + "\"";
    }

    private static String stack(Throwable t) {
        if (t == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(t.getClass().getSimpleName()).append(": ")
                .append(t.getMessage() == null ? "" : t.getMessage());
        for (StackTraceElement el : t.getStackTrace()) {
            sb.append("\n  at ").append(el);
            if (sb.length() > 8000) break;
        }
        return sb.toString();
    }

    private static synchronized void logTicketFailure(long ticketId, Throwable t) {
        String msg = (t == null || t.getMessage() == null) ? "" : t.getMessage();
        msg = msg.replace("\r"," ").replace("\n"," ").replace(",", " ");
        String line = ticketId + ",\"" + msg.replace("\"","\"\"") + "\"\n";
        try {
            Files.writeString(ERROR_CSV, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ioe) {
            logger.error("Failed to write error CSV: " + ioe.getMessage());
        }
    }

    /** Row model for error CSV and retry list. */
    private static final class ErrorRow {
        final long ticketId;
        final String subject;
        final String message;

        ErrorRow(long ticketId, String subject, String message) {
            this.ticketId = ticketId;
            this.subject = subject == null ? "" : subject;
            this.message = message == null ? "" : message;
        }
    }

    // ===================== STRICT PROPS =====================

    private static Properties loadPropsStrict(String file) {
        File f = new File(file);
        if (!f.exists() || !f.isFile()) {
            throw new RuntimeException("Config file not found: " + file +
                    "\nPass the path from ZendeskSearchExportIds when calling PrintTicketPdf.printTicket(ids, configFile).");
        }
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(f)) {
            p.load(in);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read config: " + file, e);
        }
        logger.info("Loaded config: {}", f.getAbsolutePath());
        return p;
    }

    private static String require(String key) {
        String v = PROPS.getProperty(key);
        if (v == null || v.trim().isEmpty()) {
            throw new RuntimeException("Missing required property: " + key);
        }
        return v.trim();
    }

    private static String propOrDefault(String key, String def) {
        String v = PROPS.getProperty(key);
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    // ===================== LOG SUPPRESSION =====================

    private static void suppressOpenHtmlToPdfLogs() {
        try {
            System.setProperty("xr.util-logging.loggingEnabled", "false");

            java.util.logging.Level off = java.util.logging.Level.OFF;
            for (String c : new String[]{
                    "com.openhtmltopdf",
                    "com.openhtmltopdf.load",
                    "com.openhtmltopdf.match",
                    "com.openhtmltopdf.css-parse",
                    "com.openhtmltopdf.general"
            }) {
                java.util.logging.Logger lg = java.util.logging.Logger.getLogger(c);
                lg.setLevel(off);
                lg.setUseParentHandlers(false);
                for (java.util.logging.Handler h : lg.getHandlers()) lg.removeHandler(h);
            }

            try {
                Object slf4j = org.slf4j.LoggerFactory.getLogger("com.openhtmltopdf");
                Class<?> lbLogger = Class.forName("ch.qos.logback.classic.Logger");
                if (lbLogger.isInstance(slf4j)) {
                    Class<?> lbLevel = Class.forName("ch.qos.logback.classic.Level");
                    Object ERROR = lbLevel.getField("ERROR").get(null);
                    lbLogger.getMethod("setLevel", lbLevel).invoke(slf4j, ERROR);
                }
            } catch (ClassNotFoundException ignore) {
            } catch (Throwable ignore) {
            }

            System.setProperty("org.slf4j.simpleLogger.log.com.openhtmltopdf", "off");
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "error");

        } catch (Throwable ignore) {
        }
    }

    // ===================== ATTACHMENT DOWNLOAD =====================

    static void saveAttachment(HttpClient http, String authHeader, URI fileUri, Path targetDir, String suggestedName)
            throws IOException, InterruptedException {
        Files.createDirectories(targetDir);

        String fileName = safeFileName(suggestedName);
        Path finalPath = pickNameWithCounter(targetDir, fileName);
        Path tmp = finalPath.resolveSibling(finalPath.getFileName().toString() + ".part");

        int maxRetries = 4;
        long backoff = 800;

        long existing = Files.exists(tmp) ? Files.size(tmp) : 0L;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            HttpRequest.Builder rb = HttpRequest.newBuilder(fileUri)
                    .timeout(Duration.ofMinutes(5))
                    .header("Authorization", authHeader)
                    .header("Accept", "*/*")
                    .header("Accept-Encoding", "identity")
                    .header("User-Agent", UA_DL);

            if (existing > 0) {
                rb.header("Range", "bytes=" + existing + "-");
            }

            HttpRequest req = rb.GET().build();
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            int code = resp.statusCode();

            if (code != 200 && code != 206) {
                if (attempt == 1) {
                    URI alt = fileUri.toString().contains("?") ? URI.create(fileUri + "&download=1")
                            : URI.create(fileUri + "?download=1");
                    fileUri = alt;
                    attempt--;
                    continue;
                }
                throw new IOException("HTTP " + code + " for " + fileUri);
            }

            OpenOption[] opts = (existing > 0 && code == 206)
                    ? new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND}
                    : new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE};

            if (code == 200 && existing > 0) {
                existing = 0; // restart if server ignored Range
            }

            long chunkLen = resp.headers().firstValueAsLong("Content-Length").orElse(-1L);

            long writtenThisChunk = 0;
            try (InputStream in = resp.body();
                 OutputStream out = Files.newOutputStream(tmp, opts)) {
                byte[] buf = new byte[512 * 1024];
                int r;
                while ((r = in.read(buf)) != -1) {
                    out.write(buf, 0, r);
                    writtenThisChunk += r;
                }
            }

            if (chunkLen >= 0 && writtenThisChunk < chunkLen) {
                Thread.sleep(backoff);
                backoff = Math.min(backoff * 2, 5000);
                existing = Files.exists(tmp) ? Files.size(tmp) : 0L;
                continue;
            }

            long expectedTotal = -1L;
            if (code == 200) {
                expectedTotal = resp.headers().firstValueAsLong("Content-Length").orElse(-1L);
            } else {
                String cr = resp.headers().firstValue("Content-Range").orElse(null);
                if (cr != null && cr.startsWith("bytes")) {
                    int slash = cr.indexOf('/');
                    if (slash > 0) {
                        try { expectedTotal = Long.parseLong(cr.substring(slash + 1).trim()); } catch (NumberFormatException ignore) {}
                    }
                }
            }

            long actual = Files.size(tmp);
            if (expectedTotal > 0 && actual < expectedTotal) {
                Thread.sleep(backoff);
                backoff = Math.min(backoff * 2, 5000);
                existing = actual;
                continue;
            }

            Files.move(tmp, finalPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            if (Files.size(finalPath) == 0) {
                Files.deleteIfExists(finalPath);
                throw new IOException("Downloaded 0 bytes for " + fileUri);
            }
            return; // success
        }

        throw new IOException("Failed to fully download after retries: " + fileUri);
    }

    static String safeFileName(String raw) {
        if (raw == null || raw.isBlank()) return "attachment.bin";
        return raw.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "_").trim();
    }

    public static Path pickNameWithCounter(Path dir, String fileName) throws IOException {
        String safe = safeFileName(fileName);
        int dot = safe.lastIndexOf('.');
        String base = (dot > 0) ? safe.substring(0, dot) : safe;
        String ext  = (dot > 0) ? safe.substring(dot)     : "";
        Path candidate = dir.resolve(safe);
        int n = 1;
        while (Files.exists(candidate)) {
            candidate = dir.resolve(base + " (" + (n++) + ")" + ext);
        }
        return candidate;
    }

    // ===================== INTERNAL TYPES =====================

    private static final class StringResponse implements HttpResponse<String> {
        private final HttpResponse<byte[]> delegate;
        private final String body;
        StringResponse(HttpResponse<byte[]> delegate, String body) { this.delegate = delegate; this.body = body; }

        @Override public int statusCode() { return delegate.statusCode(); }
        @Override public HttpRequest request() { return delegate.request(); }
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return delegate.headers(); }
        @Override public String body() { return body; }
        @Override public Optional<SSLSession> sslSession() { return delegate.sslSession(); }
        @Override public URI uri() { return delegate.uri(); }
        @Override public HttpClient.Version version() { return delegate.version(); }
    }

    // ===================== SMALL UTILS =====================

    private static final Object ERRCSV_LOCK = new Object();

    private static void appendErrorCsv(long ticketId, String stage, String message) {
        try {
            if (ERROR_CSV == null) return;
            Path csv = ERROR_CSV;
            if (csv.getParent() != null) Files.createDirectories(csv.getParent());
            boolean newFile = !Files.exists(csv);

            try (Writer w = Files.newBufferedWriter(
                    csv, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {

                synchronized (ERRCSV_LOCK) {
                    if (newFile) {
                        w.write("ticketId,stage,message,time\n");
                    }
                    w.write(ticketId + "," + csvEsc(stage) + "," + csvEsc(message) + "," +
                            OffsetDateTime.now().toString() + "\n");
                }
            }
        } catch (Exception e) {
            logger.warn("appendErrorCsv failed: {}", e.toString());
        }
    }
    private static String csvEsc(String s) {
        if (s == null) s = "";
        s = s.replace("\r", " ").replace("\n", " ");
        s = s.replace("\"", "\"\"");
        return "\"" + s + "\"";
    }
    private static String formatDuration(long ms) {
        long s = ms / 1000; long m = s / 60; long h = m / 60;
        s %= 60; m %= 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m ");
        sb.append(s).append("s");
        return sb.toString();
    }


}
