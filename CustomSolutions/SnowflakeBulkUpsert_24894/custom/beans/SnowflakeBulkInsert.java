package custom.beans;


import com.adeptia.indigo.api.custom.ScriptExecutor;
import com.adeptia.indigo.logging.Logger;
import com.adeptia.indigo.security.AuthUtil;
import com.adeptia.indigo.services.transport.connector.DatabaseConnectionInfo;
import com.adeptia.indigo.services.transport.db.DatabaseService;
import com.adeptia.indigo.api.custom.ExecutionEvent;
import com.adeptia.indigo.storage.EntityManager;
import com.adeptia.indigo.storage.EntityManagerFactory;
import com.adeptia.indigo.storage.TypedEntityId;
import com.adeptia.indigo.system.Context;
import com.adeptia.indigo.utils.JdbcUtils;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component("SnowflakeBulkInsert")
public class SnowflakeBulkInsert implements ScriptExecutor {

    private static final long LIST_INTERVAL_MS = 2000L;
    private static final long LIST_TIMEOUT_MS  = 300000L;

    private static final Pattern CSV_SPLIT_REGEX =
            Pattern.compile(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");

    private static final String CTX_BULK_INSERT_COMPLETED = "BulkInsertCompleted";
    private static final String CTX_ERROR_MESSAGE         = "errorMessage";

    @Override
    public void execute(ExecutionEvent event) {
        Context context = event.getContext();
        Logger logger   = event.getLogger();
        try {
            configureProxyFromContext(context, logger);
            logger.info("Effective JVM https.proxyHost=" + System.getProperty("https.proxyHost")
                    + ", https.proxyPort=" + System.getProperty("https.proxyPort")
                    + ", https.nonProxyHosts=" + System.getProperty("https.nonProxyHosts"));

            String connectionInfoId = (String) context.get("connectionInfoID");

            String tableName  = ((String) context.get("targetTableName")).toUpperCase();
            String schemaName = ((String) context.get("targetSchemaName")).toUpperCase();
            String dbName     = ((String) context.get("databaseName")).toUpperCase();

            String csvFilePath = (String) context.get("filePath");

            String keyColumn = (String) context.get("keyColumn");
            String mergeKey = (keyColumn != null && !keyColumn.isBlank())
                    ? keyColumn.toUpperCase()
                    : null;

            int maxAttempts = 5;
            boolean autoCommit = true;

            Path csvPath = Paths.get(csvFilePath);
            if (!Files.exists(csvPath) || !Files.isReadable(csvPath)) {
                String msg = "CSV file not found or unreadable: " + csvFilePath;
                logger.error(msg);
                context.put(CTX_BULK_INSERT_COMPLETED, Boolean.FALSE);
                context.put(CTX_ERROR_MESSAGE, "CSV file not found or unreadable: " + csvFilePath);
                return;
            }

            boolean success = runWithRetries(
                    () -> Boolean.valueOf(
                            runLoadAndUpsert(
                                    connectionInfoId,
                                    dbName,
                                    schemaName,
                                    tableName,
                                    csvFilePath,
                                    mergeKey,
                                    autoCommit,
                                    logger
                            )
                    ),
                    maxAttempts,
                    logger
            );

            context.put(CTX_BULK_INSERT_COMPLETED, Boolean.valueOf(success));

            if (!success && context.get(CTX_ERROR_MESSAGE) == null) {
                context.put(CTX_ERROR_MESSAGE, "Bulk insert operation failed after retries.");
            }

        } catch (Exception ex) {
            logger.error("Bulk insert failed: " + ex.getMessage(), ex);
            context.put(CTX_BULK_INSERT_COMPLETED, Boolean.FALSE);
            context.put(CTX_ERROR_MESSAGE, "Bulk insert failed: " + ex.getMessage());
        }
    }

    private boolean runWithRetries(CheckedSupplier<Boolean> action, int maxAttempts, Logger logger) {
        long sleep = 2000L;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (Exception ex) {
                logger.error("Attempt " + attempt + " failed", ex);
                if (attempt == maxAttempts) return false;

                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                sleep = Math.min(sleep * 2, 30000L); // cap at 30s
            }
        }
        return false;
    }


    private boolean runLoadAndUpsert(
            String connectionInfoId,
            String databaseName,
            String schemaName,
            String tableName,
            String csvFilePath,
            String mergeKeyColumn,
            boolean autoCommit,
            Logger logger
    ) throws Exception {

        LocalDateTime start = LocalDateTime.now();
        logger.info(start.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                + " - Starting bulk load into "
                + databaseName + "." + schemaName + "." + tableName);

        TempNames tempNames = generateTempNames(tableName);

        String qualifiedTarget =
                "\"" + databaseName + "\".\"" + schemaName + "\".\"" + tableName + "\"";

        boolean success = false;

        try (Connection conn = openConnection(connectionInfoId);
             Statement stmt = conn.createStatement()) {

            conn.setAutoCommit(autoCommit);
            stmt.setQueryTimeout(900);

            stmt.execute("ALTER SESSION SET JDBC_QUERY_RESULT_FORMAT='JSON'");

            verifyTargetTable(stmt, databaseName, schemaName, tableName, logger);

            List<String> csvColumns = parseCsvHeader(csvFilePath, logger);
            if (csvColumns.isEmpty()) {
                throw new SQLException("No columns found in CSV header for file: " + csvFilePath);
            }
            logger.info("CSV Header Columns: " + String.valueOf(csvColumns));

            validateColumns(stmt, databaseName, schemaName, tableName, csvColumns, logger);

            SqlFragments sql = buildSqlFragments(csvColumns, mergeKeyColumn);

            stageFile(stmt, tempNames.stage, csvFilePath, logger);

            loadTempTable(
                    stmt,
                    tempNames.temp,
                    tempNames.stage,
                    qualifiedTarget,
                    mergeKeyColumn,
                    sql,
                    logger
            );

            Duration elapsed = Duration.between(start, LocalDateTime.now());
            logger.info("Bulk load completed in " + elapsed.toMillis() + " ms");

            success = true;
            return true;

        } finally {
            // cleanup uses a fresh connection and is guarded internally
            cleanupTempObjects(connectionInfoId, tempNames, logger);
        }
    }

    private Connection openConnection(String connectionInfoId) throws Exception {
        EntityManager em = EntityManagerFactory.getEntityManager(
                DatabaseConnectionInfo.class,
                AuthUtil.getAdminSubject()
        );

        DatabaseConnectionInfo info = (DatabaseConnectionInfo) em.retrieve(
                new TypedEntityId(connectionInfoId, "DatabaseConnectionInfo")
        );

        return JdbcUtils.getConnection((DatabaseService) info, AuthUtil.getAdminSubject());
    }

    private void verifyTargetTable(
            Statement stmt,
            String databaseName,
            String schemaName,
            String tableName,
            Logger logger
    ) throws SQLException {

        String sql = String.format(
                "SELECT TABLE_NAME FROM \"%s\".INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = '%s' AND TABLE_NAME = '%s'",
                databaseName.replace("\"", "\"\""),
                schemaName.replace("\"", "\"\""),
                tableName.replace("\"", "\"\"")
        );

        logger.debug("Verifying target table with query: " + sql);

        try (ResultSet rs = stmt.executeQuery(sql)) {
            if (!rs.next()) {
                throw new SQLException("Target table does not exist: "
                        + databaseName + "." + schemaName + "." + tableName);
            }
        }

        logger.info("Target table verified: " + databaseName + "." + schemaName + "." + tableName);
    }

    private List<String> parseCsvHeader(String csvFilePath, Logger logger) throws IOException {
        Path path = Paths.get(csvFilePath);
        try (BufferedReader br = Files.newBufferedReader(path)) {
            String headerLine = br.readLine();
            if (headerLine != null && !headerLine.trim().isEmpty()) {
                return CSV_SPLIT_REGEX.splitAsStream(headerLine)
                        .map(s -> s.trim()
                                .replaceAll("^\"|\"$", "")
                                .toUpperCase())
                        .collect(Collectors.toList());
            }
        }
        logger.warn("CSV appears empty or missing header: " + csvFilePath);
        return Collections.emptyList();
    }

    private void validateColumns(
            Statement stmt,
            String databaseName,
            String schemaName,
            String tableName,
            List<String> csvColumns,
            Logger logger
    ) throws SQLException {

        String sql = String.format(
                "SELECT COLUMN_NAME FROM \"%s\".INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = '%s' AND TABLE_NAME = '%s'",
                databaseName.replace("\"", "\"\""),
                schemaName.replace("\"", "\"\""),
                tableName.replace("\"", "\"\"")
        );

        logger.debug("Validating target columns with query: " + sql);

        Set<String> targetCols = new HashSet<>();

        try (ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                targetCols.add(rs.getString(1).toUpperCase());
            }
        }

        if (targetCols.isEmpty()) {
            throw new SQLException("No columns found for target table: "
                    + databaseName + "." + schemaName + "." + tableName);
        }

        List<String> missing = csvColumns.stream()
                .filter(col -> !targetCols.contains(col))
                .collect(Collectors.toList());

        if (!missing.isEmpty()) {
            throw new SQLException("CSV header columns missing in target table: "
                    + tableName + ". Missing=" + String.valueOf(missing)
                    + " TargetCols=" + String.valueOf(targetCols));
        }

        logger.info("All CSV headers successfully validated against target table columns.");
    }

    private SqlFragments buildSqlFragments(List<String> columns, String mergeKeyColumn) {

        // colList:  "C1","C2","C3"
        String colList = columns.stream()
                .map(c -> "\"" + c.replace("\"", "\"\"") + "\"")
                .collect(Collectors.joining(","));

        // updateSet excludes merge key if provided:
        // tgt."C1" = stg."C1", tgt."C2" = stg."C2"
        String updateSet = columns.stream()
                .filter(c -> mergeKeyColumn == null || !c.equalsIgnoreCase(mergeKeyColumn))
                .map(c -> String.format("tgt.\"%s\" = stg.\"%s\"",
                        c.replace("\"", "\"\""),
                        c.replace("\"", "\"\"")))
                .collect(Collectors.joining(","));

        // insertVals: stg."C1",stg."C2"
        String insertVals = columns.stream()
                .map(c -> "stg.\"" + c.replace("\"", "\"\"") + "\"")
                .collect(Collectors.joining(","));

        // insertCols == colList (same)
        return new SqlFragments(colList, updateSet, colList, insertVals);
    }

    private void stageFile(
            Statement stmt,
            String stageName,
            String csvFilePath,
            Logger logger
    ) throws SQLException, IOException, InterruptedException {

        String fileFormatOpts =
                "TYPE=CSV, SKIP_HEADER=1, FIELD_OPTIONALLY_ENCLOSED_BY='\"', TRIM_SPACE=TRUE, EMPTY_FIELD_AS_NULL=TRUE, NULL_IF=('')";

        String stageEsc = stageName.replace("\"", "\"\"");

        // exact string from constant pool:
        // "CREATE OR REPLACE STAGE \"\u0001\" FILE_FORMAT=(\u0001)"
        stmt.execute("CREATE OR REPLACE STAGE \"" + stageEsc + "\" FILE_FORMAT=(" + fileFormatOpts + ")");

        logger.info("Created stage: " + stageName);

        Path filePath = Paths.get(csvFilePath);
        String fileUri = filePath.toUri().toString();

        // exact string from constant pool:
        // "PUT \u0001 @\"\u0001\" AUTO_COMPRESS=TRUE OVERWRITE=TRUE"
        String putSql = "PUT " + fileUri + " @\"" + stageEsc + "\" AUTO_COMPRESS=TRUE OVERWRITE=TRUE";

        logger.info("Executing PUT command: " + putSql);

        boolean anyUploaded = false;

        try (ResultSet rs = stmt.executeQuery(putSql)) {
            logger.info("PUT command results:");
            while (rs.next()) {
                String status  = rs.getString("status");
                String source  = rs.getString("source");
                String target  = rs.getString("target");
                String message = rs.getString("message");

                logger.info("source=" + source + ", target=" + target
                        + ", status=" + status + ", message=" + message);

                if ("UPLOADED".equalsIgnoreCase(status)) {
                    anyUploaded = true;
                }
            }
        }
        catch (SQLException e) {
            logger.error("PUT failed. SQLState=" + e.getSQLState() + ", ErrorCode=" + e.getErrorCode()
                    + ", Message=" + e.getMessage(), e);

            Throwable t = e.getCause();
            int depth = 0;
            while (t != null && depth++ < 10) {
                logger.error("PUT cause[" + depth + "]: " + t.getClass().getName() + " - " + t.getMessage());
                t = t.getCause();
            }
            throw e;
        }

        if (!anyUploaded) {
            throw new SQLException("File upload via PUT command failed or reported no files uploaded for: " + csvFilePath);
        }

        long waited = 0L;

        // exact string from constant pool:
        // "LIST @\"\u0001\""
        String listSql = "LIST @\"" + stageEsc + "\"";
        logger.info("Waiting for stage listing to show uploaded file. LIST SQL: " + listSql);

        while (waited < LIST_TIMEOUT_MS) {
            try (ResultSet rs = stmt.executeQuery(listSql)) {
                if (rs.next()) {
                    logger.info("Found staged file in: " + stageName + " name=" + rs.getString("name"));
                    return;
                }
            }

            logger.debug("Stage listing not yet showing file for: " + stageName + ". Retrying...");
            Thread.sleep(LIST_INTERVAL_MS);
            waited += LIST_INTERVAL_MS;
        }

        throw new SQLException("Timed out waiting for staged file to appear in stage: " + stageName);
    }

    private void loadTempTable(
            Statement stmt,
            String tempTable,
            String stageName,
            String qualifiedTarget,
            String mergeKeyColumn,
            SqlFragments sql,
            Logger logger
    ) throws SQLException {

        String tempEsc = tempTable.replace("\"", "\"\"");

        // exact string from constant pool:
        // "CREATE OR REPLACE TEMP TABLE \"\u0001\" LIKE \u0001"
        stmt.execute("CREATE OR REPLACE TEMP TABLE \"" + tempEsc + "\" LIKE " + qualifiedTarget);
        logger.info("Created temp table: " + tempTable);

        // COPY INTO uses String.format constant
        String copySql = String.format(
                "COPY INTO \"%s\" (%s) FROM '@\"%s\"' ON_ERROR='ABORT_STATEMENT'",
                tempTable.replace("\"", "\"\""),
                sql.colList,
                stageName.replace("\"", "\"\"")
        );

        logger.info("Executing COPY INTO temporary table: " + copySql);

        long startMs = System.currentTimeMillis();
        long rowsLoaded = 0L;

        try (ResultSet rs = stmt.executeQuery(copySql)) {
            ResultSetMetaData md = rs.getMetaData();
            int colCount = md.getColumnCount();

            int rowsLoadedIdx = -1;
            for (int i = 1; i <= colCount; i++) {
                if ("rows_loaded".equalsIgnoreCase(md.getColumnLabel(i))) {
                    rowsLoadedIdx = i;
                    break;
                }
            }

            if (rowsLoadedIdx == -1 && colCount > 0) {
                logger.warn("Column 'rows_loaded' not found by label in COPY results. Rows loaded count from result might be inaccurate.");
            }

            boolean dumpedOnce = false;
            while (rs.next()) {
                if (rowsLoadedIdx != -1) {
                    rowsLoaded += rs.getLong(rowsLoadedIdx);
                } else if (!dumpedOnce) {
                    StringBuilder sb = new StringBuilder("Available columns in COPY result:");
                    for (int i = 1; i <= colCount; i++) {
                        sb.append(md.getColumnLabel(i)).append(i == colCount ? "" : ",");
                    }
                    logger.warn(sb.toString());
                }

                // dump first-row data (as per bytecode logic)
                if (!rs.isFirst() && dumpedOnce) {
                    continue;
                }
                StringBuilder row = new StringBuilder("COPY result (first row data):");
                for (int i = 1; i <= colCount; i++) {
                    row.append(md.getColumnLabel(i)).append("=")
                            .append(rs.getString(i)).append(" |");
                }
                logger.debug(row.toString().trim());
                dumpedOnce = true;
            }
        }

        long durationMs = System.currentTimeMillis() - startMs;
        logger.info("COPY INTO temporary table processed. Reported rows loaded from result (if 'rows_loaded' column was found): "
                + rowsLoaded + ". Duration: " + durationMs + "ms.");

        if (mergeKeyColumn == null) {
            String insertSql = String.format(
                    "INSERT INTO %s (%s) SELECT %s FROM \"%s\"",
                    qualifiedTarget,
                    sql.insertCols,
                    sql.insertCols,
                    tempTable.replace("\"", "\"\"")
            );

            logger.info("Executing INSERT INTO target table: " + insertSql);
            int inserted = stmt.executeUpdate(insertSql);

            logger.info("Inserted " + inserted + " rows into target table: " + qualifiedTarget);

            if (inserted == 0 && rowsLoaded > 0) {
                logger.warn("COPY might have loaded rows into temp table, but INSERT into target table resulted in 0 rows. Check data or conditions.");
            }

        } else {
            String mergeSql = String.format(
                    "MERGE INTO %s tgt USING \"%s\" stg ON tgt.\"%s\" = stg.\"%s\" " +
                            "WHEN MATCHED THEN UPDATE SET %s " +
                            "WHEN NOT MATCHED THEN INSERT (%s) VALUES (%s)",
                    qualifiedTarget,
                    tempTable.replace("\"", "\"\""),
                    mergeKeyColumn.replace("\"", "\"\""),
                    mergeKeyColumn.replace("\"", "\"\""),
                    sql.updateSet,
                    sql.insertCols,
                    sql.insertVals
            );

            logger.info("Executing MERGE INTO target table: " + mergeSql);
            int affected = stmt.executeUpdate(mergeSql);

            logger.info("MERGE affected " + affected + " rows in target table: " + qualifiedTarget);

            if (affected == 0 && rowsLoaded > 0) {
                logger.warn("COPY might have loaded rows into temp table, but MERGE into target table affected 0 rows. Check data or MERGE conditions.");
            }
        }
    }

    private void cleanupTempObjects(String connectionInfoId, TempNames temp, Logger logger) {
        logger.info("Cleaning up temp objects. Temp table: " + temp.temp + ", stage: " + temp.stage);
        try (Connection conn = openConnection(connectionInfoId);
             Statement stmt = conn.createStatement()) {

            // exact constants:
            // "DROP TABLE IF EXISTS \"\u0001\""
            // "DROP STAGE IF EXISTS \"\u0001\""
            stmt.execute("DROP TABLE IF EXISTS \"" + temp.temp.replace("\"", "\"\"") + "\"");
            logger.info("Dropped temp table: " + temp.temp);

            stmt.execute("DROP STAGE IF EXISTS \"" + temp.stage.replace("\"", "\"\"") + "\"");
            logger.info("Dropped stage: " + temp.stage);

        } catch (Exception ex) {
            logger.error("Cleanup failed: " + ex.getMessage(), ex);
        }
    }

    private TempNames generateTempNames(String tableName) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));

        String safe = tableName.replaceAll("[^a-zA-Z0-9_]", "_");

        String stage = "TEMP_STAGE_" + safe + "_" + ts;
        String temp  = "TEMP_" + safe + "_" + ts;

        return new TempNames(stage, temp);
    }

    /* ---------------- helper types ---------------- */

    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    private static final class TempNames {
        final String stage;
        final String temp;
        TempNames(String stage, String temp) {
            this.stage = stage;
            this.temp  = temp;
        }
    }

    private static final class SqlFragments {
        final String colList;
        final String updateSet;
        final String insertCols;
        final String insertVals;

        SqlFragments(String colList, String updateSet, String insertCols, String insertVals) {
            this.colList    = colList;
            this.updateSet  = updateSet;
            this.insertCols = insertCols;
            this.insertVals = insertVals;
        }
    }

    private void configureProxyFromContext(Context context, Logger logger) {

        String enabled = getCtx(context, "useProxy");         // true/false
        String host    = getCtx(context, "proxyHost");        // proxy.company.com
        String port    = getCtx(context, "proxyPort");        // 8080
        String nonProxyHosts = getCtx(context, "nonProxyHost"); // optional: a|b|c

        boolean useProxy = "true".equalsIgnoreCase(enabled)
                || "1".equals(enabled)
                || "yes".equalsIgnoreCase(enabled)
                || "y".equalsIgnoreCase(enabled);

        // Helper: clear all proxy-related JVM properties (inline, no extra method)
        final Runnable clearAll = () -> {
            System.clearProperty("java.net.useSystemProxies");
            System.clearProperty("http.proxyHost");
            System.clearProperty("http.proxyPort");
            System.clearProperty("https.proxyHost");
            System.clearProperty("https.proxyPort");
            System.clearProperty("http.nonProxyHosts");
            System.clearProperty("https.nonProxyHosts");
        };

        if (!useProxy) {
            clearAll.run();
            logger.info("Proxy is disabled (useProxy != true). Cleared JVM proxy properties.");
            return;
        }

        // Validate host/port presence
        if (host == null || host.isBlank() || port == null || port.isBlank()) {
            clearAll.run(); // prevent stale config
            throw new IllegalArgumentException("useProxy=true but proxyHost / proxyPort not provided.");
        }

        // Validate port numeric range
        final int portNum;
        try {
            portNum = Integer.parseInt(port.trim());
            if (portNum < 1 || portNum > 65535) {
                clearAll.run();
                throw new IllegalArgumentException("proxyPort out of range (1-65535): " + port);
            }
        } catch (NumberFormatException nfe) {
            clearAll.run();
            throw new IllegalArgumentException("proxyPort is not a valid integer: " + port, nfe);
        }

        // Set proxy properties
        System.setProperty("java.net.useSystemProxies", "true");
        System.setProperty("http.proxyHost", host);
        System.setProperty("http.proxyPort", String.valueOf(portNum));
        System.setProperty("https.proxyHost", host);
        System.setProperty("https.proxyPort", String.valueOf(portNum));

        // Merge nonProxyHosts if provided
        if (nonProxyHosts != null && !nonProxyHosts.isBlank()) {

            String cleaned = nonProxyHosts
                    .replaceAll("\\s+", "")          // remove spaces
                    .replaceAll("\\|{2,}", "|")      // collapse multiple pipes
                    .replaceAll("^\\|+|\\|+$", "");  // trim leading/trailing pipes

            if (!cleaned.isBlank()) {

                String existing = System.getProperty("http.nonProxyHosts"); // current JVM setting
                String merged;

                if (existing == null || existing.isBlank()) {
                    merged = cleaned;
                } else {
                    String ex = existing.replaceAll("\\s+", "")
                            .replaceAll("\\|{2,}", "|")
                            .replaceAll("^\\|+|\\|+$", "");
                    String in = cleaned;

                    java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
                    for (String t : ex.split("\\|")) if (t != null && !t.isBlank()) set.add(t.trim());
                    for (String t : in.split("\\|")) if (t != null && !t.isBlank()) set.add(t.trim());

                    merged = String.join("|", set);
                }

                System.setProperty("http.nonProxyHosts", merged);
                System.setProperty("https.nonProxyHosts", merged);

            } else {
                // Deterministic: if caller provided nonProxyHost but it collapses to blank, clear it
                System.clearProperty("http.nonProxyHosts");
                System.clearProperty("https.nonProxyHosts");
            }
        }
        // If nonProxyHost not provided: leave existing as-is (your current behavior)

        String nonProxyLog = (System.getProperty("https.nonProxyHosts") == null
                || System.getProperty("https.nonProxyHosts").isBlank())
                ? "<not set>"
                : System.getProperty("https.nonProxyHosts");

        logger.info("Proxy configured from context: host=" + host + ", port=" + portNum
                + ", effectiveNonProxyHosts=" + nonProxyLog);
    }



    private String getCtx(Context context, String key) {
        Object v = context.get(key);
        return (v == null) ? null : String.valueOf(v).trim();
    }


}
