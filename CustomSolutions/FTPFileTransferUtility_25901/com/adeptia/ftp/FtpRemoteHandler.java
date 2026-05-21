package com.adeptia.ftp;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

/**
 * FTPFileHandler
 * ??????????????
 * Wraps Apache Commons Net FTPClient / FTPSClient.
 * Implements AutoCloseable for try-with-resources usage.
 *
 * New in this version:
 *   - sendNoOp()      : lightweight NOOP ping used by FTPConnectionManager
 *                       to check if connection is still alive
 *   - setKeepAlive()  : configures periodic NOOP to prevent idle timeout
 *
 * Connection config sourced from Adeptia FTPEvent / FtpTarget activities:
 *   - host         : ftpEvent.getHostName()
 *   - port         : ftpEvent.getPort()
 *   - username     : ftpEvent.getFtpUserId()
 *   - password     : ftpEvent.getFtpPassword()
 *   - useSSL       : ftpEvent.getFtpOverSSL()
 *   - transferType : ftpEvent.getTransferType()  ?  "Passive" or "Active"
 *
 * ?? Connection Safety Rules ??????????????????????????????????????????????
 *
 *   1. Use via FTPConnectionManager ? handles timeout and reconnect
 *   2. completePendingCommand() MUST be called after every readFile()
 *   3. disconnect() is idempotent ? safe to call multiple times
 *   4. writeFile() deletes existing target before writing (overwrite support)
 *   5. PrintCommandListener intentionally NOT added ? avoids log pollution
 *
 * ?? Two FTP Channels ????????????????????????????????????????????????????
 *
 *   CONTROL CHANNEL (port 21) ? open for the full session
 *     Sends : USER, PASS, NOOP, PASV/PORT, LIST, RETR, STOR, DELE, QUIT
 *
 *   DATA CHANNEL (dynamic port) ? opens/closes per operation
 *     Used  : actual file bytes and directory listings
 */
public class FtpRemoteHandler implements IFileHandler {

    private static final Logger LOG = LoggerFactory.getLogger(FtpRemoteHandler.class);

    // ?? Fields ????????????????????????????????????????????????????????????
    private FTPClient     ftpClient;
    private boolean       connected  = false;
    private boolean       closed     = false;

    private final String  host;
    private final int     port;
    private final String  username;
    private final String  password;
    private final boolean useSSL;
    private final boolean isImplicit;
    private final String  protectionLevel;
    private final String  transferType;

    // ?? Transfer Type Constants ???????????????????????????????????????????
    public static final String TRANSFER_TYPE_PASSIVE = "Passive";
    public static final String TRANSFER_TYPE_ACTIVE  = "Active";

    // ?? FTP Reply Code Constants ??????????????????????????????????????????
    private static final int REPLY_NOT_AVAILABLE   = 421;
    private static final int REPLY_LOGIN_INCORRECT = 530;
    private static final int REPLY_NEED_SECURITY   = 534;

    // ?? Timeout Constants ?????????????????????????????????????????????????
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int DEFAULT_TIMEOUT_MS = 60_000;
    private static final int DATA_TIMEOUT_MS    = 60_000;

    // ?? Buffer ????????????????????????????????????????????????????????????
    private static final int BUFFER_SIZE = 8192;

    // ?? Constructor ???????????????????????????????????????????????????????

    /**
     * Preferred constructor -- builds from a resolved FTPAccountConfig.
     * Supports FTP, Explicit FTPS, and Implicit FTPS.
     */
    public FtpRemoteHandler(FTPAccountConfig config) {
        this.host            = config.host;
        this.port            = config.port;
        this.username        = config.username;
        this.password        = config.password;
        this.useSSL          = config.useSSL;
        this.isImplicit      = config.isImplicit;
        this.protectionLevel = config.protectionLevel != null
                ? config.protectionLevel : "P";
        this.transferType    = config.transferType != null
                ? config.transferType.trim() : TRANSFER_TYPE_PASSIVE;
    }

    /**
     * Fallback constructor from raw parameters.
     * Used when FTPAccountConfig is not available.
     */
    public FtpRemoteHandler(String  host,     int    port,
                            String  username, String password,
                            boolean useSSL,   String transferType) {
        this.host            = host;
        this.port            = port;
        this.username        = username;
        this.password        = password;
        this.useSSL          = useSSL;
        this.isImplicit      = false;
        this.protectionLevel = "P";
        this.transferType    = (transferType != null)
                ? transferType.trim()
                : TRANSFER_TYPE_PASSIVE;
    }

    // ?????????????????????????????????????????????????????????????????????
    // connect()
    // ?????????????????????????????????????????????????????????????????????

    /**
     * Opens TCP connection, authenticates, and configures transfer settings.
     *
     * Steps:
     *   1. Create FTPClient or FTPSClient (no PrintCommandListener ? clean logs)
     *   2. TCP connect
     *   3. FTPS handshake if useSSL=true
     *   4. Login
     *   5. Set Passive / Active mode
     *   6. Set BINARY, UTF-8, timeouts, buffer
     *
     * @throws IOException if connect or login fails
     */
    @Override
    public void connect() throws IOException {

        // Step 1: Create FTPClient or FTPSClient
        // PrintCommandListener intentionally omitted ? avoids log pollution
        // (PASV, 200 Type set to I, etc. flooding the logs)
        if (useSSL) {
            this.ftpClient = new FTPSClient("TLS", isImplicit);
            LOG.info("Connecting to [{}:{}] using FTPS (Explicit TLS).", host, port);
        } else {
            this.ftpClient = new FTPClient();
            LOG.info("Connecting to [{}:{}] using plain FTP.", host, port);
        }

        // Step 2: Set timeouts and TCP connect
        ftpClient.setConnectTimeout(CONNECT_TIMEOUT_MS);
        ftpClient.setDefaultTimeout(DEFAULT_TIMEOUT_MS);

        try {
            ftpClient.connect(host, port);
        } catch (IOException e) {
            LOG.error("TCP connect failed to [{}:{}]: {}", host, port, e.getMessage());
            throw e;
        }

        int connectReply = ftpClient.getReplyCode();
        if (!FTPReply.isPositiveCompletion(connectReply)) {
            forceClose("Server refused connection. code=" + connectReply);
            throw new IOException("FTP server refused connection."
                    + " host=" + host + ":" + port + " code=" + connectReply);
        }

        try {
            // Step 3: FTPS handshake before login
            if (useSSL && ftpClient instanceof FTPSClient) {
                FTPSClient ftps = (FTPSClient) ftpClient;
                ftps.execPBSZ(0);
                ftps.execPROT("P".equalsIgnoreCase(protectionLevel) ? "P" : "C");
                LOG.debug("FTPS handshake complete. PBSZ=0, PROT=P.");
            }

            // Step 4: Login
            LOG.info("Logging in to [{}] as user [{}].", host, username);
            boolean loginOk   = ftpClient.login(username, password);
            int     loginCode = ftpClient.getReplyCode();
            String  loginMsg  = ftpClient.getReplyString().trim();

            if (!loginOk) {
                String error = buildLoginErrorMessage(loginCode, loginMsg);
                LOG.error(error);
                forceClose("Login failed. code=" + loginCode);
                throw new IOException(error);
            }

            LOG.info("Login successful to [{}].", host);

            // Step 5: Set transfer mode
            if (TRANSFER_TYPE_ACTIVE.equalsIgnoreCase(transferType)) {
                ftpClient.enterLocalActiveMode();
                LOG.debug("Transfer mode set to ACTIVE.");
            } else {
                ftpClient.enterLocalPassiveMode();
                LOG.debug("Transfer mode set to PASSIVE.");
            }

            // Step 6: Binary, UTF-8, timeouts, buffer
            ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
            ftpClient.setControlEncoding("UTF-8");
            ftpClient.setDataTimeout(DATA_TIMEOUT_MS);
            ftpClient.setBufferSize(BUFFER_SIZE);

            connected = true;
            LOG.info("FTP connection ready. host={} ssl={} transferType={}",
                    host, useSSL, transferType);

        } catch (IOException e) {
            forceClose("Exception during connect setup: " + e.getMessage());
            throw e;
        } catch (Exception e) {
            // Catch unchecked exceptions (e.g. NullPointerException from malformed
            // server response in login() or getReplyString()) so the socket opened
            // by ftpClient.connect() is always closed and never leaks.
            forceClose("Unchecked exception during connect setup: " + e.getMessage());
            throw new IOException("FTP connect failed: " + e.getMessage(), e);
        }
    }

    // ?????????????????????????????????????????????????????????????????????
    // disconnect()
    // ?????????????????????????????????????????????????????????????????????

    /**
     * Gracefully logs out and closes the TCP connection.
     * Idempotent ? safe to call multiple times.
     * Never throws.
     */
    @Override
    public void disconnect() {
        if (closed) {
            LOG.debug("disconnect() ? already closed for [{}]. Skipping.", host);
            return;
        }
        closed = true;

        if (ftpClient == null) {
            LOG.debug("disconnect() ? ftpClient is null for [{}].", host);
            return;
        }

        // Attempt graceful QUIT
        if (ftpClient.isConnected()) {
            try {
                ftpClient.logout();
                LOG.debug("FTP QUIT sent to [{}].", host);
            } catch (IOException e) {
                LOG.warn("QUIT failed for [{}]: {}. Closing socket anyway.", host, e.getMessage());
            }
        }

        // Always close the socket
        try {
            ftpClient.disconnect();
            connected = false;
            LOG.info("Disconnected from [{}].", host);
        } catch (IOException e) {
            LOG.warn("Socket close error for [{}]: {}", host, e.getMessage());
        }
    }

    // ?????????????????????????????????????????????????????????????????????
    // close() ? AutoCloseable
    // ?????????????????????????????????????????????????????????????????????

    @Override
    public void close() {
        disconnect();
    }

    // ?????????????????????????????????????????????????????????????????????
    // sendNoOp()  ? NEW ? used by FTPConnectionManager health check
    // ?????????????????????????????????????????????????????????????????????

    /**
     * Sends FTP NOOP command to check if the connection is still alive.
     * Used by FTPConnectionManager.isAlive() before every file operation.
     *
     * NOOP is the lightest possible FTP command ? no data transfer,
     * just a ping on the control channel.
     *
     * @return  true if server replied with 200 (connection alive)
     *          false if reply was not positive (connection degraded)
     * @throws IOException if the NOOP command could not be sent
     *                     (socket closed, connection reset, etc.)
     */
    @Override
    public boolean sendNoOp() throws IOException {
        if (ftpClient == null || !ftpClient.isConnected()) {
            return false;
        }
        boolean ok = ftpClient.sendNoOp();
        LOG.debug("NOOP to [{}] ? {}", host, ok ? "alive" : "dead");
        return ok;
    }

    // ?????????????????????????????????????????????????????????????????????
    // setKeepAlive()  ? NEW ? called by FTPConnectionManager after connect
    // ?????????????????????????????????????????????????????????????????????

    /**
     * Configures keep-alive on the underlying FTPClient.
     * Apache Commons Net will automatically send NOOP every
     * intervalSeconds to prevent the server from closing the
     * idle control channel between file transfers.
     *
     * This is especially important when processing large batches of files
     * where the time between transfers may exceed the server's idle timeout.
     *
     * @param intervalSeconds       How often to send NOOP (e.g. 60)
     * @param replyTimeoutMillis    How long to wait for NOOP reply (e.g. 10000)
     */
    @Override
    public void setKeepAlive(int intervalSeconds, int replyTimeoutMillis) {
        if (ftpClient != null) {
            ftpClient.setControlKeepAliveTimeout(intervalSeconds);
            ftpClient.setControlKeepAliveReplyTimeout(replyTimeoutMillis);
            LOG.debug("Keep-alive set on [{}]. interval={}s replyTimeout={}ms",
                    host, intervalSeconds, replyTimeoutMillis);
        }
    }

    // ?????????????????????????????????????????????????????????????????????
    // listFiles()
    // ?????????????????????????????????????????????????????????????????????

    /**
     * Lists all entries in a remote directory.
     *
     * @param remotePath  Remote directory  e.g. "/source/"
     * @return            Array of FTPFile entries (empty array if none found)
     * @throws IOException if listing fails
     */
    @Override
    public FileEntry[] listFiles(String remotePath) throws IOException {
        checkConnected("listFiles");
        LOG.debug("Listing files in remote path [{}].", remotePath);

        // Use fully qualified FTPFile to avoid any import conflicts
        org.apache.commons.net.ftp.FTPFile[] ftpFiles = ftpClient.listFiles(remotePath);

        if (ftpFiles == null || ftpFiles.length == 0) {
            LOG.warn("No entries found in remote path [{}].", remotePath);
            return new FileEntry[0];
        }

        // Wrap each FTPFile into a protocol-neutral FileEntry
        FileEntry[] entries = new FileEntry[ftpFiles.length];
        for (int i = 0; i < ftpFiles.length; i++) {
            org.apache.commons.net.ftp.FTPFile f = ftpFiles[i];
            entries[i] = new FileEntry(
                    f.getName(),
                    f.isFile(),
                    f.isDirectory(),
                    f.getSize());
        }

        LOG.debug("Found [{}] entries in [{}].", entries.length, remotePath);
        return entries;
    }

    // ?????????????????????????????????????????????????????????????????????
    // readFile()
    // ?????????????????????????????????????????????????????????????????????

    /**
     * Opens an InputStream to stream a remote file's bytes.
     *
     * ?? CONTROL CHANNEL IS LOCKED until completePendingCommand() is called.
     *    Always call completePendingCommand() in a finally block after this.
     *
     * @param remoteFilePath  Full remote path  e.g. "/source/EENav_101.csv"
     * @return                InputStream of raw file bytes
     * @throws IOException    if file cannot be opened
     */
    @Override
    public InputStream readFile(String remoteFilePath) throws IOException {
        checkConnected("readFile");
        LOG.info("Reading [{}] into memory buffer.", remoteFilePath);

        // CRITICAL: Buffer the entire file into memory before returning.
        // retrieveFileStream() opens a RETR data channel but locks the control
        // channel until completePendingCommand() is called. If we return a lazy
        // stream, writeFile() cannot use CWD/MKD/STOR on the same connection
        // because the control channel is locked -- causing 550 failures.
        // By fully buffering here and completing the pending command immediately,
        // the control channel is completely free when writeFile() runs.
        java.io.InputStream rawStream = ftpClient.retrieveFileStream(remoteFilePath);
        if (rawStream == null) {
            throw new IOException("Could not open read stream for [" + remoteFilePath
                    + "]. Server reply: " + ftpClient.getReplyString().trim()
                    + ". File may not exist or permissions are denied.");
        }

        // Read all bytes into memory
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int    read;
        while ((read = rawStream.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        rawStream.close();

        // Complete the pending RETR command NOW -- releases control channel
        boolean ok = ftpClient.completePendingCommand();
        if (!ok) {
            LOG.warn("completePendingCommand after buffered read returned false. Reply: {}",
                    ftpClient.getReplyString().trim());
        }
        LOG.debug("Buffered {} bytes from [{}]. Control channel free.", buffer.size(), remoteFilePath);

        return new java.io.ByteArrayInputStream(buffer.toByteArray());
    }

    // ?????????????????????????????????????????????????????????????????????
    // completePendingCommand()
    // ?????????????????????????????????????????????????????????????????????

    /**
     * MUST be called after readFile() stream is consumed.
     * Releases CONTROL CHANNEL by waiting for server's 226 reply.
     * Called in a finally block in processFile().
     *
     * @throws IOException if transfer did not complete
     */
    @Override
    public void completePendingCommand() throws IOException {
        // No-op: completePendingCommand() is called inside readFile()
        // immediately after the file is fully buffered into memory.
        // The finally block in processFile() still calls this -- harmless.
        LOG.debug("completePendingCommand() -- handled inside readFile(). No-op here.");
    }

    // ?????????????????????????????????????????????????????????????????????
    // writeFile()
    // ?????????????????????????????????????????????????????????????????????

    /**
     * Writes an InputStream to a remote file.
     * Auto-creates target directory.
     * Deletes existing target file first to allow overwrite ?
     * prevents storeFile() returning false when same filename exists.
     *
     * @param remoteDir       Target directory (auto-created if missing)
     * @param remoteFileName  Target filename (same as source filename)
     * @param content         File bytes from readFile()
     * @return                true if write succeeded
     * @throws IOException    if write fails
     */
    @Override
    public boolean writeFile(String      remoteDir,
                             String      remoteFileName,
                             InputStream content) throws IOException {
        checkConnected("writeFile");

        // Step 1: Ensure target directory exists using CWD+MKD per segment
        // More reliable than absolute MKD which many servers reject.
        // After this call, CWD is at the deepest segment of remoteDir.
        makeDirectories(remoteDir);

        // Step 2: Explicit absolute CWD to target directory.
        // CRITICAL: Do not rely on makeDirectories() leaving CWD in the right
        // place -- server PWD replies can be unreliable. Always CWD explicitly
        // before STOR. If CWD fails, throw immediately -- never store in wrong dir.
        String normalizedDir = normalizePath(remoteDir);
        boolean cwdOk = ftpClient.changeWorkingDirectory(remoteDir)
                || ftpClient.changeWorkingDirectory(normalizedDir);
        if (!cwdOk) {
            throw new IOException(
                    "Cannot CWD to target directory [" + remoteDir + "]. "
                            + "Reply: " + ftpClient.getReplyString().trim()
                            + " | Directory may not exist.");
        }

        String pwd = ftpClient.printWorkingDirectory();
        LOG.info("CWD confirmed [{}]. Storing [{}].", pwd, remoteFileName);

        // Step 3: Re-enter passive mode -- previous RETR may have reset state
        if (TRANSFER_TYPE_PASSIVE.equalsIgnoreCase(transferType)) {
            ftpClient.enterLocalPassiveMode();
        }

        // Step 4: Delete existing file by relative name to allow overwrite
        try {
            boolean del = ftpClient.deleteFile(remoteFileName);
            if (del) LOG.debug("Deleted existing [{}] for overwrite.", remoteFileName);
        } catch (Exception ignored) {}

        // Step 5: STOR by relative filename only -- CWD is confirmed target dir
        boolean success = ftpClient.storeFile(remoteFileName, content);
        if (!success) {
            LOG.error("[FAILED] storeFile [{}] in [{}]. Reply: {}",
                    remoteFileName, pwd, ftpClient.getReplyString().trim());
        } else {
            LOG.info("[STORED] [{}] -> [{}].", remoteFileName, pwd);
        }
        return success;
    }

    // ?????????????????????????????????????????????????????????????????????
    // deleteFile()
    // ?????????????????????????????????????????????????????????????????????

    /**
     * Deletes a remote file using FTP DELE command.
     * Called on source after successful transfer.
     * Never throws ? returns false on failure.
     *
     * @param remoteFilePath  Full remote path to delete
     * @return                true if deleted, false otherwise
     */
    @Override
    public boolean deleteFile(String remoteFilePath) {
        if (!isConnected()) {
            LOG.warn("deleteFile() ? not connected to [{}]. Cannot delete [{}].",
                    host, remoteFilePath);
            return false;
        }
        try {
            LOG.debug("Deleting [{}] from [{}].", remoteFilePath, host);
            boolean deleted = ftpClient.deleteFile(remoteFilePath);
            if (!deleted) {
                LOG.warn("Could not delete [{}]. Server reply: {}",
                        remoteFilePath, ftpClient.getReplyString().trim());
            }
            return deleted;
        } catch (IOException e) {
            LOG.error("Exception deleting [{}]: {}", remoteFilePath, e.getMessage(), e);
            return false;
        }
    }

    // ?????????????????????????????????????????????????????????????????????
    // isConnected()
    // ?????????????????????????????????????????????????????????????????????

    /**
     * Returns true only if TCP socket is open AND login was successful.
     */
    @Override
    public boolean isConnected() {
        return connected
                && ftpClient != null
                && ftpClient.isConnected();
    }

    // ?????????????????????????????????????????????????????????????????????
    // Private Helpers
    // ?????????????????????????????????????????????????????????????????????

    private void checkConnected(String operation) throws IOException {
        if (!isConnected()) {
            throw new IOException("Cannot perform [" + operation + "] on ["
                    + host + "] ? not connected.");
        }
    }

    private void forceClose(String reason) {
        LOG.warn("Force-closing connection to [{}]. Reason: {}", host, reason);
        closed = true;
        if (ftpClient != null) {
            try { ftpClient.disconnect(); } catch (IOException e) {
                LOG.warn("Force-close error for [{}]: {}", host, e.getMessage());
            }
        }
    }

    private void makeDirectories(String remotePath) throws IOException {
        if (remotePath == null || remotePath.isEmpty()) return;

        LOG.info("Ensuring directory exists: [{}]", remotePath);

        // CWD to root first
        if (!ftpClient.changeWorkingDirectory("/")) {
            LOG.warn("Cannot CWD to root. Reply: {}", ftpClient.getReplyString().trim());
        }

        String[] parts = remotePath.replaceAll("^/+", "").replaceAll("/+$", "").split("/");

        for (String part : parts) {
            if (part == null || part.trim().isEmpty()) continue;

            // Try CWD into this segment -- if it works, directory already exists
            boolean cwdOk = ftpClient.changeWorkingDirectory(part);
            if (!cwdOk) {
                // Does not exist -- create it
                boolean made = ftpClient.makeDirectory(part);
                int reply    = ftpClient.getReplyCode();

                if (made || reply == 257) {
                    LOG.info("Created directory segment [{}]. Reply: {}",
                            part, ftpClient.getReplyString().trim());
                } else if (reply == 550 || reply == 521 || reply == 553) {
                    LOG.debug("Directory [{}] already exists (reply={}). Continuing.", part, reply);
                } else {
                    LOG.warn("MKD [{}] reply={}. Msg: {}. Attempting CWD anyway.",
                            part, reply, ftpClient.getReplyString().trim());
                }

                // CWD into the segment we just created (or that already existed)
                if (!ftpClient.changeWorkingDirectory(part)) {
                    throw new IOException("Cannot CWD into [" + part + "] after MKD. "
                            + "Reply: " + ftpClient.getReplyString().trim()
                            + " | Full path: " + remotePath);
                }
            }
        }
        LOG.info("Directory walk complete. PWD=[{}].", ftpClient.printWorkingDirectory());
    }

    private static String normalizePath(String path) {
        if (path == null || path.isEmpty()) return "/";
        return path.endsWith("/") ? path : path + "/";
    }

    private String buildLoginErrorMessage(int code, String reply) {
        String hint;
        switch (code) {
            case REPLY_LOGIN_INCORRECT:
                hint = "Wrong username/password or user not permitted.";
                break;
            case REPLY_NOT_AVAILABLE:
                hint = "IP not whitelisted. Ask FTP admin to whitelist Adeptia server IP.";
                break;
            case REPLY_NEED_SECURITY:
                hint = "Server requires FTPS. Set FtpOverSSL=true in the activity.";
                break;
            default:
                hint = "Check credentials, IP whitelist, and server configuration.";
        }
        return String.format(
                "FTP login failed. host=%s:%d user=%s ssl=%s transferType=%s code=%d reply=%s | Hint: %s",
                host, port, username, useSSL, transferType, code, reply, hint);
    }
}