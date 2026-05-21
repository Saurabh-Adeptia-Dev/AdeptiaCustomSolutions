package com.adeptia.ftp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FTPFileDeleter
 * ==============
 * Connects to an FTP / FTPS / SFTP server and deletes a file at a remote path.
 *
 * Accepted activity ID types -- pass either, resolve() handles both:
 *
 *   FTPAccount activity ID  -- reusable connector (Services > Transport > FTP Account)
 *   FTPEvent  activity ID   -- trigger/source event, typical in plugin activities
 *                              (context.get("eID") in BeanShell custom plugin)
 *                              If the FTPEvent references an FTPAccount the
 *                              FTPAccount fields are used automatically.
 *
 * Supported authentication modes (auto-selected from the activity):
 *
 *   FTP / FTPS             -- username + password
 *   SFTP + password        -- username + password; no Key Manager configured
 *   SFTP + Key Manager     -- SSH private key only; password suppressed so
 *                             key-only servers (e.g. Azure SFTP) do not reject
 *   SFTP + Key Manager     -- Key Manager wins; password on activity suppressed
 *     AND password
 *   SFTP + no password,    -- Fails with a clear warning in the log telling you
 *     no Key Manager          to set one or the other
 *
 * Protocol auto-detected:
 *   port == 22  OR  SFTP flag  ->  SFTP  (JSch / SftpRemoteHandler)
 *   FTPS flag                  ->  FTPS  (Apache Commons Net / FtpRemoteHandler)
 *   otherwise                  ->  FTP   (Apache Commons Net / FtpRemoteHandler)
 *
 * BeanShell usage -- custom plugin activity:
 *
 *   import com.adeptia.ftp.FTPFileDeleter;
 *
 *   // Using FTPEvent ID from plugin context (most common in plugin activities)
 *   String eID = (String) context.get("eID");
 *   boolean ok = FTPFileDeleter.deleteFile(eID, "/outbox/report.csv");
 *
 *   // Using FTPAccount activity ID directly
 *   boolean ok = FTPFileDeleter.deleteFile("1463129338641494016", "/outbox/report.csv");
 *
 *   // Using directory + filename separately
 *   boolean ok = FTPFileDeleter.deleteFileInDir(eID, "/outbox", "report.csv");
 *
 *   // With a custom log label visible in the Adeptia log
 *   boolean ok = FTPFileDeleter.deleteFile(eID, "/outbox/report.csv", "POST-PROCESS-CLEANUP");
 */
public class FTPFileDeleter {

    private static final Logger LOG = LoggerFactory.getLogger(FTPFileDeleter.class);

    private FTPFileDeleter() {}

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Connects to the server identified by {@code ftpAccountId} and deletes
     * the file at {@code remoteFilePath}.
     *
     * Works for FTP, FTPS, and SFTP with either password or Key Manager auth.
     * For SFTP with a Key Manager configured on the activity, the private key
     * is loaded and password is suppressed automatically.
     *
     * @param ftpAccountId   Adeptia FTPAccount activity ID
     * @param remoteFilePath Full remote path, e.g. "/outbox/report.csv"
     * @return               {@code true} if the file was deleted successfully
     * @throws Exception     if the activity cannot be resolved or the connection fails
     */
    public static boolean deleteFile(String ftpAccountId,
                                     String remoteFilePath) throws Exception {
        return deleteFile(ftpAccountId, remoteFilePath, "DELETE");
    }

    /**
     * Same as {@link #deleteFile(String, String)} but with a custom log-role
     * label that appears in the Adeptia log for every message from this call
     * (e.g. "[CLEANUP]", "[ARCHIVE-DELETE]").
     *
     * @param ftpAccountId   Adeptia FTPAccount activity ID
     * @param remoteFilePath Full remote path, e.g. "/outbox/report.csv"
     * @param role           Log label, e.g. "CLEANUP" or "ARCHIVE-DELETE"
     * @return               {@code true} if the file was deleted successfully
     * @throws Exception     if the activity cannot be resolved or the connection fails
     */
    public static boolean deleteFile(String eventId,
                                     String remoteFilePath,
                                     String role) throws Exception {

        if (eventId == null || eventId.trim().isEmpty()) {
            throw new IllegalArgumentException("ftpAccountId must not be null or empty.");
        }
        if (remoteFilePath == null || remoteFilePath.trim().isEmpty()) {
            throw new IllegalArgumentException("remoteFilePath must not be null or empty.");
        }
        String effectiveRole = (role != null && !role.trim().isEmpty()) ? role : "DELETE";

        LOG.info("[{}] Resolving FTPAccount [{}] for remote file deletion.",
                effectiveRole, eventId);

        FTPAccountConfig config = FTPAccountConfig.resolve(eventId, effectiveRole);


        logConnectionPlan(config, remoteFilePath, effectiveRole);

        try (FTPConnectionManager mgr = new FTPConnectionManager(config, effectiveRole)) {
            mgr.connect();

            IFileHandler handler = mgr.getConnection();
            boolean deleted = handler.deleteFile(remoteFilePath);

            if (deleted) {
                LOG.info("[{}] Deleted [{}] from [{}:{}] via {}.",
                        effectiveRole, remoteFilePath,
                        config.host, config.port, config.getProtocolName());
            } else {
                LOG.warn("[{}] Could not delete [{}] from [{}:{}] via {}. "
                                + "File may not exist or permissions are insufficient.",
                        effectiveRole, remoteFilePath,
                        config.host, config.port, config.getProtocolName());
            }

            return deleted;
        }
    }

    /**
     * Connects to the server identified by {@code ftpAccountId} and deletes
     * the file {@code fileName} inside {@code remoteDir}.
     *
     * The directory and filename are joined with "/" before sending to the
     * server.  Equivalent to:
     * {@code deleteFile(ftpAccountId, remoteDir + "/" + fileName)}.
     *
     * @param ftpAccountId Adeptia FTPAccount activity ID
     * @param remoteDir    Remote directory, e.g. "/outbox"
     * @param fileName     Filename only, e.g. "report.csv"
     * @return             {@code true} if the file was deleted successfully
     * @throws Exception   if the activity cannot be resolved or the connection fails
     */
    public static boolean deleteFileInDir(String ftpAccountId,
                                          String remoteDir,
                                          String fileName) throws Exception {
        String dir  = (remoteDir != null) ? remoteDir : "/";
        if (!dir.endsWith("/")) dir = dir + "/";
        String path = dir + (fileName != null ? fileName : "");
        return deleteFile(ftpAccountId, path, "DELETE");
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Logs a human-readable summary of which auth mode will be used before
     * the connection is opened, so the Adeptia log makes it clear what is
     * happening without having to dig into JSch or Apache Net logs.
     */
    private static void logConnectionPlan(FTPAccountConfig config,
                                          String remoteFilePath,
                                          String role) {
        String authMode;
        if (config.isSFTP) {
            boolean hasKey = config.keyManager != null && !config.keyManager.trim().isEmpty();
            boolean hasPwd = config.password   != null && !config.password.trim().isEmpty();

            if (hasKey && hasPwd) {
                authMode = "publickey (Key Manager=" + config.keyManager
                        + ") [password present on activity but suppressed]";
            } else if (hasKey) {
                authMode = "publickey (Key Manager=" + config.keyManager + ") [no password]";
            } else if (hasPwd) {
                authMode = "password";
            } else {
                authMode = "password [WARNING: no password and no Key Manager configured]";
            }
        } else {
            authMode = "username/password";
        }

        LOG.info("[{}] Will connect {} to [{}:{}] user=[{}] auth={} -> delete [{}].",
                role,
                config.getProtocolName(),
                config.host, config.port,
                config.username,
                authMode,
                remoteFilePath);
    }
}
