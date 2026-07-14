package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.CredentialSummaryDto;
import com.apimarketplace.orchestrator.domain.file.FileRef;
import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.services.file.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.mail.*;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.search.*;
import java.io.InputStream;
import java.util.*;

/**
 * EmailInbox node - reads messages and performs mailbox actions over IMAP using
 * platform credentials.
 *
 * <p>IMAP credentials (host, port, username, password, use_ssl) are stored in the
 * credential system (Settings &gt; Credentials &gt; IMAP), distinct from the SMTP
 * credential used by {@link SendEmailNode}. IMAP reads/acts on a mailbox; it never
 * SENDS mail - sending (incl. replying in-thread) stays on the {@code send_email} node.
 *
 * <p>Modes, selected by {@code action}:
 * <ul>
 *   <li>{@code none} (default) - READ: list messages (filters: unreadOnly, flaggedOnly,
 *       sinceDays/beforeDays, fromContains/subjectContains/bodyContains). Each message
 *       exposes its stable IMAP {@code uid}, body/bodyHtml, cc/replyTo, and attachments.</li>
 *   <li>{@code list_folders} - list the mailbox folder names.</li>
 *   <li>{@code mark_read|mark_unread|flag|unflag|move|delete} - act on the single message
 *       identified by {@code messageUid} ({@code targetFolder} required for move).</li>
 * </ul>
 */
public class EmailInboxNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(EmailInboxNode.class);
    private static final String IMAP_INTEGRATION = "imap";
    private static final int SNIPPET_LENGTH = 200;
    private static final int MAX_BODY_LENGTH = 50_000;
    private static final long MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024; // 25MB safety cap per attachment

    private final Core.EmailInboxConfig emailInboxConfig;
    private CredentialClient credentialClient;
    private FileStorageService fileStorageService;

    public EmailInboxNode(String nodeId, Core.EmailInboxConfig emailInboxConfig) {
        super(nodeId, NodeType.EMAIL_INBOX);
        this.emailInboxConfig = emailInboxConfig;
    }

    @Override
    public void acceptServices(ServiceRegistry registry) {
        super.acceptServices(registry);
        this.credentialClient = registry.getCredentialClient();
        this.fileStorageService = registry.getFileStorageService();
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        logger.info("EmailInbox node executing: nodeId={}, itemId={}", nodeId, context.itemId());

        Map<String, Object> resolvedParams = new LinkedHashMap<>();
        String action = emailInboxConfig != null ? emailInboxConfig.action() : "none";
        if (action == null || action.isBlank()) action = "none";
        String folderName = emailInboxConfig != null ? emailInboxConfig.folder() : "INBOX";
        if (folderName == null || folderName.isBlank()) folderName = "INBOX";

        Store store = null;
        Folder folder = null;
        try {
            if (credentialClient == null) {
                throw new IllegalStateException("CredentialClient is not available");
            }

            Long credentialId = emailInboxConfig != null ? emailInboxConfig.credentialId() : null;
            Optional<CredentialSummaryDto> imapCred;
            if (credentialId != null) {
                imapCred = credentialClient.getCredentialById(context.tenantId(), credentialId);
                if (imapCred.isEmpty()) {
                    logger.warn("Selected IMAP credential {} not found, falling back to default", credentialId);
                    imapCred = credentialClient.getDefaultCredential(context.tenantId(), IMAP_INTEGRATION);
                }
            } else {
                imapCred = credentialClient.getDefaultCredential(context.tenantId(), IMAP_INTEGRATION);
            }

            if (imapCred.isEmpty()) {
                throw new IllegalStateException(
                        "No IMAP credential configured. Configure an IMAP credential and set it on this node before running.");
            }

            Map<String, Object> cred = imapCred.get().getCredentialData();
            String imapHost = getString(cred, "host");
            int imapPort = getInt(cred, "port", 993);
            String imapUsername = getString(cred, "username");
            String imapPassword = getString(cred, "password");
            boolean useSsl = !"false".equalsIgnoreCase(getString(cred, "use_ssl"));

            if (imapHost == null || imapHost.isBlank()) {
                throw new IllegalArgumentException("IMAP host is missing in credential configuration");
            }

            resolvedParams.put("folder", folderName);
            resolvedParams.put("action", action);

            // Fail fast on missing single-message-action params BEFORE opening a connection.
            if (emailInboxConfig != null && emailInboxConfig.isMessageAction()) {
                String rawUid = emailInboxConfig.messageUid();
                if (rawUid == null || rawUid.isBlank()) {
                    throw new IllegalArgumentException("messageUid is required for action '" + action + "'");
                }
                if ("move".equals(action)) {
                    String target = emailInboxConfig.targetFolder();
                    if (target == null || target.isBlank()) {
                        throw new IllegalArgumentException("targetFolder is required for the move action");
                    }
                }
            }

            store = connect(imapHost, imapPort, imapUsername, imapPassword, useSsl);

            Map<String, Object> result;
            if ("list_folders".equals(action)) {
                result = listFolders(store);
            } else {
                folder = store.getFolder(folderName);
                if (folder == null || !folder.exists()) {
                    throw new IllegalArgumentException("IMAP folder not found: " + folderName);
                }
                if ("none".equals(action)) {
                    result = readMessages(folder, context, resolvedParams);
                } else {
                    result = applyAction(store, folder, action, context, resolvedParams);
                }
            }

            // MANDATORY metadata
            result.put("node_type", "EMAIL_INBOX");
            result.put("item_index", context.itemIndex());
            result.put("itemIndex", context.itemIndex());
            result.put("item_id", context.itemId());
            result.put("resolved_params", resolvedParams);
            result.put("success", true);

            logger.info("EmailInbox completed: nodeId={}, folder={}, action={}", nodeId, folderName, action);
            return NodeExecutionResult.success(nodeId, result);

        } catch (Exception e) {
            logger.error("EmailInbox execution failed: nodeId={}, error={}", nodeId, e.getMessage(), e);
            Map<String, Object> failOutput = new LinkedHashMap<>();
            failOutput.put("node_type", "EMAIL_INBOX");
            failOutput.put("item_index", context.itemIndex());
            failOutput.put("itemIndex", context.itemIndex());
            failOutput.put("item_id", context.itemId());
            failOutput.put("resolved_params", resolvedParams);
            failOutput.put("success", false);
            failOutput.put("error", e.getMessage());
            return NodeExecutionResult.failureWithOutput(nodeId, e.getMessage(), failOutput, 0L);
        } finally {
            closeQuietly(folder);
            closeQuietly(store);
        }
    }

    /** READ mode: list messages from the folder, applying search filters. */
    private Map<String, Object> readMessages(Folder folder, ExecutionContext context,
                                             Map<String, Object> resolvedParams) throws Exception {
        boolean markSeen = emailInboxConfig != null && emailInboxConfig.markSeen();
        boolean downloadAttachments = emailInboxConfig != null && emailInboxConfig.downloadAttachments();
        int limit = emailInboxConfig != null ? emailInboxConfig.limit() : 10;

        resolvedParams.put("unreadOnly", emailInboxConfig != null && emailInboxConfig.unreadOnly());
        resolvedParams.put("flaggedOnly", emailInboxConfig != null && emailInboxConfig.flaggedOnly());
        resolvedParams.put("limit", limit);

        folder.open(markSeen ? Folder.READ_WRITE : Folder.READ_ONLY);
        UIDFolder uidFolder = (UIDFolder) folder;

        SearchTerm term = buildSearchTerm();
        Message[] messages = (term != null) ? folder.search(term) : folder.getMessages();

        // Most-recent first, then cap to limit
        List<Message> selected = new ArrayList<>(Arrays.asList(messages));
        Collections.reverse(selected);
        if (selected.size() > limit) {
            selected = selected.subList(0, limit);
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Message msg : selected) {
            out.add(toMessageMap(msg, uidFolder, folder.getFullName(), context, downloadAttachments));
            if (markSeen) {
                msg.setFlag(Flags.Flag.SEEN, true);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("messages", out);
        result.put("count", out.size());
        result.put("folder", folder.getFullName());
        return result;
    }

    /** list_folders: return the mailbox folder names. */
    private Map<String, Object> listFolders(Store store) throws Exception {
        List<String> names = new ArrayList<>();
        for (Folder f : store.getDefaultFolder().list("*")) {
            if ((f.getType() & Folder.HOLDS_MESSAGES) != 0) {
                names.add(f.getFullName());
            }
        }
        Collections.sort(names);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("folders", names);
        result.put("count", names.size());
        return result;
    }

    /** ACTION mode: act on a single message identified by its UID. */
    private Map<String, Object> applyAction(Store store, Folder folder, String action,
                                            ExecutionContext context, Map<String, Object> resolvedParams)
            throws Exception {
        String uidStr = resolveExpression(
                emailInboxConfig != null ? emailInboxConfig.messageUid() : null, context);
        resolvedParams.put("messageUid", uidStr);
        if (uidStr == null || uidStr.isBlank()) {
            throw new IllegalArgumentException("messageUid is required for action '" + action + "'");
        }
        long uid;
        try {
            uid = Long.parseLong(uidStr.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("messageUid must be a numeric IMAP UID, got: " + uidStr);
        }

        folder.open(Folder.READ_WRITE);
        UIDFolder uidFolder = (UIDFolder) folder;
        Message msg = uidFolder.getMessageByUID(uid);
        if (msg == null) {
            throw new IllegalArgumentException(
                    "No message with UID " + uid + " in folder " + folder.getFullName());
        }

        switch (action) {
            case "mark_read" -> msg.setFlag(Flags.Flag.SEEN, true);
            case "mark_unread" -> msg.setFlag(Flags.Flag.SEEN, false);
            case "flag" -> msg.setFlag(Flags.Flag.FLAGGED, true);
            case "unflag" -> msg.setFlag(Flags.Flag.FLAGGED, false);
            case "delete" -> {
                msg.setFlag(Flags.Flag.DELETED, true);
                folder.expunge();
            }
            case "move" -> {
                String target = emailInboxConfig != null ? emailInboxConfig.targetFolder() : null;
                if (target == null || target.isBlank()) {
                    throw new IllegalArgumentException("targetFolder is required for the move action");
                }
                resolvedParams.put("targetFolder", target);
                Folder dest = store.getFolder(target);
                if (dest == null || !dest.exists()) {
                    throw new IllegalArgumentException("Destination folder not found: " + target);
                }
                folder.copyMessages(new Message[]{msg}, dest);
                msg.setFlag(Flags.Flag.DELETED, true);
                folder.expunge();
            }
            default -> throw new IllegalArgumentException("Unsupported email_inbox action: " + action);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", action);
        result.put("messageUid", uid);
        result.put("folder", folder.getFullName());
        return result;
    }

    // IPv6-blackhole caveat: the k3s prod cluster has no IPv6 egress route. If {@code host}
    // resolves to an AAAA record (e.g. imap.hostinger.com behind Cloudflare), Jakarta Mail
    // tries IPv6 first and blocks until the 10s connectiontimeout below, so the connect
    // "times out" against a perfectly reachable server. The fix lives at the JVM level, not
    // here: the orchestrator-service runs with -Djava.net.preferIPv4Stack=true (helm
    // values javaToolOptions -> JAVA_TOOL_OPTIONS) so name resolution/connect uses A
    // records only. Do NOT connect by resolved IPv4 literal instead: that would break TLS
    // hostname/SNI verification against the server certificate.
    private Store connect(String host, int port, String username, String password, boolean useSsl)
            throws MessagingException {
        Properties props = new Properties();
        String protocol = useSsl ? "imaps" : "imap";
        props.put("mail.store.protocol", protocol);
        props.put("mail." + protocol + ".host", host);
        props.put("mail." + protocol + ".port", String.valueOf(port));
        props.put("mail." + protocol + ".connectiontimeout", "10000");
        props.put("mail." + protocol + ".timeout", "10000");
        if (useSsl) {
            props.put("mail.imaps.ssl.enable", "true");
            props.put("mail.imaps.ssl.protocols", "TLSv1.2 TLSv1.3");
        } else {
            props.put("mail.imap.starttls.enable", "true");
        }

        Session session = Session.getInstance(props);
        Store store = session.getStore(protocol);
        store.connect(host, port, username, password);
        return store;
    }

    /** Builds an AND of all configured READ-mode IMAP search filters (null = no filter). */
    private SearchTerm buildSearchTerm() {
        if (emailInboxConfig == null) return null;
        List<SearchTerm> terms = new ArrayList<>();
        if (emailInboxConfig.unreadOnly()) {
            terms.add(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
        }
        if (emailInboxConfig.flaggedOnly()) {
            terms.add(new FlagTerm(new Flags(Flags.Flag.FLAGGED), true));
        }
        if (emailInboxConfig.sinceDays() > 0) {
            terms.add(new ReceivedDateTerm(ComparisonTerm.GE, daysAgo(emailInboxConfig.sinceDays())));
        }
        if (emailInboxConfig.beforeDays() > 0) {
            terms.add(new ReceivedDateTerm(ComparisonTerm.LE, daysAgo(emailInboxConfig.beforeDays())));
        }
        if (notBlank(emailInboxConfig.fromContains())) {
            terms.add(new FromStringTerm(emailInboxConfig.fromContains().trim()));
        }
        if (notBlank(emailInboxConfig.subjectContains())) {
            terms.add(new SubjectTerm(emailInboxConfig.subjectContains().trim()));
        }
        if (notBlank(emailInboxConfig.bodyContains())) {
            terms.add(new BodyTerm(emailInboxConfig.bodyContains().trim()));
        }
        if (terms.isEmpty()) return null;
        if (terms.size() == 1) return terms.get(0);
        return new AndTerm(terms.toArray(new SearchTerm[0]));
    }

    private static Date daysAgo(int days) {
        return new Date(System.currentTimeMillis() - (long) days * 24L * 60L * 60L * 1000L);
    }

    private Map<String, Object> toMessageMap(Message msg, UIDFolder uidFolder, String folderName,
                                             ExecutionContext context, boolean downloadAttachments)
            throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        long uid = uidFolder.getUID(msg);
        m.put("uid", uid);
        m.put("from", joinAddresses(msg.getFrom()));
        m.put("to", joinAddresses(msg.getRecipients(Message.RecipientType.TO)));
        m.put("cc", joinAddresses(msg.getRecipients(Message.RecipientType.CC)));
        m.put("replyTo", joinAddresses(msg.getReplyTo()));
        m.put("subject", msg.getSubject() != null ? msg.getSubject() : "");
        m.put("date", msg.getReceivedDate() != null ? msg.getReceivedDate().toInstant().toString()
                : (msg.getSentDate() != null ? msg.getSentDate().toInstant().toString() : null));
        m.put("seen", msg.isSet(Flags.Flag.SEEN));
        m.put("flagged", msg.isSet(Flags.Flag.FLAGGED));
        m.put("folder", folderName);
        String messageId = null;
        String references = null;
        if (msg instanceof MimeMessage mime) {
            messageId = mime.getMessageID();
            String[] refs = mime.getHeader("References");
            if (refs != null && refs.length > 0) references = String.join(" ", refs);
        }
        m.put("messageId", messageId);
        m.put("references", references);

        // Single MIME walk → plain text + html + attachments
        MessageContent content = new MessageContent(downloadAttachments);
        parseContent(msg, content, context);
        String plain = content.plain != null ? content.plain
                : (content.html != null ? htmlToText(content.html) : "");
        m.put("body", plain);
        m.put("bodyHtml", content.html);
        m.put("snippet", plain.length() > SNIPPET_LENGTH ? plain.substring(0, SNIPPET_LENGTH).trim() : plain.trim());
        m.put("attachments", content.attachments);
        m.put("hasAttachments", !content.attachments.isEmpty());
        return m;
    }

    /** Mutable accumulator for a single message's parsed content. */
    private static final class MessageContent {
        final boolean download;
        String plain;
        String html;
        final List<Map<String, Object>> attachments = new ArrayList<>();
        MessageContent(boolean download) { this.download = download; }
    }

    /** Walks a (possibly multipart) MIME part once, collecting text, html and attachments. */
    private void parseContent(Part part, MessageContent acc, ExecutionContext context) {
        try {
            String filename = part.getFileName();
            String disposition = part.getDisposition();
            boolean isAttachment = (disposition != null && disposition.equalsIgnoreCase(Part.ATTACHMENT))
                    || (filename != null && !filename.isBlank() && !part.isMimeType("multipart/*"));

            if (isAttachment) {
                acc.attachments.add(toAttachment(part, filename, acc.download, context));
                return;
            }
            if (part.isMimeType("text/plain")) {
                if (acc.plain == null) acc.plain = cap(String.valueOf(part.getContent()));
                return;
            }
            if (part.isMimeType("text/html")) {
                if (acc.html == null) acc.html = cap(String.valueOf(part.getContent()));
                return;
            }
            if (part.isMimeType("multipart/*")) {
                Multipart mp = (Multipart) part.getContent();
                for (int i = 0; i < mp.getCount(); i++) {
                    parseContent(mp.getBodyPart(i), acc, context);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to parse message part: {}", e.getMessage());
        }
    }

    private Map<String, Object> toAttachment(Part part, String filename, boolean download, ExecutionContext context) {
        Map<String, Object> att = new LinkedHashMap<>();
        String name;
        try {
            name = (filename != null) ? MimeUtility.decodeText(filename) : "attachment";
        } catch (Exception e) {
            name = filename != null ? filename : "attachment";
        }
        String contentType = "application/octet-stream";
        int size = -1;
        try {
            contentType = stripCtParams(part.getContentType());
            size = part.getSize();
        } catch (Exception ignored) { /* metadata best-effort */ }
        att.put("filename", name);
        att.put("contentType", contentType);
        att.put("size", size >= 0 ? size : null);

        if (download && fileStorageService != null) {
            try (InputStream is = part.getInputStream()) {
                byte[] bytes = readCapped(is, MAX_ATTACHMENT_BYTES);
                if (bytes != null) {
                    FileRef ref = fileStorageService.upload(
                            context.tenantId(), context.plan().getId(), context.runId(),
                            nodeId, name, contentType, bytes);
                    att.put("file", ref);
                    att.put("size", ref.size());
                } else {
                    att.put("downloadSkipped", "attachment exceeds " + (MAX_ATTACHMENT_BYTES / (1024 * 1024)) + "MB cap");
                }
            } catch (Exception e) {
                logger.warn("Failed to download attachment '{}': {}", name, e.getMessage());
                att.put("downloadError", e.getMessage());
            }
        }
        return att;
    }

    /** Reads up to {@code max} bytes; returns null if the stream exceeds the cap. */
    private static byte[] readCapped(InputStream is, long max) throws java.io.IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        long total = 0;
        while ((n = is.read(chunk)) != -1) {
            total += n;
            if (total > max) return null;
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    private static String stripCtParams(String contentType) {
        if (contentType == null) return "application/octet-stream";
        int semi = contentType.indexOf(';');
        return (semi >= 0 ? contentType.substring(0, semi) : contentType).trim().toLowerCase(Locale.ROOT);
    }

    private static String htmlToText(String html) {
        if (html == null) return "";
        return cap(html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim());
    }

    private static String joinAddresses(Address[] addresses) {
        if (addresses == null || addresses.length == 0) return "";
        StringJoiner sj = new StringJoiner(", ");
        for (Address a : addresses) sj.add(a.toString());
        return sj.toString();
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private static String cap(String s) {
        if (s == null) return "";
        return s.length() > MAX_BODY_LENGTH ? s.substring(0, MAX_BODY_LENGTH) : s;
    }

    private String resolveExpression(String expression, ExecutionContext context) {
        if (expression == null || expression.isBlank()) return null;
        if (templateAdapter != null) {
            try {
                Map<String, Object> toResolve = Map.of("__expr__", expression);
                Map<String, Object> resolved = templateAdapter.resolveTemplates(toResolve, context);
                Object result = resolved.get("__expr__");
                return result != null ? String.valueOf(result) : expression;
            } catch (Exception e) {
                logger.warn("Failed to resolve expression '{}': {}", expression, e.getMessage());
                return expression;
            }
        }
        return expression;
    }

    private static void closeQuietly(Folder folder) {
        if (folder != null && folder.isOpen()) {
            try {
                folder.close(true);
            } catch (Exception ignored) {
                // best effort
            }
        }
    }

    private static void closeQuietly(Store store) {
        if (store != null && store.isConnected()) {
            try {
                store.close();
            } catch (Exception ignored) {
                // best effort
            }
        }
    }

    private static String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? String.valueOf(val) : null;
    }

    private static int getInt(Map<String, Object> map, String key, int defaultVal) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return defaultVal; }
        }
        return defaultVal;
    }

    public Core.EmailInboxConfig getEmailInboxConfig() { return emailInboxConfig; }

    public static class Builder {
        private String nodeId;
        private Core.EmailInboxConfig emailInboxConfig;

        public Builder nodeId(String nodeId) { this.nodeId = nodeId; return this; }
        public Builder emailInboxConfig(Core.EmailInboxConfig emailInboxConfig) { this.emailInboxConfig = emailInboxConfig; return this; }
        public EmailInboxNode build() { return new EmailInboxNode(nodeId, emailInboxConfig); }
    }

    public static Builder builder() { return new Builder(); }
}
