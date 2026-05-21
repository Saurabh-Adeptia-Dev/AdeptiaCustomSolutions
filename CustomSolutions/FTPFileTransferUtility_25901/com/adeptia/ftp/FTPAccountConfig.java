package com.adeptia.ftp;

import com.adeptia.indigo.security.AuthUtil;
import com.adeptia.indigo.services.transport.account.FTPAccount;
import com.adeptia.indigo.storage.EntityManager;
import com.adeptia.indigo.storage.EntityManagerFactory;
import com.adeptia.indigo.storage.TypedEntityId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FTPAccountConfig
 * ?????????????????
 * Resolves an Adeptia FTPAccount activity by its ID and exposes
 * all connection parameters in a protocol-neutral form.
 *
 * Supports both FTP/FTPS (port != 22) and SFTP (port == 22)
 * from a single FTPAccount activity ? protocol is auto-detected
 * based on port number and ftpType field.
 *
 * ?? FTPAccount field mapping ?????????????????????????????????????????????
 *
 *   FTPAccount field         ? FTPAccountConfig field
 *   ?????????????????????    ?????????????????????????
 *   getHostName()            ? host
 *   getPort()                ? port
 *   getFtpUserId()           ? username
 *   getPassword()            ? password
 *   getTransferType()        ? transferType  ("Passive" / "Active")
 *   getFtpType()             ? ftpType       ("FTP" / "FTPS" / "SFTP")
 *   getFtpsMode()            ? ftpsMode      ("Explicit" / "Implicit")
 *   getFtpProtectionLevel()  ? protectionLevel ("P" / "C")
 *   isFtpValidateServer()    ? validateServer
 *   getKeyManager()          ? keyManager
 *
 * ?? Protocol detection logic ?????????????????????????????????????????????
 *
 *   Port 22           ? SFTP
 *   ftpType = "SFTP"  ? SFTP
 *   ftpType = "FTPS"  ? FTPS (useSSL = true)
 *   otherwise         ? FTP  (useSSL = false)
 *
 * ?? Usage ????????????????????????????????????????????????????????????????
 *
 *   FTPAccountConfig src = FTPAccountConfig.resolve("activityId123", "SOURCE");
 *   FTPAccountConfig tgt = FTPAccountConfig.resolve("activityId456", "TARGET");
 *
 *   // Use in FTPConnectionManager
 *   FTPConnectionManager mgr = new FTPConnectionManager(src, "SOURCE");
 */
public class FTPAccountConfig {

    private static final Logger LOG = LoggerFactory.getLogger(FTPAccountConfig.class);

    // ?? Connection fields ?????????????????????????????????????????????????
    public String  activityId;
    public String  host;
    public int     port;
    public String  username;
    public String  password;
    public String  transferType;    // "Passive" or "Active"
    public String  ftpType;         // "FTP", "FTPS", "SFTP"
    public String  ftpsMode;        // "Explicit", "Implicit"
    public String  protectionLevel; // "P" (Private), "C" (Clear)
    public boolean validateServer;
    public String  keyManager;
    public String  preferredAuthentications;
    public String  addOnConfigurations;

    // ?? Derived protocol flags ????????????????????????????????????????????
    public boolean isSFTP;     // true ? SFTP via Camel SftpOperations
    public boolean useSSL;     // true ? FTPS via Camel FtpOperations with TLS
    public boolean isImplicit; // true ? FTPS implicit mode (port 990)

    // Internal aliases for fromRawParams builder
    private String  _host; private int _port; private String _username;
    private String  _password; private String _transferType;
    private String  _ftpType; private String _ftpsMode;
    private boolean _isSFTP; private boolean _useSSL; private boolean _isImplicit;

    // ?? Private no-arg constructor ? for fromRawParams builder ????????????
    private FTPAccountConfig() {}

    // ?? Private constructor ? use resolve() factory method ????????????????
    private FTPAccountConfig(FTPAccount account, String activityId) {
        this.activityId             = activityId;
        this.host                   = account.getHostName();
        this.port                   = account.getPort();
        this.username               = account.getFtpUserId();
        this.password               = account.getPassword();
        this.transferType           = account.getTransferType()     != null
                ? account.getTransferType()  : "Passive";
        this.ftpType                = account.getFtpType()          != null
                ? account.getFtpType()       : "FTP";
        this.ftpsMode               = account.getFtpsMode()         != null
                ? account.getFtpsMode()      : "Explicit";
        this.protectionLevel        = account.getFtpProtectionLevel() != null
                ? account.getFtpProtectionLevel() : "P";
        this.validateServer         = account.isFtpValidateServer();
        this.keyManager             = account.getKeyManager().replace("KeyManager:", "");
        this.preferredAuthentications = account.getPreferredAuthentications();
        this.addOnConfigurations    = account.getAddOnConfigurations();

        // ?? Derive protocol flags ?????????????????????????????????????????
        this.isSFTP     = (port == 22) || "SFTP".equalsIgnoreCase(ftpType);
        this.useSSL     = !isSFTP && "FTPS".equalsIgnoreCase(ftpType);
        this.isImplicit = useSSL && "Implicit".equalsIgnoreCase(ftpsMode);
    }

    // ?????????????????????????????????????????????????????????????????????
    // Factory Method ? resolve from Adeptia FTPAccount activity
    // ?????????????????????????????????????????????????????????????????????

    /**
     * Resolves an FTPAccount activity by its ID and returns a populated config.
     *
     * @param activityId  Adeptia FTPAccount activity ID
     * @param role        "SOURCE" or "TARGET" ? for log messages only
     * @return            Populated FTPAccountConfig
     * @throws Exception  if activity not found or retrieval fails
     */
    public static FTPAccountConfig resolve(String activityId, String role) throws Exception {
        LOG.info("[{}] Resolving FTPAccount activity [{}].", role, activityId);

        EntityManager em = EntityManagerFactory.getEntityManager(
                FTPAccount.class, AuthUtil.getAdminSubject());

        FTPAccount account = (FTPAccount) em.retrieve(
                new TypedEntityId(activityId, "FTPAccount"));

        if (account == null) {
            throw new IllegalArgumentException(
                    "FTPAccount activity not found: [" + activityId + "]");
        }

        FTPAccountConfig config = new FTPAccountConfig(account, activityId);

        LOG.info("[{}] FTPAccount resolved. host={} port={} user={} protocol={} transferType={}",
                role,
                config.host,
                config.port,
                config.username,
                config.getProtocolName(),
                config.transferType);

        return config;
    }

    // ?????????????????????????????????????????????????????????????????????
    // Helpers
    // ?????????????????????????????????????????????????????????????????????

    /**
     * Returns a human-readable protocol name for logging.
     * e.g. "SFTP", "FTPS (Explicit)", "FTPS (Implicit)", "FTP"
     */
    public String getProtocolName() {
        if (isSFTP)     return "SFTP";
        if (useSSL)     return "FTPS (" + ftpsMode + ")";
        return "FTP";
    }

    /**
     * Returns true if source and target configs point to the same server.
     * Used to decide whether to use a single shared connection.
     *
     * Comparison: host (case-insensitive) + port + username
     */
    public boolean isSameServer(FTPAccountConfig other) {
        return this.host.equalsIgnoreCase(other.host)
                && this.port == other.port
                && this.username.equalsIgnoreCase(other.username);
    }

    /**
     * Creates a minimal FTPAccountConfig from raw parameters.
     * Used as fallback in FTPConnectionManager when no FTPAccount activity is available.
     */
    public static FTPAccountConfig fromRawParams(
            String host, int port, String username, String password,
            boolean useSSL, String transferType) {

        // Build a minimal FTPAccount-like object using a synthetic config
        FTPAccountConfig cfg = new FTPAccountConfig();
        cfg.activityId    = "raw";
        cfg.host          = host;
        cfg.port          = port;
        cfg.username      = username;
        cfg.password      = password;
        cfg.transferType  = transferType != null ? transferType : "Passive";
        cfg.ftpType       = useSSL ? "FTPS" : (port == 22 ? "SFTP" : "FTP");
        cfg.ftpsMode      = "Explicit";
        cfg.protectionLevel = "P";
        cfg.isSFTP        = (port == 22);
        cfg.useSSL        = useSSL && port != 22;
        cfg.isImplicit    = false;
        return cfg;
    }


    // ?????????????????????????????????????????????????????????????????????
    // Factory ? build from raw params (no Adeptia activity lookup)
    // ?????????????????????????????????????????????????????????????????????

    /**
     * Builds an FTPAccountConfig from raw connection parameters.
     * Used as fallback when FTPConnectionManager is constructed without
     * an FTPAccountConfig (raw params constructor).
     *
     * @param host          FTP/SFTP server hostname
     * @param port          port (21 = FTP/FTPS, 22 = SFTP)
     * @param username      login user
     * @param password      login password
     * @param useSSL        true = FTPS
     * @param transferType  "Passive" or "Active"
     * @return              populated FTPAccountConfig
     */
    public static FTPAccountConfig fromRaw(String  host,     int    port,
                                           String  username, String password,
                                           boolean useSSL,   String transferType) {
        // Build a minimal FTPAccount-like object via anonymous subclass
        // so we can reuse the private constructor
        FTPAccount stub = new FTPAccount();
        stub.setHostName(host);
        stub.setPort(port);
        stub.setFtpUserId(username);
        stub.setPassword(password);
        stub.setTransferType(transferType);
        // Derive ftpType from port + useSSL
        if (port == 22) {
            stub.setFtpType("SFTP");
        } else if (useSSL) {
            stub.setFtpType("FTPS");
        } else {
            stub.setFtpType("FTP");
        }
        stub.setFtpsMode("Explicit");
        stub.setFtpProtectionLevel("P");
        return new FTPAccountConfig(stub, "raw");
    }

    @Override
    public String toString() {
        return String.format("FTPAccountConfig{host=%s, port=%d, user=%s, protocol=%s, transferType=%s}",
                host, port, username, getProtocolName(), transferType);
    }
}