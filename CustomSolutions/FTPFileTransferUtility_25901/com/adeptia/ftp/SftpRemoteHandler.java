package com.adeptia.ftp;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import org.apache.camel.component.file.GenericFileOperationFailedException;
import org.apache.camel.component.file.remote.FtpConfiguration;
import org.apache.camel.component.file.remote.FtpEndpoint;
import org.apache.camel.component.file.remote.FtpOperations;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPClientConfig;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPSClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Vector;

/**
 * CamelFileHandler
 * -----------------
 * Unified FTP + SFTP handler using Apache Camel's built-in operations.
 * No additional dependencies -- everything already on Adeptia's classpath.
 *
 * -- Error fixes from Adeptia Camel version -------------------------------
 *
 *   The Camel version in Adeptia AC4 uses slightly different APIs than
 *   the latest Camel. Specifically:
 *
 *   FtpConfiguration:
 *     - No setSecurityProtocol()     -> handled via FTPSClient directly
 *     - No setImplicit()             -> handled via FTPSClient constructor
 *     - No setDataChannelProtectionLevel() -> handled via FTPSClient.execPROT()
 *
 *   FtpOperations:
 *     - Constructor: FtpOperations(FTPClient, FTPClientConfig)  not (FtpEndpoint, null)
 *
 *   SftpOperations:
 *     - Constructor: SftpOperations(SftpEndpoint)  not (SftpEndpoint, null)
 *
 *   listFiles():
 *     - FTP  returns FTPFile[]            not List<FTPFile>
 *     - SFTP returns SftpRemoteFile[]     not List<SftpRemoteFile>
 *
 *   SftpRemoteFile:
 *     - getFilename()  not getFileName()
 *     - isRegularFile() not isFile()
 *
 *   storeFile():
 *     - Signature: storeFile(String name, Exchange exchange, long size)
 *     - We pass Exchange=null, size=-1
 *
 *   retrieveFile():
 *     - No retrieveFileToStreamInMessageBody() on either operations class
 *     - Use retrieveFile(name, exchange, size) + write to stream via exchange
 *
 * -- Protocol Selection ----------------------------------------------------
 *
 *   FTPAccountConfig.isSFTP == true   -> SftpConfiguration + SftpOperations
 *   FTPAccountConfig.useSSL == true   -> FTPSClient + FtpOperations
 *   otherwise                         -> FTPClient  + FtpOperations
 */
public class SftpRemoteHandler implements IFileHandler {

    private static final Logger LOG = LoggerFactory.getLogger(SftpRemoteHandler.class);

    // -- Fields ------------------------------------------------------------
    private final FTPAccountConfig config;
    private DefaultCamelContext    camelContext;
    private FtpOperations          ftpOps;
    private FTPClient              ftpClient;     // FTP/FTPS direct client
    // SFTP direct via JSch (bypasses Camel SftpOperations to avoid known_hosts issues)
    private Session                sftpSession;
    private ChannelSftp            sftpChannel;
    private boolean                connected = false;
    private boolean                closed    = false;

    // -- Constructor -------------------------------------------------------

    public SftpRemoteHandler(FTPAccountConfig config) {
        this.config = config;
    }

    // =====================================================================
    // connect()
    // =====================================================================

    @Override
    public void connect() throws IOException {
        LOG.info("Connecting via Camel [{}] to [{}:{}] user=[{}].",
                config.getProtocolName(), config.host, config.port, config.username);
        try {
            camelContext = new DefaultCamelContext();
            camelContext.start();

            if (config.isSFTP) {
                connectSFTP();
            } else {
                connectFTP();
            }

            connected = true;
            LOG.info("Camel [{}] connection ready. host={} port={}",
                    config.getProtocolName(), config.host, config.port);

        } catch (Exception e) {
            forceClose();
            throw new IOException("Camel connect failed [" + config.getProtocolName()
                    + "] host=" + config.host + ":" + config.port
                    + " user=" + config.username + " | " + e.getMessage(), e);
        }
    }

    // -- FTP / FTPS connect ------------------------------------------------

    private void connectFTP() throws Exception {

        // -- Step 1: Create the right FTPClient ----------------------------
        // FIX: setSecurityProtocol / setImplicit / setDataChannelProtectionLevel
        //      do NOT exist in FtpConfiguration in Adeptia's Camel version.
        //      Instead we construct FTPSClient directly and pass it to FtpOperations.
        if (config.useSSL) {
            // Explicit FTPS: connect on port 21 then upgrade to TLS
            // Implicit FTPS: connect directly on SSL socket (port 990)
            // FIX: assign this.ftpClient immediately after construction so that
            // forceClose() can close the socket if connect() or login() throws.
            // Previously this.ftpClient was assigned only AFTER login(), meaning
            // an exception during login left an open socket that forceClose() could
            // never find (ftpClient was still null at that point).
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
            LOG.debug("FTPS connected directly. implicit={} port={}", config.isImplicit, config.port);
        } else {
            // Plain FTP
            // FIX: same early-assignment pattern as FTPS branch above.
            this.ftpClient = new FTPClient();
            this.ftpClient.setConnectTimeout(30_000);
            this.ftpClient.connect(config.host, config.port);
            this.ftpClient.login(config.username, config.password);
            if ("Passive".equalsIgnoreCase(config.transferType)) {
                this.ftpClient.enterLocalPassiveMode();
            }
            this.ftpClient.setFileType(org.apache.commons.net.ftp.FTP.BINARY_FILE_TYPE);
            LOG.debug("FTP connected directly. port={}", config.port);
        }

        // -- Step 2: Wrap in FtpOperations via FtpConfiguration ------------
        // FIX: FtpOperations constructor in Adeptia's Camel is:
        //      FtpOperations(FTPClient client, FTPClientConfig clientConfig)
        FtpConfiguration ftpCfg = new FtpConfiguration();
        ftpCfg.setHost(config.host);
        ftpCfg.setPort(config.port);
        ftpCfg.setUsername(config.username);
        ftpCfg.setPassword(config.password);
        ftpCfg.setBinary(true);
        ftpCfg.setPassiveMode("Passive".equalsIgnoreCase(config.transferType));

        FTPClientConfig clientConfig = new FTPClientConfig();

        // FIX: correct constructor -- FtpOperations(FTPClient, FTPClientConfig)
        ftpOps = new FtpOperations(ftpClient, clientConfig);

        FtpEndpoint endpoint = new FtpEndpoint();
        endpoint.setCamelContext(camelContext);
        endpoint.setConfiguration(ftpCfg);
        ftpOps.setEndpoint(endpoint);

        // Apply addOnConfigurations to FTPClient
        // Format in Adeptia FTPAccount: "param1=value1&param2=value2"
        // Supported params:
        //   connectTimeout=<ms>         override TCP connect timeout
        //   dataTimeout=<ms>            override data channel timeout
        //   defaultTimeout=<ms>         override default socket timeout
        //   bufferSize=<bytes>          override transfer buffer size
        //   keepAliveTimeout=<seconds>  keep-alive NOOP interval
        //   encoding=<charset>          control channel encoding (default UTF-8)
        applyFTPAddOnConfig(ftpClient);

        LOG.debug("FTP{} operations wrapped.", config.useSSL ? "S" : "");
    }

    // -- SFTP connect ------------------------------------------------------

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
        // Without this, JSch may default to ssh-rsa and get rejected.
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
        // FTPAccount.getKeyManager() returns the Key Manager activity ID.
        // We resolve it to get the private key bytes and passphrase,
        // then pass them to JSch as an in-memory identity (no file needed).
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

        if (!keyAuthConfigured) {
            // Password auth only -- no key configured
            jschConfig.put("PreferredAuthentications", "password,keyboard-interactive");
            LOG.info("Auth=password (no Key Manager or privateKey configured).");
        }

        // Create session
        sftpSession = jsch.getSession(config.username, config.host, config.port);

        // CRITICAL: Only set password for password-auth mode.
        // For key auth, do NOT set password -- even a dummy password causes
        // Azure SFTP to reject auth if it is offered after publickey.
        if (!keyAuthConfigured) {
            sftpSession.setPassword(config.password);
            LOG.debug("Session: password auth mode. Password set.");
        } else {
            LOG.info("Session: key auth mode. Password NOT set on session (suppressed for key-only server).");
        }

        sftpSession.setConfig(jschConfig);

        int connectTimeoutMs = getAddOnInt("connectTimeout", 30_000);
        int serverAliveMs    = getAddOnInt("serverAliveInterval", 60_000);
        sftpSession.setTimeout(connectTimeoutMs);
        sftpSession.setServerAliveInterval(serverAliveMs);
        sftpSession.setServerAliveCountMax(3);

        // Enable JSch verbose logging for diagnostics -- helps diagnose
        // algorithm negotiation failures in Adeptia log
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

        LOG.info("Connecting SSH session to [{}:{}] user=[{}] timeout={}ms...",
                config.host, config.port, config.username, connectTimeoutMs);
        sftpSession.connect(connectTimeoutMs);
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

        // Disconnect FTP/FTPS (Camel FtpOperations + raw FTPClient)
        try {
            if (ftpOps != null) { ftpOps.disconnect(); ftpOps = null; }
        } catch (Exception e) {
            LOG.warn("Error disconnecting Camel FtpOps [{}]: {}", config.host, e.getMessage());
        }
        if (ftpClient != null && ftpClient.isConnected()) {
            try {
                ftpClient.logout();
                ftpClient.disconnect();
            } catch (Exception e) {
                LOG.warn("Error disconnecting FTPClient [{}]: {}", config.host, e.getMessage());
            }
            ftpClient = null;
        }

        // Stop Camel context
        try {
            if (camelContext != null && !camelContext.isStopped()) {
                camelContext.stop();
                camelContext = null;
            }
        } catch (Exception e) {
            LOG.warn("Error stopping CamelContext [{}]: {}", config.host, e.getMessage());
        }

        connected = false;
        LOG.info("Disconnected Camel [{}] from [{}].", config.getProtocolName(), config.host);
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
        LOG.debug("Listing [{}] via Camel [{}].", remotePath, config.getProtocolName());

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
                        SftpATTRS attrs  = entry.getAttrs();
                        boolean   isDir  = attrs.isDir();
                        entries.add(new FileEntry(eName, !isDir, isDir, attrs.getSize()));
                    }
                }
            } else {
                // FIX: listFiles on FTP returns FTPFile[] not List<FTPFile>
                // Use the raw ftpClient.listFiles() since FtpOperations.listFiles
                // signature varies across Camel versions
                FTPFile[] files = ftpClient.listFiles(remotePath);
                if (files != null) {
                    for (FTPFile f : files) {
                        entries.add(new FileEntry(
                                f.getName(),
                                f.isFile(),
                                f.isDirectory(),
                                f.getSize()));
                    }
                }
            }

            LOG.debug("Found [{}] entries in [{}].", entries.size(), remotePath);
            return entries.toArray(new FileEntry[0]);

        } catch (Exception e) {
            throw new IOException("listFiles failed [" + remotePath
                    + "] via Camel [" + config.getProtocolName() + "]: " + e.getMessage(), e);
        }
    }

    // =====================================================================
    // readFile()
    // =====================================================================

    /**
     * FTP  : uses ftpClient.retrieveFileStream() directly (most reliable)
     * SFTP : uses SftpOperations.getContent() or channel.get()
     *
     * FIX: retrieveFileToStreamInMessageBody() does not exist in either
     *      FtpOperations or SftpOperations in Adeptia's Camel version.
     */
    @Override
    public InputStream readFile(String remoteFilePath) throws IOException {
        checkConnected("readFile");
        LOG.info("Reading file [{}] into memory buffer.", remoteFilePath);
        try {
            if (config.isSFTP) {
                // Use JSch ChannelSftp.get() directly -- fully buffers file
                // SFTP has no "pending command" concept so no completePendingCommand needed
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
                //   finally     -> completePendingCommand() = too late, already broken
                //
                // By buffering the entire file here:
                //   readFile()  -> RETR fully completes, 226 received, channel free
                //   completePendingCommand() -> no-op (already done)
                //   writeFile() -> CWD, MKD, STOR on clean control channel = works
                //
                // Memory trade-off: files are held in RAM during transfer.
                // Acceptable for typical CSV files (<100MB).
                InputStream rawStream = ftpClient.retrieveFileStream(remoteFilePath);
                if (rawStream == null) {
                    throw new IOException("FTP retrieveFileStream returned null for ["
                            + remoteFilePath + "]. Reply: "
                            + ftpClient.getReplyString().trim());
                }

                // Read ALL bytes from the stream -- this fully consumes the RETR
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int    read;
                while ((read = rawStream.read(chunk)) != -1) {
                    buffer.write(chunk, 0, read);
                }
                rawStream.close();

                // CRITICAL: complete the RETR pending command NOW, before returning
                // This releases the control channel cleanly before writeFile() runs
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
        } catch (GenericFileOperationFailedException e) {
            throw new IOException("readFile failed [" + remoteFilePath
                    + "] via Camel [" + config.getProtocolName() + "]: " + e.getMessage(), e);
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
        //
        // For FTP: completePendingCommand() is now called INSIDE readFile()
        // immediately after the file is fully buffered into memory.
        // This ensures the RETR command is fully finished before control
        // is returned to the caller, so writeFile() sees a clean channel.
        //
        // The finally block in FTPTransferPlugin.processFile() still calls
        // this method -- it is harmless here.
        LOG.debug("completePendingCommand() -- handled inside readFile(). No-op here.");
    }

    // =====================================================================
    // writeFile()
    // =====================================================================

    /**
     * Writes a file to the remote FTP/SFTP server.
     *
     * For FTP:
     *   1. Ensures target directory exists using CWD-based mkdir
     *   2. Restores original working directory after mkdir
     *   3. Deletes existing target file to allow overwrite
     *   4. Uses ftpClient.storeFile() with absolute path
     */
    @Override
    public boolean writeFile(String remoteDir, String remoteFileName,
                             InputStream content) throws IOException {
        checkConnected("writeFile");
        try {
            String fullPath = normalizePath(remoteDir) + remoteFileName;

            if (config.isSFTP) {
                makeDirectoriesSFTP(remoteDir);
                LOG.info("SFTP writing [{}] to [{}].", remoteFileName, remoteDir);
                // ChannelSftp.OVERWRITE = overwrite existing file
                sftpChannel.put(content, fullPath, ChannelSftp.OVERWRITE);
                LOG.info("SFTP write complete: [{}].", fullPath);
                return true;
            } else {
                // FTP/FTPS write sequence:
                //   1. makeDirectoriesFTP() -- CWD+MKD per segment to ensure dir exists
                //   2. Explicit absolute CWD to remoteDir -- guarantees we are in the
                //      correct directory regardless of what makeDirectoriesFTP left behind
                //   3. Re-enter passive mode -- resets data channel state after RETR
                //   4. Delete existing file by name (relative) -- allow overwrite
                //   5. storeFile by filename only (relative to CWD) -- most compatible

                // Step 1: Ensure directory exists
                makeDirectoriesFTP(remoteDir);

                // Step 2: Explicit absolute CWD -- definitive, no ambiguity
                // This is the key fix: after makeDirectoriesFTP() we always
                // CWD to the exact absolute target path before storing.
                // If this fails, throw immediately -- do NOT store in wrong dir.
                String normalizedDir = normalizePath(remoteDir);
                // Try both with and without trailing slash
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

                // Step 3: Re-enter passive mode -- RETR may have reset data channel state
                if ("Passive".equalsIgnoreCase(config.transferType)) {
                    ftpClient.enterLocalPassiveMode();
                }

                // Step 4: Delete existing file to allow clean overwrite
                try {
                    boolean del = ftpClient.deleteFile(remoteFileName);
                    if (del) LOG.debug("Deleted existing [{}] for overwrite.", remoteFileName);
                } catch (Exception ignored) {}

                // Step 5: STOR by relative filename only (CWD is now confirmed target)
                boolean success = ftpClient.storeFile(remoteFileName, content);
                if (!success) {
                    LOG.error("[FAILED] storeFile [{}] in [{}]. Reply: {}",
                            remoteFileName, pwd, ftpClient.getReplyString().trim());
                } else {
                    LOG.info("[STORED] [{}] -> [{}].", remoteFileName, pwd);
                }
                return success;
            }

        } catch (GenericFileOperationFailedException e) {
            LOG.error("writeFile failed [{}]: {}", remoteFileName, e.getMessage(), e);
            return false;
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
            LOG.debug("Deleting [{}] via Camel [{}].", remoteFilePath, config.getProtocolName());
            boolean deleted;
            if (config.isSFTP) {
                sftpChannel.rm(remoteFilePath);
                deleted = true;
            } else {
                // For FTP: CWD to parent dir, delete by filename only
                // Absolute path DELE can fail on same servers that reject absolute STOR
                String parentDir  = getParentPath(remoteFilePath);
                String fileName   = getFileName(remoteFilePath);
                if (!parentDir.isEmpty()) {
                    ftpClient.changeWorkingDirectory(parentDir);
                }
                deleted = ftpClient.deleteFile(fileName);
                // Restore to root after operation
                ftpClient.changeWorkingDirectory("/");
            }
            if (!deleted) {
                LOG.warn("deleteFile returned false for [{}]. Reply: {}",
                        remoteFilePath, ftpClient.getReplyString().trim());
            }
            return deleted;
        } catch (Exception e) {
            LOG.error("deleteFile failed [{}] via Camel [{}]: {}",
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
                // JSch keepalive via session
                sftpSession.sendKeepAliveMsg();
            } else {
                // Use raw ftpClient.sendNoOp()
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
        // FTP: apply to raw ftpClient
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
                    + "] -- Camel [" + config.getProtocolName()
                    + "] not connected to [" + config.host + "].");
        }
    }

    private void forceClose() {
        closed = true;
        try {
            if (sftpChannel != null) { sftpChannel.disconnect(); sftpChannel = null; }
            if (sftpSession != null) { sftpSession.disconnect(); sftpSession = null; }
            if (ftpOps      != null) { ftpOps.disconnect();      ftpOps      = null; }
            if (ftpClient   != null && ftpClient.isConnected()) {
                ftpClient.disconnect(); ftpClient = null;
            }
            if (camelContext != null) { camelContext.stop(); camelContext = null; }
        } catch (Exception e) {
            LOG.debug("forceClose error [{}]: {}", config.host, e.getMessage());
        }
    }

    /**
     * FTP directory creation using CWD (change working directory) approach.
     *
     * Why CWD instead of absolute MKD?
     *   Some FTP servers reject MKD with an absolute path if the directory
     *   already partially exists, or if the server uses a chroot jail where
     *   the home directory is implicit. CWD + MKD per segment is universally
     *   supported and avoids all path resolution ambiguity.
     *
     * Algorithm:
     *   For path "/Saurabh/target/EENav_Custom1":
     *   1. CWD /          -- go to root
     *   2. CWD Saurabh    -- if fails: MKD Saurabh then CWD Saurabh
     *   3. CWD target     -- if fails: MKD target  then CWD target
     *   4. CWD EENav_Custom1 -- if fails: MKD EENav_Custom1 then CWD EENav_Custom1
     *   Result: directory guaranteed to exist, CWD is now at deepest level
     */
    private void makeDirectoriesFTP(String remotePath) throws IOException {
        if (remotePath == null || remotePath.isEmpty()) return;

        LOG.info("Ensuring FTP directory exists: [{}]", remotePath);

        // Start from root
        if (!ftpClient.changeWorkingDirectory("/")) {
            LOG.warn("Cannot CWD to root. Reply: {}", ftpClient.getReplyString().trim());
        }

        String[] parts = remotePath.replaceAll("^/+", "").replaceAll("/+$", "").split("/");

        for (String part : parts) {
            if (part == null || part.trim().isEmpty()) continue;

            // Try CWD into this segment -- if it exists we just move into it
            boolean cwdOk = ftpClient.changeWorkingDirectory(part);
            if (!cwdOk) {
                // Directory does not exist -- create it
                LOG.info("Creating FTP directory segment [{}] in [{}].",
                        part, ftpClient.printWorkingDirectory());
                boolean made = ftpClient.makeDirectory(part);
                int reply    = ftpClient.getReplyCode();

                if (made || reply == 257) {
                    // 257 = "pathname created"
                    LOG.info("Created FTP directory [{}]. Reply: {}",
                            part, ftpClient.getReplyString().trim());
                } else if (reply == 550 || reply == 521 || reply == 553) {
                    // Already exists codes -- safe to continue
                    LOG.debug("FTP directory [{}] already exists (reply={}). Continuing.", part, reply);
                } else {
                    LOG.warn("MKD [{}] reply={}. msg={}. Attempting to CWD anyway.",
                            part, reply, ftpClient.getReplyString().trim());
                }

                // CWD into the segment we just created (or that already existed)
                if (!ftpClient.changeWorkingDirectory(part)) {
                    throw new IOException("Cannot CWD into [" + part + "] after MKD. "
                            + "Reply: " + ftpClient.getReplyString().trim()
                            + " | Full path: " + remotePath);
                }
            }

            LOG.debug("CWD -> [{}]. PWD: {}", part, ftpClient.printWorkingDirectory());
        }

        // Final confirmation: log actual PWD after all segments walked
        LOG.info("Directory walk complete for [{}]. PWD=[{}].",
                remotePath, ftpClient.printWorkingDirectory());
        // Note: writeFile() always does a definitive absolute CWD after this
        // method returns, so any PWD mismatch here is corrected there.
    }

    /**
     * SFTP directory creation using sftpOps.buildDirectory().
     */
    private void makeDirectoriesSFTP(String remotePath) {
        if (remotePath == null || remotePath.isEmpty()) return;
        String[] parts = remotePath.replaceAll("^/+", "").replaceAll("/+$", "").split("/");
        StringBuilder cur = new StringBuilder("/");
        for (String part : parts) {
            if (part == null || part.trim().isEmpty()) continue;
            cur.append(part).append("/");
            String dirPath = cur.toString();
            try {
                // Try to stat -- if succeeds, directory exists
                sftpChannel.stat(dirPath);
                LOG.debug("SFTP directory exists: [{}].", dirPath);
            } catch (SftpException statEx) {
                // Does not exist -- create it
                try {
                    sftpChannel.mkdir(dirPath);
                    LOG.info("Created SFTP directory: [{}].", dirPath);
                } catch (SftpException mkdirEx) {
                    // May have been created by another thread, or already exists
                    LOG.debug("SFTP mkdir [{}] skipped (may exist): {}", dirPath, mkdirEx.getMessage());
                }
            }
        }
    }

    // =====================================================================
    // Add-On Configuration Helpers
    // =====================================================================

    /**
     * Parses addOnConfigurations string into a key-value map.
     * Format: "key1=value1&key2=value2&..."
     * Returns empty map if addOnConfigurations is null/empty.
     */
    private java.util.Map<String, String> parseAddOnConfig() {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        String raw = config.addOnConfigurations;
        if (raw == null || raw.trim().isEmpty()) return map;
        for (String pair : raw.split("&")) {
            pair = pair.trim();
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = pair.substring(0, eq).trim();
                String val = pair.substring(eq + 1).trim();
                if (!key.isEmpty()) {
                    map.put(key, val);
                    LOG.debug("AddOnConfig parsed: [{}]=[{}]", key, val);
                }
            }
        }
        return map;
    }

    /**
     * Returns a single value from addOnConfigurations, or defaultValue if absent.
     */
    private String getAddOnValue(String key, String defaultValue) {
        return parseAddOnConfig().getOrDefault(key, defaultValue);
    }

    /**
     * Returns an integer value from addOnConfigurations, or defaultValue if absent/invalid.
     */
    private int getAddOnInt(String key, int defaultValue) {
        String val = getAddOnValue(key, null);
        if (val == null) return defaultValue;
        try { return Integer.parseInt(val); }
        catch (NumberFormatException e) {
            LOG.warn("AddOnConfig [{}]=[{}] is not a valid integer. Using default={}.", key, val, defaultValue);
            return defaultValue;
        }
    }

    /**
     * Applies addOnConfigurations to an FTPClient.
     *
     * Supported keys:
     *   connectTimeout=<ms>         TCP connect timeout
     *   dataTimeout=<ms>            data channel timeout
     *   defaultTimeout=<ms>         socket default timeout
     *   bufferSize=<bytes>          file transfer buffer size
     *   keepAliveTimeout=<seconds>  control channel keep-alive NOOP interval
     *   keepAliveReplyTimeout=<ms>  keep-alive reply wait timeout
     *   encoding=<charset>          control encoding (default UTF-8)
     */
    private void applyFTPAddOnConfig(FTPClient client) {
        java.util.Map<String, String> addOn = parseAddOnConfig();
        if (addOn.isEmpty()) return;

        LOG.info("Applying addOnConfigurations to FTPClient: {}", addOn);

        if (addOn.containsKey("connectTimeout")) {
            client.setConnectTimeout(Integer.parseInt(addOn.get("connectTimeout")));
        }
        if (addOn.containsKey("dataTimeout")) {
            client.setDataTimeout(Integer.parseInt(addOn.get("dataTimeout")));
        }
        if (addOn.containsKey("defaultTimeout")) {
            client.setDefaultTimeout(Integer.parseInt(addOn.get("defaultTimeout")));
        }
        if (addOn.containsKey("bufferSize")) {
            client.setBufferSize(Integer.parseInt(addOn.get("bufferSize")));
        }
        if (addOn.containsKey("keepAliveTimeout")) {
            client.setControlKeepAliveTimeout(Integer.parseInt(addOn.get("keepAliveTimeout")));
        }
        if (addOn.containsKey("keepAliveReplyTimeout")) {
            client.setControlKeepAliveReplyTimeout(Integer.parseInt(addOn.get("keepAliveReplyTimeout")));
        }
        if (addOn.containsKey("encoding")) {
            client.setControlEncoding(addOn.get("encoding"));
        }
    }

    /**
     * Applies addOnConfigurations to a JSch session Properties object.
     *
     * Any key not handled specifically is passed through directly to JSch config.
     * This allows full JSch configuration flexibility without code changes.
     *
     * Known JSch config keys:
     *   kex                  KEX algorithm list
     *   server_host_key      host key types
     *   cipher.s2c/c2s       cipher suites
     *   compression.s2c/c2s  compression (none/zlib)
     *   StrictHostKeyChecking yes/no
     *   PreferredAuthentications  auth method order
     *
     * Custom keys handled separately (not passed to JSch):
     *   connectTimeout, serverAliveInterval, privateKey, privateKeyPassphrase
     */
    private void applyAddOnConfigToJsch(Properties jschConfig) {
        java.util.Map<String, String> addOn = parseAddOnConfig();
        if (addOn.isEmpty()) return;

        LOG.info("Applying addOnConfigurations to JSch session: {}", addOn);

        // Keys handled separately -- skip here
        java.util.Set<String> skip = new java.util.HashSet<>(
                java.util.Arrays.asList("connectTimeout", "serverAliveInterval",
                        "privateKey", "privateKeyPassphrase"));

        for (java.util.Map.Entry<String, String> entry : addOn.entrySet()) {
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
     * Resolves an Adeptia Key Manager activity and loads the private key
     * file into JSch for public key authentication.
     *
     * Actual KeyManager class: com.adeptia.indigo.security.keymanager.KeyManager
     *
     * Fields used:
     *   getKeyFilePath()        -> absolute path to the private key file on disk
     *                             (resolved via PGPUtils.getKeyManagerFilePath())
     *   getPrivateKeyPassword() -> passphrase protecting the private key (may be null)
     *   getKeyType()            -> key type e.g. "SSH", "PGP" (we only handle SSH here)
     *   getKeyManagerType()     -> "PGP" or SSH variant (skip if "PGP")
     *
     * The key file is read directly from disk by JSch using addIdentity(filePath).
     * No in-memory byte loading is needed -- JSch handles PEM parsing itself.
     *
     * @param jsch      JSch instance to add the identity to
     * @param keyMgrId  Adeptia Key Manager activity ID
     * @throws Exception if Key Manager not found, key file missing, or wrong type
     */
    /**
     * Resolves an Adeptia Key Manager activity and loads the SSH private key
     * into JSch for public key authentication.
     *
     * KeyManager class: com.adeptia.indigo.security.keymanager.KeyManager
     *
     * Fields used from the actual KeyManager class:
     *
     *   getKeyManagerType()     -> "PGP" or null/empty (SSH when not PGP)
     *                             Skip if "PGP" -- only SSH keys work for SFTP
     *
     *   getKeyFilePath()        -> Relative path stored in DB
     *                             e.g. "keymanager/12345/id_rsa"
     *                             Must be resolved to absolute path via
     *                             PGPUtils.getKeyManagerFilePath(km) which
     *                             prepends ADEPTIA_HOME/ServerKernel/repository/
     *
     *   getPrivateKeyPassword() -> Passphrase protecting the private key
     *                             Stored encrypted in DB, decrypted by Adeptia
     *                             May be null/empty if key has no passphrase
     *
     *   getKeyType()            -> "SSH-RSA", "SSH-DSA", "SSH-ECDSA" etc.
     *                             Informational only -- JSch auto-detects format
     *
     *   getEncryptKeyFile()     -> true if the key file itself is encrypted
     *                             by Adeptia (separate from SSH passphrase)
     *                             If true, Adeptia must decrypt before JSch reads
     */
    private void applyKeyManagerToJsch(JSch jsch, String keyMgrId) throws Exception {

        // ?? Step 1: Resolve Key Manager from Adeptia DB ???????????????????
        com.adeptia.indigo.storage.EntityManager em =
                com.adeptia.indigo.storage.EntityManagerFactory.getEntityManager(
                        com.adeptia.indigo.security.keymanager.KeyManager.class,
                        com.adeptia.indigo.security.AuthUtil.getAdminSubject());

        com.adeptia.indigo.security.keymanager.KeyManager km =
                (com.adeptia.indigo.security.keymanager.KeyManager) em.retrieve(
                        new com.adeptia.indigo.storage.TypedEntityId(keyMgrId, "KeyManager"));

        if (km == null) {
            throw new IllegalArgumentException(
                    "Key Manager activity not found: [" + keyMgrId + "]");
        }

        // ?? Step 2: Validate key type ?????????????????????????????????????
        // PGP key managers are for encryption/signing, not for SSH auth
        String keyManagerType = km.getKeyManagerType();
        if ("PGP".equalsIgnoreCase(keyManagerType)) {
            throw new IllegalArgumentException(
                    "Key Manager [" + keyMgrId + "] is type=PGP. "
                            + "Only SSH key managers are supported for SFTP. "
                            + "Create a new Key Manager with an SSH private key.");
        }

        // ?? Step 3: Resolve absolute key file path ????????????????????????
        // km.getKeyFilePath() returns a relative path stored in DB.
        // PGPUtils.getKeyManagerFilePath() resolves it to the absolute path
        // under $ADEPTIA_HOME/ServerKernel/repository/keymanager/...
        // Fallback to getKeyFilePath() directly if PGPUtils is unavailable.
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
                    "Key Manager [" + keyMgrId + "] has no key file path. "
                            + "Upload a private key file in the Key Manager activity.");
        }

        // ?? Step 4: Verify key file exists and is readable ?????????????????
        java.io.File keyFile = new java.io.File(keyFilePath);
        if (!keyFile.exists()) {
            throw new java.io.FileNotFoundException(
                    "Key Manager [" + keyMgrId + "] key file not found: ["
                            + keyFilePath + "]. "
                            + "Verify the file exists on the Adeptia server at this path.");
        }
        if (!keyFile.canRead()) {
            throw new java.io.IOException(
                    "Key Manager [" + keyMgrId + "] key file not readable: ["
                            + keyFilePath + "]. Check file system permissions.");
        }

        // ?? Step 5: Get passphrase ?????????????????????????????????????????
        // getPrivateKeyPassword() returns the SSH key passphrase.
        // This is different from getEncryptKeyFile() which is Adeptia's own
        // encryption of the file at rest -- Adeptia handles that transparently.
        String passPhrase     = km.getPrivateKeyPassword();
        String keyType        = km.getKeyType();
        boolean encryptedFile = km.getEncryptKeyFile();

        LOG.info("Key Manager [{}] resolved: type={} keyType={} file=[{}] "
                        + "hasPassphrase={} encryptedAtRest={}",
                keyMgrId, keyManagerType, keyType, keyFilePath,
                passPhrase != null && !passPhrase.isEmpty() ? "yes" : "no",
                encryptedFile);

        // ?? Step 6: Read key file header to detect format ??????????????????
        // JSch supports: OpenSSH PEM (-----BEGIN RSA/EC/OPENSSH PRIVATE KEY-----)
        // JSch does NOT support: PuTTY PPK format (PuTTY-User-Key-File-2: ...)
        // Azure SFTP keys exported from Azure portal are typically PPK.
        // We detect PPK and convert to OpenSSH in-memory before passing to JSch.
        String  effectiveKeyPath   = keyFilePath;
        boolean tempFileCreated    = false;
        java.io.File tempKeyFile   = null;

        try {
            // Read first line to detect format
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
                // PPK detected -- convert to OpenSSH PEM in a temp file
                LOG.info("Key Manager [{}] key is PPK format. Converting to OpenSSH PEM...", keyMgrId);
                try {
                    com.jcraft.jsch.KeyPair kpair = com.jcraft.jsch.KeyPair.load(jsch, keyFilePath);
                    tempKeyFile = java.io.File.createTempFile("adeptia_sftp_key_", ".pem");
                    tempKeyFile.deleteOnExit();
                    // Write OpenSSH PEM (unencrypted) -- JSch will handle it
                    if (passPhrase != null && !passPhrase.isEmpty()) {
                        kpair.writePrivateKey(tempKeyFile.getAbsolutePath(),
                                passPhrase.getBytes());
                    } else {
                        kpair.writePrivateKey(tempKeyFile.getAbsolutePath());
                    }
                    kpair.dispose();
                    effectiveKeyPath = tempKeyFile.getAbsolutePath();
                    tempFileCreated  = true;
                    LOG.info("PPK converted to OpenSSH PEM at temp path [{}].", effectiveKeyPath);
                } catch (Exception ppkEx) {
                    LOG.error("PPK conversion failed for Key Manager [{}]: {}. "
                                    + "Please convert the key to OpenSSH PEM format using "
                                    + "PuTTYgen -> Conversions -> Export OpenSSH key, "
                                    + "then re-upload to Key Manager.",
                            keyMgrId, ppkEx.getMessage());
                    throw new java.io.IOException(
                            "PPK key conversion failed for Key Manager [" + keyMgrId + "]: "
                                    + ppkEx.getMessage()
                                    + ". Convert key to OpenSSH PEM and re-upload.", ppkEx);
                }
            } else {
                LOG.info("Key Manager [{}] key format: OpenSSH/PEM (not PPK). Using as-is.",
                        keyMgrId);
            }

            // ?? Step 7: Load identity into JSch ??????????????????????????
            LOG.info("Loading identity into JSch from [{}] passphrase={}",
                    effectiveKeyPath, passPhrase != null && !passPhrase.isEmpty() ? "yes" : "no");
            if (passPhrase != null && !passPhrase.isEmpty()) {
                jsch.addIdentity(effectiveKeyPath, passPhrase);
            } else {
                jsch.addIdentity(effectiveKeyPath);
            }
            LOG.info("Key Manager [{}] identity loaded. JSch will use publickey auth.", keyMgrId);

        } finally {
            // Clean up temp file after JSch has loaded the identity into memory
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

    /** Returns the parent directory of a full remote path. */
    private static String getParentPath(String fullPath) {
        if (fullPath == null) return "/";
        int last = fullPath.lastIndexOf('/');
        return last > 0 ? fullPath.substring(0, last) : "/";
    }

    /** Returns the filename component of a full remote path. */
    private static String getFileName(String fullPath) {
        if (fullPath == null) return "";
        int last = fullPath.lastIndexOf('/');
        return last >= 0 ? fullPath.substring(last + 1) : fullPath;
    }
}