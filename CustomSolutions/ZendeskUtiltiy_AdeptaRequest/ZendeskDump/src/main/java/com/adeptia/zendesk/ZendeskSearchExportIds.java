package com.adeptia.zendesk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

public class ZendeskSearchExportIds {

    // ====== STRICT CONFIG (properties file is REQUIRED) ======
    // Only this flag is accepted via CLI/VM option to select the file path.
    // Example: -Dzendesk.config=D:/cfg/zendesk.properties  or  --config D:/cfg/zendesk.properties
    private static String CONFIG_FILE = System.getProperty("zendesk.config", "D:\\Development\\Zendesk\\zendesk.properties");
//    private static String CONFIG_FILE = "D:\\Saurabh_Files\\Kubernettes\\zendesk.properties";
//    // Loaded values (initialized in main from the REQUIRED properties file)
    private static String SUBDOMAIN;   // required
    private static String EMAIL;       // required. Include "/token" when using API token auth
    private static String API_TOKEN;   // required
    private static String START;       // required (YYYY-MM-DD)
    private static String END;         // required (YYYY-MM-DD)
    private static int    PAGE_SIZE;   // optional, default 1000 (1..1000)
    private static int    MAX_RETRIES; // optional, default 4 (>=1)
    private static int    MAX_TICKETS; // optional, default 0 (<=0 means unlimited)

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .callTimeout(Duration.ofSeconds(90))
            .connectTimeout(Duration.ofSeconds(20))
            .readTimeout(Duration.ofSeconds(60))
            .build();
    // Bootstrap: make terminal behave like IDE for networking
    static void bootstrapNetworkAndLogs() {
        // Match IntelliJ's behavior: honor system proxies
        System.setProperty("java.net.useSystemProxies", "true");
        // Some corp proxies + S3 redirects behave better on HTTP/1.1
        System.setProperty("jdk.httpclient.http2", "false");
        // Allow some non-standard redirects (seen in a few Zendesk edges)
        System.setProperty("jdk.httpclient.enableallownonstandardredirects", "true");
    }

    public static void main(String[] args) throws Exception {
        // Allow overriding ONLY the config file path via --config
        bootstrapNetworkAndLogs();
        for (int i = 0; i < args.length; i++) {
            if ("--config".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                CONFIG_FILE = args[++i];
            }
        }

        Properties cfg = loadPropsStrict(CONFIG_FILE);   // fail-fast if file missing
        initFromPropsStrict(cfg);                         // fail-fast if required keys missing

        long t0 = System.currentTimeMillis();
        List<Long> ticketIds = fetchTicketIds(START, END);
        System.out.println("Total unique ticket IDs: " + ticketIds.size());

        PrintTicketPdf.printTicket(ticketIds,CONFIG_FILE);

        long ms = System.currentTimeMillis() - t0;
        System.out.println("Execution finished in " + formatDuration(ms) + " (" + ms + " ms)");
    }

    private static void initFromPropsStrict(Properties p) {
        SUBDOMAIN   = require(p, "subdomain");
        EMAIL       = require(p, "email");
        API_TOKEN   = require(p, "apiToken");
        START       = requireDate(p, "start");
        END         = requireDate(p, "end");

        PAGE_SIZE   = getInt(p, "pageSize", 1000);
        if (PAGE_SIZE < 1 || PAGE_SIZE > 1000) {
            throw new IllegalArgumentException("pageSize must be between 1 and 1000 (got " + PAGE_SIZE + ")");
        }
        MAX_RETRIES = Math.max(1, getInt(p, "maxRetries", 4));
        MAX_TICKETS = getInt(p, "maxTickets", 0); // <=0 means unlimited
    }

    /**
     * Fetches ALL ticket IDs in the date range, transparently following pagination via links.next.
     * De-duplicates while preserving order. Applies MAX_TICKETS if > 0.
     */
    public static List<Long> fetchTicketIds(String startDate, String endDate) throws IOException {
        Set<Long> ids = new LinkedHashSet<>();

        HttpUrl base = HttpUrl.parse("https://" + SUBDOMAIN + ".zendesk.com/api/v2/search/export.json")
                .newBuilder()
                .addEncodedQueryParameter("filter%5Btype%5D", "ticket")
                .addQueryParameter("query", "created>=" + startDate + " created<=" + endDate)
                .addQueryParameter("page[size]", String.valueOf(PAGE_SIZE))
                .build();

        String next = base.toString();
        String last = null;
        String authHeader = basicAuth(EMAIL, API_TOKEN);

        while (next != null && !next.equals(last)) {
            last = next;

            Request req = new Request.Builder()
                    .url(next)
                    .get()
                    .header("Authorization", authHeader)
                    .header("Accept", "application/json")
                    .header("User-Agent", "ZD-SearchExport-Java/1.3")
                    .build();

            Response resp = null;
            int tries = 0;
            IOException lastErr = null;
            while (tries < MAX_RETRIES) {
                tries++;
                try {
                    resp = CLIENT.newCall(req).execute();
                    int code = resp.code();
                    if (code == 429 || (code >= 500 && code < 600)) {
                        long sleepMs = retryAfterMs(resp, tries);
                        closeQuiet(resp);
                        sleep(sleepMs);
                        continue; // retry
                    }
                    if (!resp.isSuccessful()) {
                        throw new IOException("Search export failed: " + code + " " + resp.message());
                    }

                    JsonNode root = MAPPER.readTree(resp.body().byteStream());

                    JsonNode results = root.path("results");
                    if (results.isArray()) {
                        for (JsonNode r : results) {
                            JsonNode id = r.get("id");
                            if (id != null && id.isNumber()) {
                                ids.add(id.asLong());
                                if (MAX_TICKETS > 0 && ids.size() >= MAX_TICKETS) {
                                    System.out.println("Cap reached (maxTickets=" + MAX_TICKETS + ")");
                                    return new ArrayList<>(ids);
                                }
                            }
                        }
                    }

                    String n = textOrNull(root.path("links").path("next"));
                    next = (n == null || n.isBlank() || "null".equalsIgnoreCase(n)) ? null : n;

                    System.out.println("Fetched page (" + ids.size() + " total so far) next=" + (next == null ? "<end>" : "yes"));
                    break; // page done
                } catch (IOException ioe) {
                    lastErr = ioe;
                    long sleepMs = backoffMs(tries);
                    sleep(sleepMs);
                } finally {
                    closeQuiet(resp);
                }
            }

            if (tries >= MAX_RETRIES && next != null) {
                throw new IOException("Exceeded retries while fetching: " + next, lastErr);
            }
        }

        return new ArrayList<>(ids);
    }

    private static long retryAfterMs(Response r, int tries) {
        String ra = r.header("Retry-After");
        if (ra != null) {
            try { return Math.max(1000L, Long.parseLong(ra) * 1000L); } catch (NumberFormatException ignored) {}
        }
        return backoffMs(tries);
    }

    private static long backoffMs(int tries) {
        long base = 1000L * (1L << Math.min(tries - 1, 3)); // 1s,2s,4s,8s
        return Math.min(8000L, base);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    private static void closeQuiet(Response r) {
        if (r != null) {
            try { r.close(); } catch (Exception ignored) {}
        }
    }

    private static String textOrNull(JsonNode n) {
        return (n == null || n.isNull()) ? null : n.asText(null);
    }

    /** Builds a Basic auth header for Zendesk API token auth. EMAIL must include "/token" when using API token auth. */
    private static String basicAuth(String emailWithTokenSuffix, String token) {
        String raw = emailWithTokenSuffix + ":" + token;
        String b64 = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        return "Basic " + b64;
    }

    // ===== Helpers: properties & validation (STRICT) =====
    private static Properties loadPropsStrict(String file) throws IOException {
        File f = new File(file);
        if (!f.exists() || !f.isFile()) {
            throw new FileNotFoundException("Config file not found: " + file + "Pass -Dzendesk.config=PATH or --config PATH");
        }
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(f)) {
            p.load(in);
        }
        System.out.println("Loaded config: " + f.getAbsolutePath());
        return p;
    }

    private static String require(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null || v.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing required property: " + key);
        }
        return v.trim();
    }

    private static String requireDate(Properties p, String key) {
        String v = require(p, key);
        if (!(v.length() == 10 && v.charAt(4) == '-' && v.charAt(7) == '-')) {
            throw new IllegalArgumentException("Property '" + key + "' must be in YYYY-MM-DD format (got '" + v + "')");
        }
        return v;
    }

    private static int getInt(Properties p, String key, int def) {
        String v = p.getProperty(key);
        if (v == null || v.trim().isEmpty()) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException nfe) {
            throw new IllegalArgumentException("Property '" + key + "' must be an integer (got '" + v + "')");
        }
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
