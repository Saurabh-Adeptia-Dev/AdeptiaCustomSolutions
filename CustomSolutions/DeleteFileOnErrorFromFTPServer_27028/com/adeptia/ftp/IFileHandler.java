package com.adeptia.ftp;


import java.io.IOException;
import java.io.InputStream;

/**
 * IFileHandler
 * ?????????????
 * Common interface for both FTPFileHandler and SFTPFileHandler.
 * Allows FTPConnectionManager and FTPTransferPlugin to work
 * with either protocol transparently ? no protocol-specific
 * code anywhere except inside the two implementations.
 *
 * Implemented by:
 *   FTPFileHandler  ? Apache Commons Net (FTP / FTPS, port 21)
 *   SFTPFileHandler ? JSch (SFTP over SSH, port 22)
 */
public interface IFileHandler extends AutoCloseable {

    /**
     * Opens the connection and authenticates.
     * @throws IOException on connect or login failure
     */
    void connect() throws IOException;

    /**
     * Gracefully closes the connection. Idempotent ? safe to call multiple times.
     */
    void disconnect();

    /**
     * AutoCloseable ? delegates to disconnect().
     */
    void close();

    /**
     * Lists all entries in a remote directory.
     * @param remotePath  e.g. "/source/"
     * @return            array of file entries (empty array if none found)
     * @throws IOException on listing failure
     */
    FileEntry[] listFiles(String remotePath) throws IOException;

    /**
     * Opens a read stream for a remote file.
     * ?? For FTP: CONTROL channel is locked until completePendingCommand() is called.
     * ?? For SFTP: no pending command needed ? stream is independent.
     *
     * @param remoteFilePath  full remote path
     * @return                InputStream of file bytes
     * @throws IOException    if file cannot be opened
     */
    InputStream readFile(String remoteFilePath) throws IOException;

    /**
     * Must be called after readFile() InputStream is consumed.
     * FTP  : waits for 226 reply, releases control channel
     * SFTP : no-op (SFTP has no pending command concept)
     *
     * @throws IOException if FTP transfer did not complete
     */
    void completePendingCommand() throws IOException;

    /**
     * Writes content to a remote file. Auto-creates target directory.
     * Deletes existing target file first to allow overwrite.
     *
     * @param remoteDir       target directory
     * @param remoteFileName  target filename
     * @param content         file bytes
     * @return                true if write succeeded
     * @throws IOException    on write failure
     */
    boolean writeFile(String remoteDir, String remoteFileName,
                      InputStream content) throws IOException;

    /**
     * Deletes a remote file.
     * Never throws ? returns false on failure.
     *
     * @param remoteFilePath  full remote path
     * @return                true if deleted
     */
    boolean deleteFile(String remoteFilePath);

    /**
     * Sends a lightweight ping to check if the connection is alive.
     * FTP  : sends NOOP command
     * SFTP : sends SSH keepalive or stat on root
     *
     * @return  true if connection is alive
     * @throws IOException if ping could not be sent
     */
    boolean sendNoOp() throws IOException;

    /**
     * Configures keep-alive to prevent server-side idle timeout.
     * FTP  : sets controlKeepAliveTimeout
     * SFTP : sets ServerAliveInterval on SSH session
     *
     * @param intervalSeconds      how often to ping
     * @param replyTimeoutMillis   how long to wait for reply
     */
    void setKeepAlive(int intervalSeconds, int replyTimeoutMillis);

    /**
     * Returns true if currently connected and authenticated.
     */
    boolean isConnected();

    // ?? FileEntry ? protocol-neutral file listing entry ??????????????????

    /**
     * Protocol-neutral wrapper for a remote file entry.
     * Replaces FTPFile so SFTPFileHandler can return the same type.
     */
    class FileEntry {
        public final String  name;
        public final boolean isFile;
        public final boolean isDirectory;
        public final long    size;

        public FileEntry(String name, boolean isFile, boolean isDirectory, long size) {
            this.name        = name;
            this.isFile      = isFile;
            this.isDirectory = isDirectory;
            this.size        = size;
        }

        @Override
        public String toString() {
            return String.format("FileEntry{name='%s', isFile=%s, size=%d}",
                    name, isFile, size);
        }
    }
}