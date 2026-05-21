package com.adeptia.ftp;

import com.adeptia.indigo.event.ftptrigger.FTPEvent;
import com.adeptia.indigo.security.AuthUtil;
import com.adeptia.indigo.services.transport.account.FTPAccount;
import com.adeptia.indigo.storage.EntityManager;
import com.adeptia.indigo.storage.EntityManagerFactory;
import com.adeptia.indigo.storage.TypedEntityId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FTPAccountConfig
 * ================
 * Resolves an Adeptia FTP activity by its ID and exposes all connection
 * parameters in a protocol-neutral form consumed by FTPConnectionManager.
 *
 * Accepted activity ID types (resolve() tries both automatically):
 *
 *   FTPAccount  -- reusable connector activity
 *                  (Services > Transport > FTP Account)
 *   FTPEvent    -- trigger/source event activity used in process flows
 *                  and accessible from custom plugin / BeanShell activities
 *                  (Services > Event > FTP)
 *                  If the FTPEvent references an FTPAccount via getAccountId(),
 *                  the FTPAccount fields take precedence.
 *
 * Field mapping -- FTPAccount
 *   getHostName()             -> host
 *   getPort()                 -> port
 *   getFtpUserId()            -> username
 *   getPassword()             -> password
 *   getTransferType()         -> transferType  ("Passive" / "Active")
 *   getFtpType()              -> ftpType       ("FTP" / "FTPS" / "SFTP")
 *   getFtpsMode()             -> ftpsMode      ("Explicit" / "Implicit")
 *   getFtpProtectionLevel()   -> protectionLevel ("P" / "C")
 *   isFtpValidateServer()     -> validateServer
 *   getKeyManager()           -> keyManager    (activity ID, prefix stripped)
 *   getPreferredAuthentications() -> preferredAuthentications
 *   getAddOnConfigurations()  -> addOnConfigurations
 *
 * Field mapping -- FTPEvent (different method names!)
 *   getHostName()             -> host
 *   getPort()                 -> port
 *   getFtpUserId()            -> username
 *   getFtpPassword()          -> password      (NOTE: different from FTPAccount)
 *   getTransferType()         -> transferType
 *   getSecured()              -> true = SFTP   (NOTE: different from FTPAccount)
 *   getFtpOverSSL()           -> true = FTPS   (NOTE: different from FTPAccount)
 *   getFtpsMode()             -> ftpsMode
 *   getFtpProtectionLevel()   -> protectionLevel (normalized: "Private"->"P", "Clear"->"C")
 *   isFtpValidateServer()     -> validateServer
 *   getKeyManager()           -> keyManager
 *   getPreferredAuthentications() -> preferredAuthentications
 *   getAddOnConfigurations()  -> addOnConfigurations
 *
 * Protocol detection:
 *   port == 22  OR  isSFTP flag  ->  SFTP
 *   useSSL flag                  ->  FTPS (explicit or implicit)
 *   otherwise                    ->  FTP
 */
public class FTPAccountConfig {

    private static final Logger LOG = LoggerFactory.getLogger(FTPAccountConfig.class);

    // Connection fields
    public String  activityId;
    public String  host;
    public int     port;
    public String  username;
    public String  password;
    public String  transferType;      // "Passive" or "Active"
    public String  ftpType;           // "FTP", "FTPS", "SFTP"
    public String  ftpsMode;          // "Explicit", "Implicit"
    public String  protectionLevel;   // "P" (Private), "C" (Clear)
    public boolean validateServer;
    public String  keyManager;
    public String  preferredAuthentications;
    public String  addOnConfigurations;

    // Derived protocol flags
    public boolean isSFTP;      // true -> SFTP (JSch via SftpRemoteHandler)
    public boolean useSSL;      // true -> FTPS (Apache Commons Net / FtpRemoteHandler)
    public boolean isImplicit;  // true -> FTPS Implicit mode (port 990)

    private FTPAccountConfig() {}

    // =========================================================================
    // Private constructors -- use factory methods
    // =========================================================================

    /** Populate from FTPAccount activity. */
    private FTPAccountConfig(FTPAccount account, String activityId) {
        this.activityId   = activityId;
        this.host         = account.getHostName();
        this.port         = account.getPort();
        this.username     = account.getFtpUserId();
        this.password     = account.getPassword();
        this.transferType = account.getTransferType()       != null
                ? account.getTransferType()       : "Passive";
        this.ftpType      = account.getFtpType()            != null
                ? account.getFtpType()            : "FTP";
        this.ftpsMode     = account.getFtpsMode()           != null
                ? account.getFtpsMode()           : "Explicit";
        this.protectionLevel = account.getFtpProtectionLevel() != null
                ? account.getFtpProtectionLevel() : "P";
        this.validateServer  = account.isFtpValidateServer();

        String rawKm = account.getKeyManager();
        this.keyManager = (rawKm != null) ? rawKm.replace("KeyManager:", "").trim() : "";

        this.preferredAuthentications = account.getPreferredAuthentications();
        this.addOnConfigurations      = account.getAddOnConfigurations();

        deriveProtocolFlags();
    }

    /**
     * Populate from FTPEvent activity.
     *
     * FTPEvent exposes connection fields differently from FTPAccount:
     *   - password  : getFtpPassword()  (not getPassword())
     *   - SFTP flag : getSecured()      (not getFtpType())
     *   - FTPS flag : getFtpOverSSL()   (not getFtpType())
     *   - protection: "Private"/"Clear" (not "P"/"C") -- normalized here
     */
    private FTPAccountConfig(FTPEvent event, String activityId) {
        this.activityId   = activityId;
        this.host         = event.getHostName();
        this.port         = event.getPort();
        this.username     = event.getFtpUserId();
        this.password     = event.getFtpPassword();
        this.transferType = event.getTransferType()  != null
                ? event.getTransferType()  : "Passive";
        this.ftpsMode     = event.getFtpsMode()      != null
                ? event.getFtpsMode()      : "Explicit";

        // FTPEvent uses getSecured() = SFTP and getFtpOverSSL() = FTPS
        // instead of a single getFtpType() field
        boolean sftp = event.getSecured();
        boolean ftps = !sftp && event.getFtpOverSSL();
        this.ftpType = sftp ? "SFTP" : (ftps ? "FTPS" : "FTP");

        // FTPEvent protection level may be "Private", "Clear", or "None"
        // Normalize to the "P"/"C" values expected by FtpRemoteHandler
        this.protectionLevel = normalizeProtectionLevel(event.getFtpProtectionLevel());

        this.validateServer = event.isFtpValidateServer();

        String rawKm = event.getKeyManager();
        this.keyManager = (rawKm != null) ? rawKm.replace("KeyManager:", "").trim() : "";

        // PreferredAuthentications / addOnConfigurations may not exist on all
        // FTPEvent versions -- guard with try/catch for forward compatibility
        try { this.preferredAuthentications = event.getPreferredAuthentications(); } catch (Exception ignored) {}
        try { this.addOnConfigurations      = event.getAddOnConfigurations();      } catch (Exception ignored) {}

        deriveProtocolFlags();
    }

    /** Sets isSFTP / useSSL / isImplicit from ftpType + port. */
    private void deriveProtocolFlags() {
        this.isSFTP     = (port == 22) || "SFTP".equalsIgnoreCase(ftpType);
        this.useSSL     = !isSFTP && "FTPS".equalsIgnoreCase(ftpType);
        this.isImplicit = useSSL && "Implicit".equalsIgnoreCase(ftpsMode);
    }

    // =========================================================================
    // Factory -- resolve() : accepts FTPAccount OR FTPEvent activity ID
    // =========================================================================

    /**
     * Resolves an FTP activity by ID.  Tries FTPAccount first; if not found,
     * falls back to FTPEvent.  If the FTPEvent has an accountId that points to
     * an FTPAccount, that FTPAccount's fields are used (they are more complete).
     *
     * This means the same call works for:
     *   - An FTPAccount activity ID  (Services > Transport > FTP Account)
     *   - An FTPEvent activity ID    (Services > Event > FTP, or plugin context eID)
     *
     * BeanShell plugin example -- get eID from context and pass directly:
     *   String eID = (String) context.get("eID");
     *   FTPAccountConfig cfg = FTPAccountConfig.resolve(eID, "DELETE");
     *
     * @param activityId  FTPAccount or FTPEvent activity ID
     * @param role        Label for log messages only ("SOURCE", "TARGET", "DELETE", etc.)
     * @return            Populated FTPAccountConfig
     * @throws Exception  if the activity is not found as either type
     */
    public static FTPAccountConfig resolve(String activityId, String role) throws Exception {

        // -- Try FTPAccount first -------------------------------------------------
 //       FTPAccount account = tryLoadFTPAccount(activityId, role);
//        if (account != null) {
//            FTPAccountConfig cfg = new FTPAccountConfig(account, activityId);
//            LOG.info("[{}] Resolved FTPAccount [{}]. host={} port={} user={} protocol={}",
//                    role, activityId, cfg.host, cfg.port, cfg.username, cfg.getProtocolName());
//            return cfg;
//        }

        // -- Fallback: try FTPEvent -----------------------------------------------
     //   LOG.info("[{}] [{}] not found as FTPAccount. Trying FTPEvent...", role, activityId);
        FTPEvent event = tryLoadFTPEvent(activityId, role);
        if (event == null) {
            throw new IllegalArgumentException(
                    "[" + role + "] Activity [" + activityId + "] not found as FTPAccount or FTPEvent.");
        }

        // If the FTPEvent references an FTPAccount via accountId, load that
        // FTPAccount -- it carries the full connector config (transferType, ftpType, etc.)
        // accountId may be stored as "FTPAccount:1234" -- strip the prefix before lookup
        String rawAccountId = event.getAccountId();
        String accountId = (rawAccountId != null)
                ? rawAccountId.replace("FTPAccount:", "").trim() : null;
        if (accountId != null && !accountId.isEmpty()) {
            LOG.info("[{}] FTPEvent [{}] references FTPAccount [{}]. Loading FTPAccount.",
                    role, activityId, accountId);
            FTPAccount referenced = tryLoadFTPAccount(accountId, role);
            if (referenced != null) {
                FTPAccountConfig cfg = new FTPAccountConfig(referenced, accountId);
                if (cfg.host != null && !cfg.host.trim().isEmpty()) {
                    LOG.info("[{}] Resolved via FTPEvent->FTPAccount [{}]. "
                                    + "host={} port={} user={} protocol={}",
                            role, accountId, cfg.host, cfg.port, cfg.username, cfg.getProtocolName());
                    return cfg;
                }
                LOG.warn("[{}] FTPAccount [{}] has blank host. Falling back to FTPEvent fields.",
                        role, accountId);
            } else {
                LOG.warn("[{}] FTPEvent [{}] references FTPAccount [{}] but it was not found. "
                        + "Falling back to FTPEvent fields.", role, activityId, accountId);
            }
        }

        // Use FTPEvent fields directly
        FTPAccountConfig cfg = new FTPAccountConfig(event, activityId);
        LOG.info("[{}] Resolved FTPEvent [{}]. host={} port={} user={} protocol={}",
                role, activityId, cfg.host, cfg.port, cfg.username, cfg.getProtocolName());
        return cfg;
    }

    /**
     * Resolves an FTPEvent activity ID explicitly.
     * Use this when you are certain the ID is an FTPEvent (e.g. from context.get("eID")).
     * Prefer {@link #resolve(String, String)} if you are unsure of the activity type.
     *
     * @param ftpEventId  Adeptia FTPEvent activity ID
     * @param role        Label for log messages only
     * @return            Populated FTPAccountConfig
     * @throws Exception  if the FTPEvent is not found
     */
    public static FTPAccountConfig resolveFromEvent(String ftpEventId, String role) throws Exception {
        LOG.info("[{}] Resolving FTPEvent [{}] explicitly.", role, ftpEventId);

        FTPEvent event = tryLoadFTPEvent(ftpEventId, role);
        if (event == null) {
            throw new IllegalArgumentException(
                    "[" + role + "] FTPEvent [" + ftpEventId + "] not found.");
        }

        // If the event has an accountId, load the FTPAccount (preferred)
        // accountId may be stored as "FTPAccount:1234" -- strip the prefix before lookup
        String rawAccountId = event.getAccountId();
        String accountId = (rawAccountId != null)
                ? rawAccountId.replace("FTPAccount:", "").trim() : null;
        if (accountId != null && !accountId.isEmpty()) {
            FTPAccount account = tryLoadFTPAccount(accountId, role);
            if (account != null) {
                FTPAccountConfig cfg = new FTPAccountConfig(account, accountId);
                if (cfg.host != null && !cfg.host.trim().isEmpty()) {
                    LOG.info("[{}] FTPEvent [{}] -> FTPAccount [{}]. host={} port={} protocol={}",
                            role, ftpEventId, accountId, cfg.host, cfg.port, cfg.getProtocolName());
                    return cfg;
                }
                LOG.warn("[{}] FTPAccount [{}] has blank host. Falling back to FTPEvent fields.",
                        role, accountId);
            }
        }

        FTPAccountConfig cfg = new FTPAccountConfig(event, ftpEventId);
        LOG.info("[{}] FTPEvent [{}] resolved directly. host={} port={} protocol={}",
                role, ftpEventId, cfg.host, cfg.port, cfg.getProtocolName());
        return cfg;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Returns a human-readable protocol name, e.g. "SFTP", "FTPS (Explicit)", "FTP". */
    public String getProtocolName() {
        if (isSFTP)  return "SFTP";
        if (useSSL)  return "FTPS (" + ftpsMode + ")";
        return "FTP";
    }

    /** True if both configs point at the same server (host + port + user). */
    public boolean isSameServer(FTPAccountConfig other) {
        return this.host.equalsIgnoreCase(other.host)
                && this.port == other.port
                && this.username.equalsIgnoreCase(other.username);
    }

    // =========================================================================
    // Factory -- fromRawParams (no Adeptia activity lookup)
    // =========================================================================

    /** Builds a minimal config from raw parameters -- no entity lookup. */
    public static FTPAccountConfig fromRawParams(
            String host, int port, String username, String password,
            boolean useSSL, String transferType) {

        FTPAccountConfig cfg = new FTPAccountConfig();
        cfg.activityId      = "raw";
        cfg.host            = host;
        cfg.port            = port;
        cfg.username        = username;
        cfg.password        = password;
        cfg.transferType    = transferType != null ? transferType : "Passive";
        cfg.ftpType         = useSSL ? "FTPS" : (port == 22 ? "SFTP" : "FTP");
        cfg.ftpsMode        = "Explicit";
        cfg.protectionLevel = "P";
        cfg.keyManager      = "";
        cfg.isSFTP          = (port == 22);
        cfg.useSSL          = useSSL && port != 22;
        cfg.isImplicit      = false;
        return cfg;
    }

    @Override
    public String toString() {
        return String.format(
                "FTPAccountConfig{host=%s, port=%d, user=%s, protocol=%s, transferType=%s, keyManager=%s}",
                host, port, username, getProtocolName(), transferType,
                (keyManager != null && !keyManager.isEmpty()) ? keyManager : "(none)");
    }

    // =========================================================================
    // Private entity-loading helpers
    // =========================================================================

    /**
     * Attempts to load an FTPAccount by ID.
     * Returns null if not found -- does NOT throw.
     */
    private static FTPAccount tryLoadFTPAccount(String activityId, String role) {
        try {
            EntityManager em = EntityManagerFactory.getEntityManager(
                    FTPAccount.class, AuthUtil.getAdminSubject());
            FTPAccount account = (FTPAccount) em.retrieve(
                    new TypedEntityId(activityId, "FTPAccount"));
            if (account != null) {
                LOG.debug("[{}] Loaded FTPAccount [{}].", role, activityId);
            }
            return account;
        } catch (Exception e) {
            LOG.warn("[{}] FTPAccount lookup for [{}] failed ({}): {}",
                    role, activityId, e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    /**
     * Attempts to load an FTPEvent by ID.
     * Returns null if not found -- does NOT throw.We
     */
    private static FTPEvent tryLoadFTPEvent(String activityId, String role) {
        try {
            EntityManager em = EntityManagerFactory.getEntityManager(
                    FTPEvent.class, AuthUtil.getAdminSubject());
            FTPEvent event = (FTPEvent) em.retrieve(
                    new TypedEntityId(activityId, "FTPEvent"));
            if (event != null) {
                LOG.debug("[{}] Loaded FTPEvent [{}].", role, activityId);
            }
            return event;
        } catch (Exception e) {
            LOG.warn("[{}] FTPEvent lookup for [{}] failed ({}): {}",
                    role, activityId, e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    /**
     * Normalizes FTPEvent protection level strings to the "P"/"C" values
     * expected by FtpRemoteHandler.execPROT().
     *
     *   "Private"  -> "P"
     *   "Clear"    -> "C"
     *   "None"     -> "C"
     *   "P" / "C"  -> unchanged (already correct)
     *   null       -> "P"  (default: private/encrypted)
     */
    private static String normalizeProtectionLevel(String raw) {
        if (raw == null) return "P";
        return switch (raw.trim()) {
            case "Private"       -> "P";
            case "Clear", "None" -> "C";
            default              -> raw.trim(); // already "P" or "C"
        };
    }
}
