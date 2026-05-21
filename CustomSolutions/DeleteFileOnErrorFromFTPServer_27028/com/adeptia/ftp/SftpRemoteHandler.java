package com.adeptia.ftp;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPSClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;

/**
 * SftpRemoteHandler
 * -----------------
 * Unified FTP + SFTP handler.
 *
 * Protocol selection (from FTPAccountConfig):
 *   config.isSFTP == true   -> JSch direct (SSH/SFTP, port 22)
 *   config.useSSL == true   -> Apache Commons Net FTPSClient (Explicit/Implicit FTPS)
 *   otherwise               -> Apache Commons Net FTPClient  (plain FTP)
 *
 * All I/O operations (list, read, write, delete) use the raw protocol client
 * directly with no Camel dependency at runtime.
 */
public class SftpRemoteHandler implements IFileHandler {

    private static final Logger LOG = LoggerFactory.getLogger(SftpRemoteHandler.class);

    // -- Fields ------------------------------------------------------------
    private final FTPAccountConfig  config;
    private FTPClient               ftpClient;     // FTP/FTPS — Apache Commons Net
    // SFTP — JSch direct (bypasses known_hosts issues in containerised environments)
    private Session                 sftpSession;
    private ChannelSftp             sftpChannel;
    private boolean                 connected = false;
    private boolean                 closed    = false;
    // Parsed once on first access; config.addOnConfigurations is immutable after construction
    private Map<String, String>     addOnConfigCache;

    // -- Constructor -------------------------------------------------------

    public SftpRemoteHandler(FTPAccountConfig config) {
        this.config = config;
    }

    // =====================================================================
    // connect()
    // =====================================================================

    @Override
    public void connect() throws IOException {
        closed = false; // reset so disconnect() works correctly if this handler is reconnected
        LOG.info("Connecting [{}] to [{}:{}] user=[{}].",
                config.getProtocolName(), config.host, config.port, config.username);
        try {
            if (config.isSFTP) {
                connectSFTP();
            } else {
                connectFTP();
            }
            connected = true;
            LOG.info("[{}] connection ready. host={} port={}",
                    config.getProtocolName(), config.host, config.port);
        } catch (Exception e) {
            forceClose();
            throw new IOException("Connect failed [" + config.getProtocolName()
                    + "] host=" + config.host + ":" + config.port
                    + " user=" + config.username + " | " + e.getMessage(), e);
        }
    }

    // -- FTP / FTPS connect ------------------------------------------------

    private void connectFTP() throws Exception {
        if (config.useSSL) {
            // FIX: assign this.ftpClient immediately after construction so that
            // forceClose() can close the socket if connect() or login() throws.
            this.ftpClient = new FTPSClient("TLS", config.isImplicit);
            this.ftpClient.setConnectTimeout(30_000);
            this.ftpClient.connect(config.host, config.port);
            this.ftpClient.login(config.username, config.password);
            ((FTPSClient) this.ftpClient).execPBSZ(0);
            // Protection level: P = Private (encrypted data channel)
            ((FTPSClient) this.ftpClient).execPROT(
                    "P".equalsIgnoreCase(config.protectionLevel) ? "P" : "C");
            if ("Passive".equalsIgnoreCase(config.transferType)) {
                this.ftpClient.enterLocalPassiveMode();
            }
            this.ftpClient.setFileType(org.apache.commons.net.ftp.FTP.BINARY_FILE_TYPE);
            LOG.debug("FTPS connected. implicit={} port={}", config.isImplicit, config.port);
        } else {
            // FIX: same early-assignment pattern as FTPS branch above.
            this.ftpClient = new FTPClient();
            this.ftpClient.setConnectTimeout(30_000);
            this.ftpClient.connect(config.host, config.port);
            this.ftpClient.login(config.username, config.password);
            if ("Passive".equalsIgnoreCase(config.transferType)) {
                this.ftpClient.enterLocalPassiveMode();
            }
            this.ftpClient.setFileType(org.apache.commons.net.ftp.FTP.BINARY_FILE_TYPE);
            LOG.debug("FTP connected. port={}", config.port);
        }
        applyFTPAddOnConfig(ftpClient);
    }

    // -- SFTP connect (JSch) -----------------------------------------------

    private void connectSFTP() throws Exception {
        LOG.info("Connecting to SFTP [{}:{}] via JSch directly.", config.host, config.port);

        JSch jsch = new JSch();

        // -- Build JSch config -----------------------------------------------
        // Start with the most permissive set of algorithms to maximise
        // compatibility with both modern and legacy SFTP servers.
        // addOnConfigurations can override any of these values.
        Properties jschConfig = new Properties();

        // Host key checking -- disabled to avoid known_hosts file prompts
        // in containerised/server environments where no SSH home dir exists.
        jschConfig.put("StrictHostKeyChecking", "no");
        jschConfig.put("HashKnownHosts",        "no");

        // Authentication order -- password first (most common for FTP-style servers)
        jschConfig.put("PreferredAuthentications",
                "password,keyboard-interactive,publickey,gssapi-with-mic");

        // KEX algorithms: full list from modern + legacy to cover all servers
        jschConfig.put("kex",
                "curve25519-sha256,curve25519-sha256@libssh.org,"
                        + "ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521,"
                        + "diffie-hellman-group-exchange-sha256,"
                        + "diffie-hellman-group16-sha512,diffie-hellman-group18-sha512,"
                        + "diffie-hellman-group14-sha256,"
                        + "diffie-hellman-group14-sha1,"
                        + "diffie-hellman-group-exchange-sha1,"
                        + "diffie-hellman-group1-sha1");

        // Host key types: include legacy DSA and RSA without sha2
        jschConfig.put("server_host_key",
                "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,"
                        + "ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256,ssh-rsa,ssh-dss");

        // Ciphers: include legacy CBC modes for old servers
        String ciphers = "aes128-ctr,aes192-ctr,aes256-ctr,"
                + "aes128-gcm@openssh.com,aes256-gcm@openssh.com,"
                + "aes128-cbc,aes192-cbc,aes256-cbc,3des-cbc,blowfish-cbc";
        jschConfig.put("cipher.s2c", ciphers);
        jschConfig.put("cipher.c2s", ciphers);

        // MACs: include legacy hmac-sha1 for old servers
        String macs = "hmac-sha2-256-etm@openssh.com,hmac-sha2-512-etm@openssh.com,"
                + "hmac-sha2-256,hmac-sha2-512,hmac-sha1,hmac-sha1-96,hmac-md5";
        jschConfig.put("mac.s2c", macs);
        jschConfig.put("mac.c2s", macs);

        // Compression: off by default for reliability
        jschConfig.put("compression.s2c", "none");
        jschConfig.put("compression.c2s", "none");

        // Public key accepted algorithms -- CRITICAL for Azure SFTP.
        // Azure rejects ssh-rsa (SHA-1). Must explicitly list rsa-sha2-512
        // and rsa-sha2-256 so JSch advertises them during key exchange.
        jschConfig.put("PubkeyAcceptedAlgorithms",
                "rsa-sha2-512,rsa-sha2-256,ecdsa-sha2-nistp256,"
                        + "ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,ssh-ed25519,ssh-rsa");

        // Apply addOnConfigurations FIRST (so they override defaults above)
        applyAddOnConfigToJsch(jschConfig);

        // Log final effective config for diagnostics
        LOG.info("SFTP JSch effective config for [{}:{}]:", config.host, config.port);
        LOG.info("  kex              = {}", jschConfig.get("kex"));
        LOG.info("  server_host_key  = {}", jschConfig.get("server_host_key"));
        LOG.info("  cipher.s2c       = {}", jschConfig.get("cipher.s2c"));
        LOG.info("  mac.s2c          = {}", jschConfig.get("mac.s2c"));
        LOG.info("  StrictHostKeyChecking = {}", jschConfig.get("StrictHostKeyChecking"));
        LOG.info("  PreferredAuthentications = {}", jschConfig.get("PreferredAuthentications"));

        // -- Private Key Authentication ----------------------------------------
        // Priority order:
        //   1. Adeptia Key Manager activity (FTPAccount.getKeyManager() field)
        //   2. addOnConfigurations privateKey=/path/to/key  (file path fallback)
        //   3. Password auth (default if neither above is set)
        boolean keyAuthConfigured = false;

        // Option 1: Adeptia Key Manager
        if (config.keyManager != null && !config.keyManager.trim().isEmpty()) {
            LOG.info("Key Manager [{}] configured. Loading private key...", config.keyManager);
            try {
                applyKeyManagerToJsch(jsch, config.keyManager);
                keyAuthConfigured = true;
                // publickey ONLY -- do NOT offer password to server.
                // Azure SFTP and strict key-only servers reject the entire
                // auth chain if password is offered even as a fallback.
                jschConfig.put("PreferredAuthentications", "publickey");
                LOG.info("Key Manager key loaded. Auth=publickey ONLY (password suppressed).");
            } catch (Exception e) {
                LOG.error("Key Manager [{}] failed to load: {}. Cannot connect.",
                        config.keyManager, e.getMessage());
                throw new java.io.IOException(
                        "Key Manager [" + config.keyManager + "] failed: " + e.getMessage(), e);
            }
        }

        // Option 2: addOnConfigurations privateKey=/path/to/key
        if (!keyAuthConfigured) {
            String privateKeyPath = getAddOnValue("privateKey", null);
            if (privateKeyPath != null && !privateKeyPath.isEmpty()) {
                String passphrase = getAddOnValue("privateKeyPassphrase", null);
                if (passphrase != null && !passphrase.isEmpty()) {
                    jsch.addIdentity(privateKeyPath, passphrase);
                } else {
                    jsch.addIdentity(privateKeyPath);
                }
                keyAuthConfigured = true;
                jschConfig.put("PreferredAuthentications", "publickey");
                LOG.info("Private key (file) [{}] loaded. Auth=publickey ONLY.", privateKeyPath);
            }
        }

        boolean hasPassword = config.password != null && !config.password.trim().isEmpty();

        if (!keyAuthConfigured) {
            jschConfig.put("PreferredAuthentications", "password,keyboard-interactive");
            LOG.info("Auth=password (no Key Manager or privateKey configured).");
        }

        int connectTimeoutMs = getAddOnInt("connectTimeout", 30_000);
        int serverAliveMs    = getAddOnInt("serverAliveInterval", 60_000);

        // Enable JSch verbose logging for diagnostics -- helps diagnose
        // algorithm negotiation failures in the Adeptia log.
        JSch.setLogger(new com.jcraft.jsch.Logger() {
            public boolean isEnabled(int level) {
                return level >= com.jcraft.jsch.Logger.INFO;
            }
            public void log(int level, String message) {
                if (level >= com.jcraft.jsch.Logger.ERROR) {
                    LOG.error("JSch: {}", message);
                } else if (level >= com.jcraft.jsch.Logger.WARN) {
                    LOG.warn("JSch: {}", message);
                } else {
                    LOG.info("JSch: {}", message);
                }
            }
        });

        if (keyAuthConfigured && hasPassword) {
            // Key + password both available.
            // Attempt 1: publickey + password (covers servers that require both).
            // Attempt 2: publickey only (covers servers like Azure SFTP that reject
            //            the session if a password is offered alongside a key).
            jschConfig.put("PreferredAuthentications", "publickey,password,keyboard-interactive");
            sftpSession = jsch.getSession(config.username, config.host, config.port);
            sftpSession.setPassword(config.password);
            sftpSession.setConfig(jschConfig);
            sftpSession.setTimeout(connectTimeoutMs);
            sftpSession.setServerAliveInterval(serverAliveMs);
            sftpSession.setServerAliveCountMax(3);
            LOG.info("SFTP [{}:{}]: key+password both configured. Trying publickey+password first...",
                    config.host, config.port);
            try {
                sftpSession.connect(connectTimeoutMs);
                LOG.info("SFTP [{}:{}]: connected with publickey+password.", config.host, config.port);
            } catch (Exception e) {
                LOG.warn("SFTP [{}:{}]: publickey+password failed ({}). Retrying with publickey only...",
                        config.host, config.port, e.getMessage());
                try { sftpSession.disconnect(); } catch (Exception ignored) {}
                sftpSession = jsch.getSession(config.username, config.host, config.port);
                jschConfig.put("PreferredAuthentications", "publickey");
                sftpSession.setConfig(jschConfig);
                sftpSession.setTimeout(connectTimeoutMs);
                sftpSession.setServerAliveInterval(serverAliveMs);
                sftpSession.setServerAliveCountMax(3);
                sftpSession.connect(connectTimeoutMs);
                LOG.info("SFTP [{}:{}]: connected with publickey only (password suppressed).",
                        config.host, config.port);
            }
        } else {
            // Single-strategy: key only OR password only.
            sftpSession = jsch.getSession(config.username, config.host, config.port);
            if (!keyAuthConfigured) {
                String pwd = (config.password != null) ? config.password : "";
                if (pwd.isEmpty()) {
                    LOG.warn("SFTP password-auth mode but no password is set for user [{}] on [{}:{}]. "
                            + "Connection will likely fail. "
                            + "Set a password on the FTPAccount activity or configure a Key Manager.",
                            config.username, config.host, config.port);
                }
                sftpSession.setPassword(pwd);
            } else {
                LOG.info("Session: key-only mode (no password configured). Password NOT set.");
            }
            sftpSession.setConfig(jschConfig);
            sftpSession.setTimeout(connectTimeoutMs);
            sftpSession.setServerAliveInterval(serverAliveMs);
            sftpSession.setServerAliveCountMax(3);
            LOG.info("Connecting SSH session to [{}:{}] user=[{}] timeout={}ms...",
                    config.host, config.port, config.username, connectTimeoutMs);
            sftpSession.connect(connectTimeoutMs);
        }

        LOG.info("SSH session connected to [{}:{}].", config.host, config.port);
        sftpChannel = (ChannelSftp) sftpSession.openChannel("sftp");
        sftpChannel.connect(10_000);
        LOG.info("SFTP channel open. [{}:{}] user=[{}].",
                config.host, config.port, config.username);
    }

    // =====================================================================
    // disconnect()
    // =====================================================================

    @Override
    public void disconnect() {
        if (closed) {
            LOG.debug("disconnect() -- already closed [{}]. Skipping.", config.host);
            return;
        }
        closed = true;

        // Disconnect SFTP (JSch direct)
        if (sftpChannel != null) {
            try { sftpChannel.disconnect(); } catch (Exception e) {
                LOG.warn("Error closing SFTP channel [{}]: {}", config.host, e.getMessage());
            }
            sftpChannel = null;
        }
        if (sftpSession != null) {
            try { sftpSession.disconnect(); } catch (Exception e) {
                LOG.warn("Error closing SSH session [{}]: {}", config.host, e.getMessage());
            }
            sftpSession = null;
        }

        // Disconnect FTP/FTPS
        if (ftpClient != null && ftpClient.isConnected()) {
            try {
                ftpClient.logout();
                ftpClient.disconnect();
            } catch (Exception e) {
                LOG.warn("Error disconnecting FTPClient [{}]: {}", config.host, e.getMessage());
            }
            ftpClient = null;
        }

        connected = false;
        LOG.info("Disconnected [{}] from [{}].", config.getProtocolName(), config.host);
    }

    @Override
    public void close() {
        disconnect();
    }

    // =====================================================================
    // listFiles()
    // =====================================================================

    @Override
    public FileEntry[] listFiles(String remotePath) throws IOException {
        checkConnected("listFiles");
        LOG.debug("Listing [{}] via [{}].", remotePath, config.getProtocolName());

        try {
            List<FileEntry> entries = new ArrayList<>();

            if (config.isSFTP) {
                // Use JSch ChannelSftp.ls() directly
                @SuppressWarnings("unchecked")
                Vector<ChannelSftp.LsEntry> lsEntries = sftpChannel.ls(remotePath);
                if (lsEntries != null) {
                    for (ChannelSftp.LsEntry entry : lsEntries) {
                        String eName = entry.getFilename();
                        if (".".equals(eName) || "..".equals(eName)) continue;
                        SftpATTRS attrs = entry.getAttrs();
                        boolean isDir  = attrs.isDir();
                        entries.add(new FileEntry(eName, !isDir, isDir, attrs.getSize()));
                    }
                }
            } else {
                FTPFile[] files = ftpClient.listFiles(remotePath);
                if (files != null) {
                    for (FTPFile f : files) {
                        entries.add(new FileEntry(
                                f.getName(), f.isFile(), f.isDirectory(), f.getSize()));
                    }
                }
            }

            LOG.debug("Found [{}] entries in [{}].", entries.size(), remotePath);
            return entries.toArray(new FileEntry[0]);

        } catch (SftpException e) {
            throw new IOException("listFiles failed [" + remotePath + "]: " + e.getMessage(), e);
        }
    }

    // =====================================================================
    // readFile()
    // =====================================================================

    @Override
    public InputStream readFile(String remoteFilePath) throws IOException {
        checkConnected("readFile");
        LOG.info("Reading file [{}] into memory buffer.", remoteFilePath);
        try {
            if (config.isSFTP) {
                // JSch ChannelSftp.get() fully buffers the file.
                // SFTP has no "pending command" concept, so no completePendingCommand needed.
                LOG.info("SFTP reading [{}] into memory buffer.", remoteFilePath);
                InputStream sftpStream = sftpChannel.get(remoteFilePath);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = sftpStream.read(chunk)) != -1) {
                    baos.write(chunk, 0, read);
                }
                sftpStream.close();
                LOG.debug("SFTP buffered {} bytes from [{}].", baos.size(), remoteFilePath);
                return new ByteArrayInputStream(baos.toByteArray());
            } else {
                // KEY FIX: Buffer the entire file into memory before returning.
                //
                // Why: ftpClient.retrieveFileStream() opens a data channel but
                // leaves the control channel in "pending" state until
                // completePendingCommand() is called. In single-connection mode
                // (same source+target server), the SAME ftpClient is used for
                // both read (RETR) and write (STOR). If we return a lazy stream:
                //
                //   readFile()  -> opens RETR data channel (control pending)
                //   writeFile() -> calls CWD, MKD, STOR on same control channel
                //                  WHILE RETR is still pending = corrupted sequence
                //
                // By buffering the entire file here:
                //   readFile()  -> RETR fully completes, 226 received, channel free
                //   writeFile() -> CWD, MKD, STOR on clean control channel = works
                InputStream rawStream = ftpClient.retrieveFileStream(remoteFilePath);
                if (rawStream == null) {
                    throw new IOException("FTP retrieveFileStream returned null for ["
                            + remoteFilePath + "]. Reply: "
                            + ftpClient.getReplyString().trim());
                }

                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int    read;
                while ((read = rawStream.read(chunk)) != -1) {
                    buffer.write(chunk, 0, read);
                }
                rawStream.close();

                boolean ok = ftpClient.completePendingCommand();
                if (!ok) {
                    LOG.warn("completePendingCommand after buffered read returned false. Reply: {}",
                            ftpClient.getReplyString().trim());
                } else {
                    LOG.debug("RETR completed and buffered. {} bytes read from [{}].",
                            buffer.size(), remoteFilePath);
                }

                return new ByteArrayInputStream(buffer.toByteArray());
            }
        } catch (SftpException e) {
            throw new IOException("SFTP read failed for [" + remoteFilePath
                    + "]: " + e.getMessage(), e);
        }
    }

    // =====================================================================
    // completePendingCommand()
    // =====================================================================

    @Override
    public void completePendingCommand() throws IOException {
        // No-op for both FTP and SFTP.
        // For FTP: completePendingCommand() is called INSIDE readFile() immediately
        // after the file is fully buffered, so the control channel is already free.
        LOG.debug("completePendingCommand() -- handled inside readFile(). No-op here.");
    }

    // =====================================================================
    // writeFile()
    // =====================================================================

    @Override
    public boolean writeFile(String remoteDir, String remoteFileName,
                             InputStream content) throws IOException {
        checkConnected("writeFile");
        try {
            String fullPath = normalizePath(remoteDir) + remoteFileName;

            if (config.isSFTP) {
                makeDirectoriesSFTP(remoteDir);
                LOG.info("SFTP writing [{}] to [{}].", remoteFileName, remoteDir);
                sftpChannel.put(content, fullPath, ChannelSftp.OVERWRITE);
                LOG.info("SFTP write complete: [{}].", fullPath);
                return true;
            } else {
                // FTP/FTPS write sequence:
                //   1. makeDirectoriesFTP() -- CWD+MKD per segment to ensure dir exists
                //   2. Explicit absolute CWD to remoteDir
                //   3. Re-enter passive mode -- resets data channel state after RETR
                //   4. Delete existing file by name (relative) -- allow overwrite
                //   5. storeFile by filename only (relative to CWD)

                makeDirectoriesFTP(remoteDir);

                String normalizedDir = normalizePath(remoteDir);
                boolean cwdOk = ftpClient.changeWorkingDirectory(remoteDir)
                        || ftpClient.changeWorkingDirectory(normalizedDir);
                if (!cwdOk) {
                    throw new IOException(
                            "Cannot CWD to target directory [" + remoteDir + "]. "
                                    + "Reply: " + ftpClient.getReplyString().trim()
                                    + " | Directory may not have been created correctly.");
                }

                String pwd = ftpClient.printWorkingDirectory();
                LOG.info("CWD confirmed [{}]. Storing [{}].", pwd, remoteFileName);

                if ("Passive".equalsIgnoreCase(config.transferType)) {
                    ftpClient.enterLocalPassiveMode();
                }

                try {
                    boolean del = ftpClient.deleteFile(remoteFileName);
                    if (del) LOG.debug("Deleted existing [{}] for overwrite.", remoteFileName);
                } catch (Exception ignored) {}

                boolean success = ftpClient.storeFile(remoteFileName, content);
                if (!success) {
                    LOG.error("[FAILED] storeFile [{}] in [{}]. Reply: {}",
                            remoteFileName, pwd, ftpClient.getReplyString().trim());
                } else {
                    LOG.info("[STORED] [{}] -> [{}].", remoteFileName, pwd);
                }
                return success;
            }

        } catch (SftpException e) {
            throw new IOException("SFTP write failed for [" + remoteFileName
                    + "]: " + e.getMessage(), e);
        }
    }

    // =====================================================================
    // deleteFile()
    // =====================================================================

    @Override
    public boolean deleteFile(String remoteFilePath) {
        if (!isConnected()) {
            LOG.warn("deleteFile() -- not connected [{}].", config.host);
            return false;
        }
        try {
            LOG.debug("Deleting [{}] via [{}].", remoteFilePath, config.getProtocolName());
            boolean deleted;
            if (config.isSFTP) {
                sftpChannel.rm(remoteFilePath);
                deleted = true;
            } else {
                // For FTP: CWD to parent dir, delete by filename only
                String parentDir = getParentPath(remoteFilePath);
                String fileName  = getFileName(remoteFilePath);
                if (!parentDir.isEmpty()) {
                    ftpClient.changeWorkingDirectory(parentDir);
                }
                deleted = ftpClient.deleteFile(fileName);
                ftpClient.changeWorkingDirectory("/");
            }
            if (!deleted) {
                LOG.warn("deleteFile returned false for [{}]. Reply: {}",
                        remoteFilePath, ftpClient.getReplyString().trim());
            }
            return deleted;
        } catch (Exception e) {
            LOG.error("deleteFile failed [{}] via [{}]: {}",
                    remoteFilePath, config.getProtocolName(), e.getMessage(), e);
            return false;
        }
    }

    // =====================================================================
    // sendNoOp()
    // =====================================================================

    @Override
    public boolean sendNoOp() throws IOException {
        if (!isConnected()) return false;
        try {
            if (config.isSFTP) {
                sftpSession.sendKeepAliveMsg();
            } else {
                ftpClient.sendNoOp();
            }
            LOG.debug("NOOP sent [{}] to [{}].", config.getProtocolName(), config.host);
            return true;
        } catch (Exception e) {
            LOG.debug("NOOP failed [{}] [{}]: {}",
                    config.getProtocolName(), config.host, e.getMessage());
            return false;
        }
    }

    // =====================================================================
    // setKeepAlive()
    // =====================================================================

    @Override
    public void setKeepAlive(int intervalSeconds, int replyTimeoutMillis) {
        if (!config.isSFTP && ftpClient != null) {
            ftpClient.setControlKeepAliveTimeout(intervalSeconds);
            ftpClient.setControlKeepAliveReplyTimeout(replyTimeoutMillis);
            LOG.debug("FTP keep-alive set. interval={}s [{}].", intervalSeconds, config.host);
        }
        // SFTP: serverAliveInterval already set in connectSFTP() via JSch session
    }

    // =====================================================================
    // isConnected()
    // =====================================================================

    @Override
    public boolean isConnected() {
        if (!connected || closed) return false;
        if (config.isSFTP) {
            return sftpSession != null && sftpSession.isConnected()
                    && sftpChannel != null && sftpChannel.isConnected();
        } else {
            return ftpClient != null && ftpClient.isConnected();
        }
    }

    // =====================================================================
    // Private Helpers
    // =====================================================================

    private void checkConnected(String operation) throws IOException {
        if (!isConnected()) {
            throw new IOException("Cannot perform [" + operation
                    + "] -- [" + config.getProtocolName()
                    + "] not connected to [" + config.host + "].");
        }
    }

    private void forceClose() {
        closed = true;
        try {
            if (sftpChannel != null) { sftpChannel.disconnect(); sftpChannel = null; }
            if (sftpSession != null) { sftpSession.disconnect(); sftpSession = null; }
            if (ftpClient  != null && ftpClient.isConnected()) {
                ftpClient.disconnect(); ftpClient = null;
            }
        } catch (Exception e) {
            LOG.debug("forceClose error [{}]: {}", config.host, e.getMessage());
        }
    }

    private void makeDirectoriesFTP(String remotePath) throws IOException {
        if (remotePath == null || remotePath.isEmpty()) return;

        LOG.info("Ensuring FTP directory exists: [{}]", remotePath);

        if (!ftpClient.changeWorkingDirectory("/")) {
            LOG.warn("Cannot CWD to root. Reply: {}", ftpClient.getReplyString().trim());
        }

        String[] parts = remotePath.replaceAll("^/+", "").replaceAll("/+$", "").split("/");

        for (String part : parts) {
            if (part == null || part.trim().isEmpty()) continue;

            boolean cwdOk = ftpClient.changeWorkingDirectory(part);
            if (!cwdOk) {
                LOG.info("Creating FTP directory segment [{}] in [{}].",
                        part, ftpClient.printWorkingDirectory());
                boolean made = ftpClient.makeDirectory(part);
                int reply    = ftpClient.getReplyCode();

                if (made || reply == 257) {
                    LOG.info("Created FTP directory [{}]. Reply: {}",
                            part, ftpClient.getReplyString().trim());
                } else if (reply == 550 || reply == 521 || reply == 553) {
                    LOG.debug("FTP directory [{}] already exists (reply={}). Continuing.", part, reply);
                } else {
                    LOG.warn("MKD [{}] reply={}. msg={}. Attempting to CWD anyway.",
                            part, reply, ftpClient.getReplyString().trim());
                }

                if (!ftpClient.changeWorkingDirectory(part)) {
                    throw new IOException("Cannot CWD into [" + part + "] after MKD. "
                            + "Reply: " + ftpClient.getReplyString().trim()
                            + " | Full path: " + remotePath);
                }
            }

            LOG.debug("CWD -> [{}]. PWD: {}", part, ftpClient.printWorkingDirectory());
        }

        LOG.info("Directory walk complete for [{}]. PWD=[{}].",
                remotePath, ftpClient.printWorkingDirectory());
    }

    private void makeDirectoriesSFTP(String remotePath) {
        if (remotePath == null || remotePath.isEmpty()) return;
        String[] parts = remotePath.replaceAll("^/+", "").replaceAll("/+$", "").split("/");
        StringBuilder cur = new StringBuilder("/");
        for (String part : parts) {
            if (part == null || part.trim().isEmpty()) continue;
            cur.append(part).append("/");
            String dirPath = cur.toString();
            try {
                sftpChannel.stat(dirPath);
                LOG.debug("SFTP directory exists: [{}].", dirPath);
            } catch (SftpException statEx) {
                try {
                    sftpChannel.mkdir(dirPath);
                    LOG.info("Created SFTP directory: [{}].", dirPath);
                } catch (SftpException mkdirEx) {
                    LOG.debug("SFTP mkdir [{}] skipped (may exist): {}", dirPath, mkdirEx.getMessage());
                }
            }
        }
    }

    // =====================================================================
    // Add-On Configuration Helpers
    // =====================================================================

    /**
     * Parses addOnConfigurations string into a key-value map, cached after first call.
     * Format: "key1=value1&key2=value2&..."
     */
    private Map<String, String> parseAddOnConfig() {
        if (addOnConfigCache != null) return addOnConfigCache;
        addOnConfigCache = new LinkedHashMap<>();
        String raw = config.addOnConfigurations;
        if (raw == null || raw.trim().isEmpty()) return addOnConfigCache;
        for (String pair : raw.split("&")) {
            pair = pair.trim();
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = pair.substring(0, eq).trim();
                String val = pair.substring(eq + 1).trim();
                if (!key.isEmpty()) addOnConfigCache.put(key, val);
            }
        }
        return addOnConfigCache;
    }

    private String getAddOnValue(String key, String defaultValue) {
        return parseAddOnConfig().getOrDefault(key, defaultValue);
    }

    private int getAddOnInt(String key, int defaultValue) {
        String val = getAddOnValue(key, null);
        if (val == null) return defaultValue;
        try { return Integer.parseInt(val); }
        catch (NumberFormatException e) {
            LOG.warn("AddOnConfig [{}]=[{}] is not a valid integer. Using default={}.", key, val, defaultValue);
            return defaultValue;
        }
    }

    private void applyFTPAddOnConfig(FTPClient client) {
        Map<String, String> addOn = parseAddOnConfig();
        if (addOn.isEmpty()) return;

        LOG.info("Applying addOnConfigurations to FTPClient: {}", addOn);

        if (addOn.containsKey("connectTimeout"))       client.setConnectTimeout(Integer.parseInt(addOn.get("connectTimeout")));
        if (addOn.containsKey("dataTimeout"))          client.setDataTimeout(Integer.parseInt(addOn.get("dataTimeout")));
        if (addOn.containsKey("defaultTimeout"))       client.setDefaultTimeout(Integer.parseInt(addOn.get("defaultTimeout")));
        if (addOn.containsKey("bufferSize"))           client.setBufferSize(Integer.parseInt(addOn.get("bufferSize")));
        if (addOn.containsKey("keepAliveTimeout"))     client.setControlKeepAliveTimeout(Integer.parseInt(addOn.get("keepAliveTimeout")));
        if (addOn.containsKey("keepAliveReplyTimeout"))client.setControlKeepAliveReplyTimeout(Integer.parseInt(addOn.get("keepAliveReplyTimeout")));
        if (addOn.containsKey("encoding"))             client.setControlEncoding(addOn.get("encoding"));
    }

    private void applyAddOnConfigToJsch(Properties jschConfig) {
        Map<String, String> addOn = parseAddOnConfig();
        if (addOn.isEmpty()) return;

        LOG.info("Applying addOnConfigurations to JSch session: {}", addOn);

        Set<String> skip = new HashSet<>(Arrays.asList(
                "connectTimeout", "serverAliveInterval", "privateKey", "privateKeyPassphrase"));

        for (Map.Entry<String, String> entry : addOn.entrySet()) {
            if (!skip.contains(entry.getKey())) {
                jschConfig.put(entry.getKey(), entry.getValue());
                LOG.debug("JSch config overridden by addOn: [{}]=[{}]",
                        entry.getKey(), entry.getValue());
            }
        }
    }

    // =====================================================================
    // Key Manager Integration
    // =====================================================================

    /**
     * Resolves an Adeptia Key Manager activity and loads the SSH private key
     * into JSch for public key authentication.
     *
     * KeyManager fields used:
     *   getKeyManagerType()     -> skip if "PGP" (SSH keys only)
     *   getKeyFilePath()        -> relative path, resolved via PGPUtils
     *   getPrivateKeyPassword() -> SSH key passphrase (may be null/empty)
     *   getEncryptKeyFile()     -> whether Adeptia encrypted the file at rest
     *
     * PPK (PuTTY) keys are detected by the file header and converted to
     * OpenSSH PEM in a temp file before being loaded into JSch.
     */
    private void applyKeyManagerToJsch(JSch jsch, String keyMgrId) throws Exception {

        // Step 1: Resolve Key Manager from Adeptia DB
        com.adeptia.indigo.storage.EntityManager em =
                com.adeptia.indigo.storage.EntityManagerFactory.getEntityManager(
                        com.adeptia.indigo.security.keymanager.KeyManager.class,
                        com.adeptia.indigo.security.AuthUtil.getAdminSubject());

        com.adeptia.indigo.security.keymanager.KeyManager km =
                (com.adeptia.indigo.security.keymanager.KeyManager) em.retrieve(
                        new com.adeptia.indigo.storage.TypedEntityId(keyMgrId, "KeyManager"));

        if (km == null) {
            throw new IllegalArgumentException("Key Manager activity not found: [" + keyMgrId + "]");
        }

        // Step 2: Validate key type (PGP keys cannot be used for SSH auth)
        String keyManagerType = km.getKeyManagerType();
        if ("PGP".equalsIgnoreCase(keyManagerType)) {
            throw new IllegalArgumentException(
                    "Key Manager [" + keyMgrId + "] is type=PGP. "
                            + "Only SSH key managers are supported for SFTP.");
        }

        // Step 3: Resolve absolute key file path
        String keyFilePath;
        try {
            keyFilePath = com.adeptia.indigo.utils.PGPUtils.getKeyManagerFilePath(km);
        } catch (Exception e) {
            LOG.warn("PGPUtils.getKeyManagerFilePath() failed for [{}]: {}. "
                    + "Falling back to getKeyFilePath() directly.", keyMgrId, e.getMessage());
            keyFilePath = km.getKeyFilePath();
        }

        if (keyFilePath == null || keyFilePath.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Key Manager [" + keyMgrId + "] has no key file path.");
        }

        // Step 4: Verify key file exists and is readable
        java.io.File keyFile = new java.io.File(keyFilePath);
        if (!keyFile.exists()) {
            throw new java.io.FileNotFoundException(
                    "Key Manager [" + keyMgrId + "] key file not found: [" + keyFilePath + "].");
        }
        if (!keyFile.canRead()) {
            throw new java.io.IOException(
                    "Key Manager [" + keyMgrId + "] key file not readable: [" + keyFilePath + "].");
        }

        // Step 5: Get passphrase
        String passPhrase     = km.getPrivateKeyPassword();
        String keyType        = km.getKeyType();
        boolean encryptedFile = km.getEncryptKeyFile();

        LOG.info("Key Manager [{}] resolved: type={} keyType={} file=[{}] hasPassphrase={} encryptedAtRest={}",
                keyMgrId, keyManagerType, keyType, keyFilePath,
                passPhrase != null && !passPhrase.isEmpty() ? "yes" : "no",
                encryptedFile);

        // Step 6: Detect PPK format and convert to OpenSSH PEM if needed.
        // JSch supports OpenSSH PEM but NOT PuTTY PPK format.
        String  effectiveKeyPath = keyFilePath;
        boolean tempFileCreated  = false;
        java.io.File tempKeyFile = null;

        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.FileReader(keyFilePath));
            String firstLine = reader.readLine();
            reader.close();

            LOG.info("Key Manager [{}] key file first line: [{}]", keyMgrId,
                    firstLine != null ? firstLine.trim() : "(empty)");

            boolean isPpk = firstLine != null
                    && (firstLine.startsWith("PuTTY-User-Key-File-2:")
                    || firstLine.startsWith("PuTTY-User-Key-File-3:"));

            if (isPpk) {
                LOG.info("Key Manager [{}] key is PPK format. Converting to OpenSSH PEM...", keyMgrId);
                try {
                    com.jcraft.jsch.KeyPair kpair = com.jcraft.jsch.KeyPair.load(jsch, keyFilePath);
                    tempKeyFile = java.io.File.createTempFile("adeptia_sftp_key_", ".pem");
                    tempKeyFile.deleteOnExit();
                    if (passPhrase != null && !passPhrase.isEmpty()) {
                        kpair.writePrivateKey(tempKeyFile.getAbsolutePath(), passPhrase.getBytes());
                    } else {
                        kpair.writePrivateKey(tempKeyFile.getAbsolutePath());
                    }
                    kpair.dispose();
                    effectiveKeyPath = tempKeyFile.getAbsolutePath();
                    tempFileCreated  = true;
                    LOG.info("PPK converted to OpenSSH PEM at temp path [{}].", effectiveKeyPath);
                } catch (Exception ppkEx) {
                    LOG.error("PPK conversion failed for Key Manager [{}]: {}. "
                                    + "Convert key to OpenSSH PEM and re-upload.",
                            keyMgrId, ppkEx.getMessage());
                    throw new java.io.IOException(
                            "PPK key conversion failed for Key Manager [" + keyMgrId + "]: "
                                    + ppkEx.getMessage(), ppkEx);
                }
            } else {
                LOG.info("Key Manager [{}] key format: OpenSSH/PEM. Using as-is.", keyMgrId);
            }

            // Step 7: Load identity into JSch
            LOG.info("Loading identity into JSch from [{}] passphrase={}",
                    effectiveKeyPath, passPhrase != null && !passPhrase.isEmpty() ? "yes" : "no");
            if (passPhrase != null && !passPhrase.isEmpty()) {
                jsch.addIdentity(effectiveKeyPath, passPhrase);
            } else {
                jsch.addIdentity(effectiveKeyPath);
            }
            LOG.info("Key Manager [{}] identity loaded. JSch will use publickey auth.", keyMgrId);

        } finally {
            if (tempFileCreated && tempKeyFile != null && tempKeyFile.exists()) {
                boolean deleted = tempKeyFile.delete();
                LOG.debug("Temp key file [{}] deleted={}.", tempKeyFile.getAbsolutePath(), deleted);
            }
        }
    }

    private static String normalizePath(String path) {
        if (path == null || path.isEmpty()) return "/";
        return path.endsWith("/") ? path : path + "/";
    }

    private static String getParentPath(String fullPath) {
        if (fullPath == null) return "/";
        int last = fullPath.lastIndexOf('/');
        return last > 0 ? fullPath.substring(0, last) : "/";
    }

    private static String getFileName(String fullPath) {
        if (fullPath == null) return "";
        int last = fullPath.lastIndexOf('/');
        return last >= 0 ? fullPath.substring(last + 1) : fullPath;
    }
}
