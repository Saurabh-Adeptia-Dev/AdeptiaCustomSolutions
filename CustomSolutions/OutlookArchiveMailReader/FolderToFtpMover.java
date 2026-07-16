package com.adeptia.archiveMailReader;

import com.adeptia.indigo.logging.Logger;
import com.adeptia.indigo.security.AuthUtil;
import com.adeptia.indigo.services.transport.ftp.Connector;
import com.adeptia.indigo.services.transport.ftp.ConnectorFactory;
import com.adeptia.indigo.services.transport.ftp.FtpTarget;
import com.adeptia.indigo.system.IndigoConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Uploads every immediate subfolder of a local root directory to the FTP server
 * described by an existing Adeptia FTP Target activity, mirroring each
 * subfolder's name and contents under the target's remote directory. Each
 * subfolder is deleted locally only after its own upload fully succeeds.
 *
 * Adeptia's CamelFTP connector sets {@code stepwise=false} on its upload
 * endpoint (CamelFTPEndpoint.UPLOAD), so a fileName containing "/" is sent to
 * the server as one literal (invalid) filename rather than being navigated as
 * a subdirectory. So each directory level is uploaded through its own
 * connector, whose remote directory (not the filename) carries the subfolder
 * path - that directory IS auto-created on connect, independent of stepwise.
 *
 * Subfolders are independent of each other (each opens its own connector), so
 * they're uploaded concurrently via a bounded thread pool rather than one at
 * a time.
 */
public class FolderToFtpMover {

    private static final int DEFAULT_CONCURRENCY = 4;

    public static void moveSubfoldersToFtp(String rootLocation, String ftpTargetActivityId) throws Exception {
        moveSubfoldersToFtp(rootLocation, ftpTargetActivityId, DEFAULT_CONCURRENCY);
    }

    /**
     * @param concurrency max number of subfolders uploaded in parallel; capped
     *                    at the subfolder count. Keep this within whatever
     *                    concurrent-connection limit the FTP server allows.
     */
    public static void moveSubfoldersToFtp(String rootLocation, String ftpTargetActivityId, int concurrency) throws Exception {
        Path root = Paths.get(rootLocation);
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Not a directory: " + rootLocation);
        }

        FtpTarget ftpTarget = IndigoConfig.getEntity(ftpTargetActivityId, FtpTarget.class);
        if (ftpTarget == null) {
            throw new IllegalStateException("FTP Target not found for id: " + ftpTargetActivityId);
        }

        List<Path> subfolders;
        try (Stream<Path> children = Files.list(root)) {
            subfolders = children.filter(Files::isDirectory).sorted().toList();
        }

        if (subfolders.isEmpty()) {
            Logger.getLogger().info("No subfolders found under " + rootLocation + "; nothing to move.");
            return;
        }

        String baseRemoteDir = trimTrailingSlash(ftpTarget.getRemoteFilePath());
        AtomicInteger moved = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        int threads = Math.max(1, Math.min(concurrency, subfolders.size()));
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Path subfolder : subfolders) {
                futures.add(executor.submit(() -> {
                    String remoteDir = baseRemoteDir + "/" + subfolder.getFileName();
                    if (uploadSubfolder(ftpTarget, subfolder, remoteDir)) {
                        try {
                            deleteRecursively(subfolder);
                            moved.incrementAndGet();
                        } catch (IOException e) {
                            Logger.getLogger().error("Uploaded but failed to delete local subfolder '"
                                    + subfolder.getFileName() + "': " + e.getMessage());
                            failed.incrementAndGet();
                        }
                    } else {
                        failed.incrementAndGet();
                    }
                }));
            }

            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    Logger.getLogger().error("Unexpected error while moving a subfolder: " + e.getCause());
                }
            }
        } finally {
            executor.shutdown();
        }

        Logger.getLogger().info("Moved " + moved.get() + " subfolder(s) to FTP"
                + (failed.get() > 0 ? "; " + failed.get() + " subfolder(s) failed and were left in place." : "."));
    }

    /**
     * Uploads every file under {@code localDir} (recursively), one connector per
     * directory level, targeting {@code remoteDir} as that connector's own
     * remote directory - files themselves are uploaded by bare name only.
     * Returns false (leaving the local folder untouched) if anything fails.
     */
    private static boolean uploadSubfolder(FtpTarget ftpTarget, Path localDir, String remoteDir) {
        String subfolderName = localDir.getFileName().toString();
        try {
            int uploaded = uploadDirectory(ftpTarget, localDir, remoteDir);
            Logger.getLogger().info("Uploaded subfolder '" + subfolderName + "' (" + uploaded + " file(s)) to FTP.");
            return true;
        } catch (Exception e) {
            Logger.getLogger().error("Failed to upload subfolder '" + subfolderName + "' to FTP: " + e.getMessage());
            return false;
        }
    }

    private static int uploadDirectory(FtpTarget ftpTarget, Path localDir, String remoteDir) throws Exception {
        List<Path> entries;
        try (Stream<Path> children = Files.list(localDir)) {
            entries = children.sorted().toList();
        }

        int uploaded = 0;
        List<Path> files = entries.stream().filter(Files::isRegularFile).toList();
        if (!files.isEmpty()) {
            Connector connector = buildConnector(ftpTarget, remoteDir);
            try {
                for (Path file : files) {
                    try (InputStream in = Files.newInputStream(file)) {
                        connector.upload(in, file.getFileName().toString());
                    }
                    uploaded++;
                }
            } finally {
                connector.close();
            }
        }

        for (Path entry : entries) {
            if (Files.isDirectory(entry)) {
                uploaded += uploadDirectory(ftpTarget, entry, remoteDir + "/" + entry.getFileName());
            }
        }
        return uploaded;
    }

    private static Connector buildConnector(FtpTarget ftpTarget, String remoteDir) throws Exception {
        return ConnectorFactory.getConnector(
                ftpTarget.getHostName(),
                ftpTarget.getPort(),
                ftpTarget.getFtpUserId(),
                ftpTarget.getPassword(),
                remoteDir,
                ftpTarget.getKeyManager(),
                ftpTarget.getFtpTimeout(),
                false,
                AuthUtil.getAdminSubject(),
                ftpTarget.getFtpsMode(),
                ftpTarget.getFtpProtectionLevel(),
                ftpTarget.getKeyStoreNameForFTP(),
                ftpTarget.getTransferMode(),
                ftpTarget.getTransferType(),
                ftpTarget.getFtpOverSSL(),
                ftpTarget.getSecured(),
                ftpTarget.getConnectorName(),
                null,
                false,
                ftpTarget.isFtpValidateServer(),
                ftpTarget.getAutoFolderCreation(),
                ftpTarget.getEntityName(),
                ftpTarget.isUseJ2SSH(),
                -1L);
    }

    private static String trimTrailingSlash(String path) {
        if (path == null) {
            return "";
        }
        return path.replaceAll("/+$", "");
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
