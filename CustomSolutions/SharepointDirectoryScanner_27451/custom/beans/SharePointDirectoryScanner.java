package custom.beans;

import com.adeptia.indigo.api.custom.ExecutionEvent;
import com.adeptia.indigo.api.custom.ScriptExecutor;
import com.adeptia.indigo.logging.Logger;
import com.adeptia.indigo.security.AuthUtil;
import com.adeptia.indigo.security.auth.PasswordService;
import com.adeptia.indigo.services.ServiceException;
import com.adeptia.indigo.services.transport.account.OAuthAccount;
import com.adeptia.indigo.storage.EntityManagerFactory;
import com.adeptia.indigo.storage.jdo.JdoEntityManager;
import com.adeptia.indigo.system.Context;
import com.adeptia.indigo.system.IndigoConfig;
import com.adeptia.indigo.utils.DataDecoder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.binary.Hex;
import org.springframework.stereotype.Component;

import javax.security.auth.Subject;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scans a SharePoint document library directory (optionally recursive) using
 * client-credentials OAuth. Designed to be called directly from BeanShell
 * with explicit parameters — no dependency on ExecutionEvent context keys.
 *
 * BeanShell usage:
 *   import com.adeptia.custom.beans.SharePointDirectoryScanner;
 *   SharePointDirectoryScanner scanner = beanFactory.getBean(SharePointDirectoryScanner.class);
 *
 *   // Direct download URLs — click any entry to download the file
 *   List urls = scanner.scanDirectory(accountId, baseUrl, folderPath, true);
 *
 *   // Direct download URLs + full metadata (size, timestamps, server-relative path)
 *   List infos = scanner.scanDirectoryWithMetadata(accountId, baseUrl, folderPath, true);
 */
@Component("SharePointDirectoryScanner")
public class SharePointDirectoryScanner implements ScriptExecutor {

    // ── Constants ─────────────────────────────────────────────────────────────

    private static final int      PAGE_SIZE    = 1000;
    private static final int      MAX_RETRIES  = 3;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);
    private static final String   ACCEPT_HEADER   = "application/json;odata=nometadata";
    private static final String   SP_FILES_SELECT =
            "$select=Name,ServerRelativeUrl,Length,TimeCreated,TimeLastModified";

    // ── Shared / instance fields ──────────────────────────────────────────────

    private static final ObjectMapper MAPPER              = new ObjectMapper();
    private static final HttpClient   DEFAULT_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .build();

    private final HttpClient httpClient;

    /** Spring-managed constructor — uses the shared HttpClient. */
    public SharePointDirectoryScanner() {
        this.httpClient = DEFAULT_HTTP_CLIENT;
    }

    /** Package-private — for unit tests only. */
    SharePointDirectoryScanner(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API — called directly from BeanShell with explicit parameters
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a flat list of direct download URLs for every file found under {@code folderPath}.
     * Each URL is {@code tenantRoot + percent-encoded(serverRelativeUrl)} and triggers a file
     * download when clicked — it does not open the SharePoint portal or Office Online viewer.
     *
     * @param accountId  Adeptia OAuth account activity ID for the SharePoint app
     * @param baseUrl    SharePoint site or subsite URL — e.g. https://tenant.sharepoint.com/SG_Subsite
     * @param folderPath Server-relative folder path — e.g. /SG_Subsite/Shared Documents/Target
     * @param recursive  true to walk into every subfolder recursively
     * @return           Mutable list of direct file download URLs
     */
    public List<String> scanDirectory(String accountId, String baseUrl,
                                      String folderPath, boolean recursive)
            throws IOException, InterruptedException, ServiceException {

        String       bearerToken = resolveBearerToken(accountId, false);
        List<String> names       = new ArrayList<>();

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                names.clear();
                collectFileNames(baseUrl, folderPath, bearerToken, recursive, names);
                return names;
            } catch (IOException e) {
                if (attempt < MAX_RETRIES && shouldRetryWithFreshToken(e)) {
                    bearerToken = resolveBearerToken(accountId, true);
                } else {
                    throw new IOException(
                            "SharePoint scan failed after " + attempt + " attempt(s): " + e.getMessage(), e);
                }
            }
        }
        return names;
    }

    /**
     * Returns a flat list of {@link FileInfo} records with full metadata.
     * Use this when you need file size, timestamps, or server-relative URLs
     * in addition to the file name.
     *
     * @param accountId  Adeptia OAuth account activity ID for the SharePoint app
     * @param baseUrl    SharePoint site base URL — e.g. https://tenant.sharepoint.com
     * @param folderPath Server-relative folder path
     * @param recursive  true to walk into every subfolder recursively
     * @return           Mutable list of {@link FileInfo}
     */
    public List<FileInfo> scanDirectoryWithMetadata(String accountId, String baseUrl,
                                                    String folderPath, boolean recursive)
            throws IOException, InterruptedException, ServiceException {

        String         bearerToken = resolveBearerToken(accountId, false);
        List<FileInfo> results     = new ArrayList<>();

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                results.clear();
                collectFileInfos(baseUrl, folderPath, bearerToken, recursive, results);
                return results;
            } catch (IOException e) {
                if (attempt < MAX_RETRIES && shouldRetryWithFreshToken(e)) {
                    bearerToken = resolveBearerToken(accountId, true);
                } else {
                    throw new IOException(
                            "SharePoint scan failed after " + attempt + " attempt(s): " + e.getMessage(), e);
                }
            }
        }
        return results;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ScriptExecutor — context-key fallback for use inside process-flow scripts
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reads parameters from the Adeptia execution context and writes results back.
     *
     * Expected context keys:
     *   accountId          — Adeptia OAuth account activity ID
     *   sharepointBaseUrl  — SharePoint site or subsite URL
     *   folderPath         — Server-relative folder path
     *   recursive          — "true" / "false" (default: "false")
     *
     * Output context keys:
     *   fileCount  — int, total files found
     *   fileUrls   — newline-separated HTML anchor tags; use as $$fileUrls$$ inside an HTML template
     *                e.g. <a href="https://…/file.xlsx">file.xlsx</a>
     */
    @Override
    public void execute(ExecutionEvent event) {
        Context context = event.getContext();
        Logger  logger  = event.getLogger();
        try {
            String  accountId  = (String) context.get("accountId");
            String  baseUrl    = (String) context.get("sharepointBaseUrl");
            String  folderPath = (String) context.get("folderPath");
            String  recurStr   = (String) context.get("recursive");
            boolean recursive  = "true".equalsIgnoreCase(recurStr);

            List<FileInfo> files = scanDirectoryWithMetadata(accountId, baseUrl, folderPath, recursive);

            context.put("fileCount", files.size());
            StringBuilder links = new StringBuilder();
            for (int i = 0; i < files.size(); i++) {
                if (i > 0) links.append("\n");
                FileInfo fi = files.get(i);
                links.append(i + 1).append(". ")
                     .append("<a href=\"").append(fi.downloadUrl).append("\">")
                     .append(escapeHtml(fi.name))
                     .append("</a>");
            }
            context.put("fileNames", links.toString());
            logger.info("SharePoint scan complete — " + files.size() + " file(s) in " + folderPath);
        } catch (Exception e) {
            context.put("fileCount", 0);
            try {
                throw new ServiceException("SharePoint directory scan failed: " + e.getMessage());
            } catch (ServiceException ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Token resolution
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a valid Bearer token for the given account. If {@code forceRefresh} is
     * true (called after a mid-scan 401 or connection error), or if the stored token
     * is absent, a fresh token is obtained from the Microsoft token endpoint using the
     * SharePoint client-credentials flow and persisted back to the OAuthAccount.
     */
    private String resolveBearerToken(String accountId, boolean forceRefresh)
            throws IOException, InterruptedException, ServiceException {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId must not be blank");
        }
        OAuthAccount account = IndigoConfig.getEntity(accountId, OAuthAccount.class);
        if (account == null) {
            throw new IllegalArgumentException("No OAuthAccount found for ID: " + accountId);
        }

        String token = account.getAccessToken();
        if (forceRefresh || token == null || token.isBlank()) {
            return "Bearer " + generateSharePointToken(account);
        }
        return "Bearer " + token;
    }

    /**
     * Obtains a fresh access token from the Microsoft token endpoint using the
     * SharePoint OAuth 2.0 client-credentials flow, then persists the new token
     * and its expiry back to the OAuthAccount so subsequent calls avoid redundant
     * token requests.
     *
     * Uses certificate-based JWT assertion (RS256) when {@code certificateThumbprint}
     * and {@code privateKey} are present in the account parameters; falls back to
     * client-secret otherwise.
     */
    private String generateSharePointToken(OAuthAccount account)
            throws IOException, InterruptedException, ServiceException {

        String clientId     = PasswordService.getInstance().decryptWithoutException(account.getClientId());
        String clientSecret = PasswordService.getInstance().decryptWithoutException(account.getClientSecret());

        Map<String, String> params     = account.constructParametersMapFromXml();
        String              realmId    = params.get("realmId");
        String              baseurl    = params.get("baseurl");
        String              thumbprint = params.get("certificateThumbprint");
        String              privateKey = params.get("privateKey");

        if (baseurl == null || baseurl.isBlank()) {
            baseurl = account.getInstanceUrl();
        }
        if (realmId == null || realmId.isBlank()) {
            throw new IllegalStateException(
                    "SharePoint account is missing 'realmId' (Tenant ID) — check account configuration");
        }
        if (baseurl == null || baseurl.isBlank()) {
            throw new IllegalStateException(
                    "SharePoint account is missing 'baseurl' — check account configuration");
        }

        String tokenUrl = "https://login.microsoftonline.com/" + realmId + "/oauth2/v2.0/token";
        String scope    = baseurl + "/.default";

        StringBuilder body = new StringBuilder();
        body.append("grant_type=client_credentials");
        body.append("&client_id=").append(URLEncoder.encode(clientId, StandardCharsets.UTF_8));
        body.append("&scope=").append(URLEncoder.encode(scope, StandardCharsets.UTF_8));

        boolean useCert = thumbprint != null && !thumbprint.isBlank()
                && privateKey  != null && !privateKey.isBlank();
        if (useCert) {
            String jwt = buildClientAssertionJwt(clientId, thumbprint, privateKey, tokenUrl);
            body.append("&client_assertion_type=").append(URLEncoder.encode(
                    "urn:ietf:params:oauth:client-assertion-type:jwt-bearer", StandardCharsets.UTF_8));
            body.append("&client_assertion=").append(jwt);
        } else {
            body.append("&client_secret=").append(URLEncoder.encode(clientSecret, StandardCharsets.UTF_8));
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(tokenUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(HTTP_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IOException(
                    "Unable to reach Microsoft token endpoint — verify network connectivity "
                            + "and tenant configuration: " + e.getMessage(), e);
        }

        if (response.statusCode() != 200) {
            throw new IOException("SharePoint token request failed — HTTP "
                    + response.statusCode() + ": " + truncate(response.body(), 300));
        }

        JsonNode json  = MAPPER.readTree(response.body());
        String   token = json.path("access_token").asText(null);

        if (token == null || token.isBlank()) {
            throw new IOException(
                    "Token response missing access_token: " + truncate(response.body(), 300));
        }

        persistUpdatedToken(account, token);
        return token;
    }

    /**
     * Persists the freshly obtained access token back to the OAuthAccount entity
     * so that future calls to {@link #resolveBearerToken} can use the cached value
     * instead of hitting the token endpoint again.
     *
     * Failure here is non-fatal — the token is still valid in memory for the current
     * scan; the next scan will simply regenerate it if the entity was not updated.
     */
    private void persistUpdatedToken(OAuthAccount account, String plainToken) throws ServiceException {
        try {
            account.setAccessToken(plainToken);
            Subject          subject       = AuthUtil.getAdminSubject();
            JdoEntityManager entityManager =
                    (JdoEntityManager) EntityManagerFactory.getEntityManager(OAuthAccount.class, subject);
            entityManager.update(account);
        } catch (Exception ex) {
            throw new ServiceException(ex.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // JWT helpers (RS256 client assertion)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds an RS256-signed JWT client assertion as required by Microsoft's
     * {@code client_assertion} parameter.
     */
    private String buildClientAssertionJwt(String clientId, String thumbprint,
                                           String encodedPrivateKey, String tokenUrl) {
        Map<String, String> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("x5t", hexThumbprintToBase64(thumbprint));

        Map<String, String> claims = new LinkedHashMap<>();
        claims.put("iss", clientId);
        claims.put("sub", clientId);
        claims.put("aud", tokenUrl);
        claims.put("exp", Long.toString(System.currentTimeMillis() / 1000 + 360_000));

        String headerB64    = Base64.encodeBase64URLSafeString(toJson(header).getBytes(StandardCharsets.UTF_8));
        String claimsB64    = Base64.encodeBase64URLSafeString(toJson(claims).getBytes(StandardCharsets.UTF_8));
        String signingInput = headerB64 + "." + claimsB64;
        return signingInput + "." + signRsa256(signingInput, encodedPrivateKey);
    }

    private String hexThumbprintToBase64(String hexThumbprint) {
        try {
            byte[] decoded = Hex.decodeHex(hexThumbprint);
            return new String(Base64.encodeBase64(decoded)).replace("=", "");
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Invalid certificate thumbprint: " + e.getMessage(), e);
        }
    }

    private String signRsa256(String data, String encodedPrivateKey) {
        try {
            byte[]     keyBytes = DataDecoder.decodeData(encodedPrivateKey);
            PrivateKey key      = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
            Signature  sig      = Signature.getInstance("SHA256withRSA");
            sig.initSign(key);
            sig.update(data.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeBase64URLSafeString(sig.sign());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign JWT assertion: " + e.getMessage(), e);
        }
    }

    private String toJson(Map<String, String> map) {
        try {
            return MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize JSON: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal scan helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns true for errors that warrant a token refresh + single retry:
     * a 401 response (expired/invalid token) or a connection-level failure
     * (which can occur when an invalid token causes the server to drop the connection).
     */
    private static boolean shouldRetryWithFreshToken(IOException e) {
        String msg = e.getMessage();
        return msg != null && (msg.contains("401") || msg.contains("Unable to connect"));
    }

    private void collectFileNames(String baseUrl, String path, String bearerToken,
                                  boolean recursive, List<String> results)
            throws IOException, InterruptedException {

        int skip = 0;
        while (true) {
            String   url  = filesUrl(baseUrl, path, skip);
            JsonNode page = fetchValueArray(url, bearerToken);
            if (page == null || page.isEmpty()) break;

            String root = tenantRoot(baseUrl);
            for (JsonNode f : page) {
                results.add(root + encode(f.path("ServerRelativeUrl").asText()));
            }
            if (page.size() < PAGE_SIZE) break;
            skip += PAGE_SIZE;
        }

        if (recursive) {
            for (String subPath : listSubFolderPaths(baseUrl, path, bearerToken)) {
                collectFileNames(baseUrl, subPath, bearerToken, true, results);
            }
        }
    }

    private void collectFileInfos(String baseUrl, String path, String bearerToken,
                                  boolean recursive, List<FileInfo> results)
            throws IOException, InterruptedException {

        int skip = 0;
        while (true) {
            String   url  = filesUrl(baseUrl, path, skip);
            JsonNode page = fetchValueArray(url, bearerToken);
            if (page == null || page.isEmpty()) break;

            String root = tenantRoot(baseUrl);
            for (JsonNode f : page) {
                String serverRelativeUrl = f.path("ServerRelativeUrl").asText();
                String downloadUrl = root + encode(serverRelativeUrl);
                results.add(new FileInfo(
                        f.path("Name").asText(),
                        serverRelativeUrl,
                        f.path("Length").asLong(0),
                        f.path("TimeCreated").asText(),
                        f.path("TimeLastModified").asText(),
                        downloadUrl
                ));
            }
            if (page.size() < PAGE_SIZE) break;
            skip += PAGE_SIZE;
        }

        if (recursive) {
            for (String subPath : listSubFolderPaths(baseUrl, path, bearerToken)) {
                collectFileInfos(baseUrl, subPath, bearerToken, true, results);
            }
        }
    }

    private List<String> listSubFolderPaths(String baseUrl, String path, String bearerToken)
            throws IOException, InterruptedException {

        String url = baseUrl
                + "/_api/web/GetFolderByServerRelativeUrl('"
                + encode(path)
                + "')/Folders?$select=Name,ServerRelativeUrl&$top=" + PAGE_SIZE;

        JsonNode folders = fetchValueArray(url, bearerToken);
        if (folders == null || folders.isEmpty()) return Collections.emptyList();

        List<String> paths = new ArrayList<>();
        for (JsonNode folder : folders) {
            String name = folder.path("Name").asText();
            if ("Forms".equals(name)) continue;
            paths.add(folder.path("ServerRelativeUrl").asText());
        }
        return paths;
    }

    private String filesUrl(String baseUrl, String path, int skip) {
        return baseUrl
                + "/_api/web/GetFolderByServerRelativeUrl('"
                + encode(path)
                + "')/Files?"
                + SP_FILES_SELECT
                + "&$top=" + PAGE_SIZE
                + "&$skip=" + skip;
    }

    /**
     * Executes a GET request and returns the {@code value} JSON array from the response.
     * Returns {@code null} for 404 (folder does not exist).
     */
    private JsonNode fetchValueArray(String url, String bearerToken)
            throws IOException, InterruptedException {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", bearerToken)
                .header("Accept", ACCEPT_HEADER)
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IOException(
                    "Unable to connect to SharePoint at " + url
                            + " — verify the base URL is reachable and network connectivity is available: "
                            + e.getMessage(), e);
        }

        if (response.statusCode() == 404) return null;

        if (response.statusCode() == 401) {
            throw new IOException("SharePoint returned 401 Unauthorized — "
                    + "verify client credentials and that the app has Site.Read.All or AllSites.Read permission");
        }
        if (response.statusCode() != 200) {
            throw new IOException("SharePoint API error " + response.statusCode()
                    + " for URL " + url + " — " + truncate(response.body(), 300));
        }

        JsonNode root = MAPPER.readTree(response.body());
        return root.has("value") ? root.get("value") : null;
    }

    /**
     * Returns just the scheme+host of {@code baseUrl} (e.g. "https://tenant.sharepoint.com").
     * SharePoint's {@code ServerRelativeUrl} is always relative to the tenant root, not to
     * a subsite, so building download URLs from the full baseUrl (which may include a subsite
     * path like "/SG_Subsite") would double that segment and produce 404s.
     */
    private static String tenantRoot(String baseUrl) {
        URI uri = URI.create(baseUrl);
        return uri.getScheme() + "://" + uri.getHost();
    }

    /**
     * Percent-encodes each path segment individually so that '/' separators are
     * preserved verbatim. URLEncoder.encode on the full path would turn every '/'
     * into '%2F', which SharePoint REST rejects with a 400.
     */
    private static String encode(String path) {
        String[]      segments = path.split("/", -1);
        StringBuilder sb       = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(URLEncoder.encode(segments[i], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : s;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FileInfo — protocol-neutral file metadata record
    // ─────────────────────────────────────────────────────────────────────────

    public static final class FileInfo {

        /** File name without path — e.g. "Q1_Report.xlsx" */
        public final String name;

        /** Server-relative path — e.g. /SG_Subsite/Shared Documents/Target/Q1_Report.xlsx */
        public final String serverRelativeUrl;

        /** Direct download URL — tenant root + percent-encoded serverRelativeUrl; click to download the file */
        public final String downloadUrl;

        /** File size in bytes */
        public final long sizeBytes;

        /** ISO-8601 creation timestamp as returned by SharePoint */
        public final String timeCreated;

        /** ISO-8601 last-modified timestamp as returned by SharePoint */
        public final String timeLastModified;

        public FileInfo(String name, String serverRelativeUrl, long sizeBytes,
                        String timeCreated, String timeLastModified, String downloadUrl) {
            this.name              = name;
            this.serverRelativeUrl = serverRelativeUrl;
            this.sizeBytes         = sizeBytes;
            this.timeCreated       = timeCreated;
            this.timeLastModified  = timeLastModified;
            this.downloadUrl       = downloadUrl;
        }

        @Override
        public String toString() {
            return "FileInfo{name='" + name + "', downloadUrl='" + downloadUrl
                    + "', size=" + sizeBytes + ", created='" + timeCreated
                    + "', modified='" + timeLastModified + "'}";
        }
    }
}
