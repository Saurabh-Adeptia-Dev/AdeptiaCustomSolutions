package com.adeptia.archiveMailReader;

import com.azure.identity.ClientSecretCredential;
import com.adeptia.indigo.logging.Logger;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.microsoft.graph.models.Attachment;
import com.microsoft.graph.models.AttachmentCollectionResponse;
import com.microsoft.graph.models.FileAttachment;
import com.microsoft.graph.models.Message;
import com.microsoft.graph.models.MessageCollectionResponse;
import com.microsoft.graph.serviceclient.GraphServiceClient;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Reads all messages from the Archive folder of a mailbox via Microsoft Graph,
 * saves each one as a .eml file, and extracts file attachments alongside.
 *
 * Nothing is hardcoded ? all configuration flows in through method parameters.
 * The main() entry point simply sources values (from env vars here) and passes
 * them to the methods.
 *
 * Required Azure AD app permissions (Application, admin-consented):
 *   - Mail.Read
 */
public class OutlookArchiveReader {



    // ---------------------------------------------------------------------
    // Auth
    // ---------------------------------------------------------------------
    /**
     * Builds a GraphServiceClient using the client-credentials (app-only) flow.
     * All secrets are passed as parameters ? nothing is stored in class state.
     */
    public static GraphServiceClient authenticate(String tenantId,
                                                  String clientId,
                                                  String clientSecret) {
        ClientSecretCredential credential = new ClientSecretCredentialBuilder()
                .tenantId(tenantId)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .build();

        String[] scopes = new String[] { "https://graph.microsoft.com/.default" };
        return new GraphServiceClient(credential, scopes);
    }

    // ---------------------------------------------------------------------
    // Mail processing
    // ---------------------------------------------------------------------
    /**
     * Pages through every message in the Archive folder of {@code userEmail},
     * writes each as a .eml file, and downloads its file attachments into
     * {@code saveLocation}.
     */
    public static void processArchiveFolder(GraphServiceClient graphClient,
                                            String userEmail,
                                            String saveLocation,
                                            int pageSize) throws IOException {
        Path root = Paths.get(saveLocation);
        if (!Files.exists(root)) {
            Files.createDirectories(root);
        }

        MessageCollectionResponse page = graphClient
                .users()
                .byUserId(userEmail)
                .mailFolders()
                .byMailFolderId("archive")
                .messages()
                .get(requestConfig -> {
                    requestConfig.queryParameters.select  = new String[] {
                            "id", "subject", "receivedDateTime", "hasAttachments", "internetMessageId"
                    };
                    requestConfig.queryParameters.top     = pageSize;
                    requestConfig.queryParameters.orderby = new String[] { "receivedDateTime desc" };
                });

        int processed = 0;
        int withAttachments = 0;
        while (page != null && page.getValue() != null) {
            List<Message> messages = page.getValue();
            for (Message message : messages) {
                processed++;
                boolean hasAtt = Boolean.TRUE.equals(message.getHasAttachments());
                Logger.getLogger().info("[" + processed + "] "
                        + (message.getReceivedDateTime() != null
                        ? message.getReceivedDateTime().format(DateTimeFormatter.ISO_INSTANT)
                        : "no-date")
                        + " | attach=" + (hasAtt ? "Y" : "N")
                        + " | " + message.getSubject());

                if (hasAtt) withAttachments++;
                saveMessage(graphClient, userEmail, message, root, hasAtt);
            }

            String nextLink = page.getOdataNextLink();
            if (nextLink == null || nextLink.isEmpty()) {
                break;
            }
            page = graphClient.users()
                    .byUserId(userEmail)
                    .mailFolders()
                    .byMailFolderId("archive")
                    .messages()
                    .withUrl(nextLink)
                    .get();
        }
        Logger.getLogger().info("Processed " + processed + " archive messages ("
                + withAttachments + " had attachments).");
    }

    /**
     * Downloads the raw MIME of the message to a .eml file and extracts any
     * file attachments alongside it.
     */
    public static void saveMessage(GraphServiceClient graphClient,
                                   String userEmail,
                                   Message message,
                                   Path root,
                                   boolean hasAtt) throws IOException {
        Path messageDir = root.resolve(buildFolderName(message));
        Files.createDirectories(messageDir);

        // 1. Raw MIME -> .eml
        Path emlFile = messageDir.resolve("message.eml");
        try (InputStream mime = graphClient
                .users()
                .byUserId(userEmail)
                .messages()
                .byMessageId(message.getId())
                .content()
                .get()) {
            if (mime != null) {
                Files.copy(mime, emlFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            Logger.getLogger().error("  (failed to fetch MIME for " + message.getId() + ": " + e.getMessage() + ")");
        }

        if (!hasAtt) {
            return;
        }

        // 2. Extract file attachments as loose files
        AttachmentCollectionResponse attachmentsPage = graphClient
                .users()
                .byUserId(userEmail)
                .messages()
                .byMessageId(message.getId())
                .attachments()
                .get();

        if (attachmentsPage == null || attachmentsPage.getValue() == null) {
            return;
        }

        for (Attachment attachment : attachmentsPage.getValue()) {
            if (attachment instanceof FileAttachment) {
                FileAttachment fa = (FileAttachment) attachment;
                byte[] bytes = fa.getContentBytes();
                if (bytes == null) {
                   Logger.getLogger().info("  (skipped, no content) " + fa.getName());
                    continue;
                }
                Path outFile = messageDir.resolve(sanitize(fa.getName()));
                try (FileOutputStream fos = new FileOutputStream(outFile.toFile())) {
                    fos.write(bytes);
                }
                Logger.getLogger().info("  saved -> " + outFile);
            } else {
                Logger.getLogger().info("  (skipped non-file attachment) " + attachment.getName());
            }
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------
    private static final DateTimeFormatter FOLDER_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS");

    private static String buildFolderName(Message message) {
        String subject = message.getSubject() == null ? "no-subject" : message.getSubject();
        String suffix = message.getReceivedDateTime() != null
                ? message.getReceivedDateTime().format(FOLDER_TIMESTAMP)
                : sanitize(uniquePart(message));
        return sanitize(subject) + "_" + suffix;
    }

    /**
     * Fallback disambiguator for the rare case a message has no receivedDateTime.
     * Prefers the email's actual Message-Id (globally unique, assigned by the
     * sending mail system) over the Graph id, whose head is shared across an
     * entire mailbox and so doesn't vary per message.
     */
    private static String uniquePart(Message message) {
        String shortened = shortenInternetMessageId(message.getInternetMessageId());
        if (shortened != null) {
            return shortened;
        }
        return message.getId() != null && message.getId().length() >= 8
                ? message.getId().substring(message.getId().length() - 8)
                : "id";
    }

    private static String shortenInternetMessageId(String internetMessageId) {
        if (internetMessageId == null || internetMessageId.isBlank()) {
            return null;
        }
        String stripped = internetMessageId.replace("<", "").replace(">", "");
        int at = stripped.indexOf('@');
        String localPart = at > 0 ? stripped.substring(0, at) : stripped;
        return localPart.length() > 20 ? localPart.substring(0, 20) : localPart;
    }

    private static String sanitize(String name) {
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        return cleaned.length() > 120 ? cleaned.substring(0, 120) : cleaned;
    }

}