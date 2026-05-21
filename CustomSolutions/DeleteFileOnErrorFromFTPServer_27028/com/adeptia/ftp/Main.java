package com.adeptia.ftp;

import java.util.List;

/**
 * Main
 * ----
 * Standalone entry point for testing FTPTransferPlugin outside Adeptia.
 * In production, FTPTransferPlugin.transfer() is called from BeanShell.
 *
 * Update srcFtpActivity and tgtFTPActivity with real FTPAccount activity IDs
 * before running.
 */
public class Main {

    public static void main(String[] args) throws Exception {

        // FTPAccount activity IDs -- get from Adeptia UI
        // Services -> FTP Account -> <account> -> copy ID from URL
        String srcFtpActivity = "1463129338641494016";
        String tgtFTPActivity = "1463129338641494016";

        String srcPath = "/Saurabh/Source/";
        String tgtBase = "/Saurabh/target/";
        String xmlPath = "D:\\Development\\AC4Codebase_New\\Shared\\web\\applets\\mapping\\multiValuedMap\\global\\SIC_EENAV.xml";

        boolean parallelMode = false;  // false = sequential (safer), true = parallel
        int     maxThreads   = 3;      // max concurrent threads (parallel mode only)

        // Execute transfer -- returns List<String> summary
        // Structure:
        //   [0]  TOTAL:<n>
        //   [1]  SUCCESS:<n>
        //   [2]  FAILED:<n>
        //   [3]  DELETED:<n>
        //   [4]  NOT_DELETED:<n>
        //   [5]  CONNECTIONS_OPENED:<n>
        //   [6]  RECONNECTS:<n>
        //   [7+] [OK] or [FAIL] per-file result lines
        List<String> results = AdeptiaFileRouter.transfer(
                srcFtpActivity,
                tgtFTPActivity,
                srcPath,
                tgtBase,
                xmlPath,
                parallelMode,
                maxThreads,
                true
        );

        // Parse counts from first 7 entries
        int total             = parseCount(results, 0);
        int success           = parseCount(results, 1);
        int failed            = parseCount(results, 2);
        int deleted           = parseCount(results, 3);
        int notDeleted        = parseCount(results, 4);
        int connectionsOpened = parseCount(results, 5);
        int reconnects        = parseCount(results, 6);

        System.out.println("=== Transfer Summary ===");
        System.out.println("Total              : " + total);
        System.out.println("Success            : " + success);
        System.out.println("Failed             : " + failed);
        System.out.println("Deleted            : " + deleted);
        System.out.println("Not Deleted        : " + notDeleted);
        System.out.println("Connections Opened : " + connectionsOpened);
        System.out.println("Reconnects         : " + reconnects);

        // Check for open connections -- should always be 0 after transfer
        int activeNow = FTPConnectionManager.getActiveConnectionCount();
        System.out.println("Active Connections : " + activeNow
                + (activeNow > 0 ? " *** LEAK ***" : " (clean)"));

        // Print failed files (entries from index 7 onwards starting with [FAIL])
        if (failed > 0) {
            System.out.println("\nFailed files:");
            for (int i = 7; i < results.size(); i++) {
                String line = results.get(i);
                if (line.startsWith("[FAIL]")) {
                    System.out.println("  " + line);
                }
            }
        }

        // Exit with error code if failures
        if (failed > 0 || activeNow > 0) {
            System.exit(1);
        }
    }

    private static int parseCount(List<String> results, int index) {
        try {
            if (index < results.size()) {
                return Integer.parseInt(results.get(index).split(":")[1]);
            }
        } catch (Exception ignored) {}
        return 0;
    }
}