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

import org.eclipse.angus.mail.imap.AppendUID;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPStore;

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
 *       exposes its stable IMAP {@code uid}, body/bodyHtml, cc/replyTo, and attachments.
 *       Messages flagged {@code \Deleted} (awaiting expunge) are logically gone and are
 *       NEVER returned: they are skipped BEFORE the limit is applied, so {@code count}
 *       and the limit budget cover live messages only.</li>
 *   <li>{@code list_folders} - list the mailbox folder names.</li>
 *   <li>{@code create_folder} - create the folder named by {@code targetFolder}. Idempotent:
 *       an already-existing folder is reported as {@code created=false}, not an error.</li>
 *   <li>{@code mark_read|mark_unread|flag|unflag|move|delete} - act on the single message
 *       identified by {@code messageUid} ({@code targetFolder} required for move; set
 *       {@code createTargetIfMissing} to create it on the fly).</li>
 * </ul>
 *
 * <p><b>delete/move only ever remove the TARGETED message.</b> When the server advertises
 * UIDPLUS, removal is a message-scoped {@code UID EXPUNGE} (see {@link #expungeScoped}).
 * Without UIDPLUS the message is only flagged {@code \Deleted} and the expunge is left to the
 * server/owner's client; the folder-wide {@link Folder#expunge()} is NEVER issued, because it
 * would also purge every message the mailbox OWNER had deleted from their own client and not
 * yet expunged - silent data loss unrelated to the requested action. The flag-only fallback is
 * safe for our own workflows because READ mode skips {@code \Deleted} messages.
 *
 * <p><b>move and UIDs:</b> the source {@code messageUid} is DEAD after a move. On a UIDPLUS
 * server the COPYUID response provides the message's UID in the target folder, surfaced as
 * {@code newMessageUid}; without UIDPLUS {@code newMessageUid} is absent and a downstream node
 * must re-read the target folder to find the message again.
 *
 * <p>Folder names are server paths, exactly as returned by {@code list_folders}: a server that
 * namespaces everything under the INBOX (Dovecot/cPanel) expects {@code INBOX.Clients}, not
 * {@code Clients}, and its hierarchy separator may be {@code .} rather than {@code /}. Both
 * {@code create_folder} and a {@code move} with {@code createTargetIfMissing} take the name
 * verbatim - they never rewrite or guess it.
 *
 * <p><b>Every string this node emits is stripped of NUL ({@code U+0000}) via {@code noNul}.</b>
 * Mail is arbitrary third-party bytes: one message carrying a NUL in its subject/body/headers made
 * the whole step output unstorable (PostgreSQL rejects NUL in text/JSONB with SQLSTATE 22P05,
 * "unsupported Unicode escape sequence"), which poisoned the transaction, dropped the output blob,
 * and left every downstream node reading an EMPTY output while the node still reported COMPLETED.
 * One poisoned mail therefore killed the entire folder read. Same source-level defence as
 * {@code DocumentTextExtractor}, which strips PDFBox NULs for the same reason.
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
        // Every user-supplied field is SpEL-resolved, not just messageUid: a {{template}} in
        // folder/targetFolder/*Contains used to reach IMAP as a literal string and silently
        // match nothing (or address the wrong folder).
        String folderName = resolveExpression(
                emailInboxConfig != null ? emailInboxConfig.folder() : null, context);
        if (folderName == null || folderName.isBlank()) folderName = "INBOX";
        // Trimmed ONCE here so create_folder and move can never diverge on the same value: an
        // untrimmed move would create a second, whitespace-named folder next to the trimmed one.
        String targetFolder = resolveExpression(
                emailInboxConfig != null ? emailInboxConfig.targetFolder() : null, context);
        if (targetFolder != null) targetFolder = targetFolder.trim();

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
            // Surfaced as soon as it is known, so a failure before the action still shows the
            // folder that was actually going to be addressed.
            if (notBlank(targetFolder)) resolvedParams.put("targetFolder", targetFolder);

            // Fail fast on missing single-message-action params BEFORE opening a connection.
            if (emailInboxConfig != null && emailInboxConfig.isMessageAction()) {
                // Guard on the RESOLVED value like targetFolder: a template resolving to blank is
                // an absent uid, and must fail here rather than after opening a connection.
                if (!notBlank(resolveExpression(emailInboxConfig.messageUid(), context))) {
                    throw new IllegalArgumentException("messageUid is required for action '" + action + "'");
                }
                if ("move".equals(action) && !notBlank(targetFolder)) {
                    throw new IllegalArgumentException("targetFolder is required for the move action");
                }
            }
            if ("create_folder".equals(action) && !notBlank(targetFolder)) {
                throw new IllegalArgumentException("targetFolder is required for the create_folder action");
            }

            store = connect(imapHost, imapPort, imapUsername, imapPassword, useSsl);

            Map<String, Object> result;
            if ("list_folders".equals(action)) {
                result = listFolders(store);
            } else if ("create_folder".equals(action)) {
                result = createFolder(store, targetFolder, resolvedParams);
            } else {
                folder = store.getFolder(folderName);
                if (folder == null || !folder.exists()) {
                    throw new IllegalArgumentException("IMAP folder not found: " + folderName);
                }
                if ("none".equals(action)) {
                    result = readMessages(folder, context, resolvedParams);
                } else {
                    result = applyAction(store, folder, action, targetFolder, context, resolvedParams);
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
            // Errors quote server-provided names (folder, uid, attachment), so they carry NULs too.
            String message = noNul(e.getMessage());
            failOutput.put("error", message);
            return NodeExecutionResult.failureWithOutput(nodeId, message, failOutput, 0L);
        } finally {
            closeQuietly(folder);
            closeQuietly(store);
        }
    }

    /**
     * READ mode: list messages from the folder, applying search filters. Messages flagged
     * {@code \Deleted} are excluded before the limit is applied, so {@code count} is the
     * number of LIVE messages returned and the limit is never spent on logically-gone mail.
     */
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

        // buildSearchTerm always ANDs an UNDELETED term, so the SERVER excludes \Deleted messages
        // and never returns them. This is the primary defence AND the performance guard: without
        // it, an unfiltered read pulls the WHOLE folder and the per-message isSet(DELETED) skip
        // below would trigger one FETCH (FLAGS) round trip per message (thousands on a big inbox).
        SearchTerm term = buildSearchTerm(context);
        Message[] messages = (term != null) ? folder.search(term) : folder.getMessages();

        // Bulk-prefetch FLAGS in ONE IMAP command so the \Deleted skip below reads cached flags
        // instead of issuing a per-message FETCH. Belt-and-braces on the already-filtered set (and
        // the only DELETED filter on the getMessages() path, if buildSearchTerm ever returns null).
        if (messages.length > 0) {
            FetchProfile profile = new FetchProfile();
            profile.add(FetchProfile.Item.FLAGS);
            folder.fetch(messages, profile);
        }

        // A message flagged \Deleted is logically gone (awaiting expunge) and must never be
        // re-processed by a workflow. Skipping it BEFORE the limit keeps the limit budget on
        // live messages, and it is what makes the no-UIDPLUS delete fallback (flag without
        // expunge, see expungeScoped) safe: our own reads never resurface the flagged message.
        List<Message> selected = new ArrayList<>(messages.length);
        for (Message candidate : messages) {
            if (!candidate.isSet(Flags.Flag.DELETED)) {
                selected.add(candidate);
            }
        }
        // Most-recent first, then cap to limit
        Collections.reverse(selected);
        if (selected.size() > limit) {
            selected = selected.subList(0, limit);
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Message msg : selected) {
            // Passed raw: toMessageMap owns the strip for the copy it emits on each message map.
            out.add(toMessageMap(msg, uidFolder, folder.getFullName(), context, downloadAttachments));
            if (markSeen) {
                msg.setFlag(Flags.Flag.SEEN, true);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("messages", out);
        result.put("count", out.size());
        // The server owns this name, so this result's own copy is stripped here.
        result.put("folder", noNul(folder.getFullName()));
        return result;
    }

    /** list_folders: return the mailbox folder names. */
    private Map<String, Object> listFolders(Store store) throws Exception {
        List<String> names = folderNames(store);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("folders", names);
        result.put("count", names.size());
        return result;
    }

    /** The mailbox's message-holding folder names, sorted. Shared by list_folders and error hints. */
    private List<String> folderNames(Store store) throws Exception {
        List<String> names = new ArrayList<>();
        for (Folder f : store.getDefaultFolder().list("*")) {
            if ((f.getType() & Folder.HOLDS_MESSAGES) != 0) {
                // Folder names are server-provided too, and they are emitted by list_folders,
                // create_folder and the "folders that exist" error hint.
                names.add(noNul(f.getFullName()));
            }
        }
        Collections.sort(names);
        return names;
    }

    /**
     * create_folder: create {@code name} verbatim (it is a server path, not a display label).
     * Idempotent - an existing folder yields {@code created=false} rather than an error, so the
     * action is safe to run on every workflow tick.
     */
    private Map<String, Object> createFolder(Store store, String name,
                                             Map<String, Object> resolvedParams) throws Exception {
        resolvedParams.put("targetFolder", name);
        // create_folder acts on targetFolder, not on the read folder: keep resolved_params.folder
        // in step with output.folder so an agent debugging via resolved_params is not misled.
        resolvedParams.put("folder", name);
        boolean created = ensureFolderExists(store.getFolder(name), name);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", "create_folder");
        result.put("folder", name);
        result.put("created", created);
        result.put("folders", folderNames(store));
        return result;
    }

    /**
     * Create {@code target} if it does not exist yet. Returns true when it was created, false when
     * it already existed - so callers can be idempotent. The name is used verbatim: IMAP servers
     * differ on both the namespace prefix and the hierarchy separator, so guessing here would
     * create a folder the caller never asked for.
     */
    private boolean ensureFolderExists(Folder target, String name) throws Exception {
        if (target == null) {
            throw new IllegalArgumentException("Invalid folder name: " + name);
        }
        if (target.exists()) {
            return false;
        }
        // HOLDS_MESSAGES | HOLDS_FOLDERS: a folder created as messages-only can be flagged
        // \NoInferiors by the server, which would forbid ever nesting under it later.
        if (!target.create(Folder.HOLDS_MESSAGES | Folder.HOLDS_FOLDERS)) {
            throw new IllegalStateException(
                "IMAP server refused to create folder: " + name
                    + ". Check the parent exists (creating 'INBOX.A.B' needs 'INBOX.A' first) and that"
                    + " the name is a server path as returned by action='list_folders'.");
        }
        return true;
    }

    /** ACTION mode: act on a single message identified by its UID. {@code target} is the already-resolved targetFolder. */
    private Map<String, Object> applyAction(Store store, Folder folder, String action, String target,
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
        // Server-owned name, stripped once for both the error message and the result.
        String fullName = noNul(folder.getFullName());
        Message msg = uidFolder.getMessageByUID(uid);
        if (msg == null) {
            throw new IllegalArgumentException(
                    "No message with UID " + uid + " in folder " + fullName);
        }

        Long newMessageUid = null;
        switch (action) {
            case "mark_read" -> msg.setFlag(Flags.Flag.SEEN, true);
            case "mark_unread" -> msg.setFlag(Flags.Flag.SEEN, false);
            case "flag" -> msg.setFlag(Flags.Flag.FLAGGED, true);
            case "unflag" -> msg.setFlag(Flags.Flag.FLAGGED, false);
            case "delete" -> {
                msg.setFlag(Flags.Flag.DELETED, true);
                expungeScoped(store, folder, msg);
            }
            case "move" -> {
                if (!notBlank(target)) {
                    throw new IllegalArgumentException("targetFolder is required for the move action");
                }
                resolvedParams.put("targetFolder", target);
                boolean createMissing = emailInboxConfig != null && emailInboxConfig.createTargetIfMissing();
                resolvedParams.put("createTargetIfMissing", createMissing);
                Folder dest = store.getFolder(target);
                if (dest == null || !dest.exists()) {
                    if (!createMissing) {
                        throw new IllegalArgumentException(
                            "Destination folder not found: " + target
                                + ". Existing folders: " + folderNames(store)
                                + ". Folder names are server paths (a server may namespace them under"
                                + " INBOX, e.g. INBOX.Clients). Create it first with action='create_folder',"
                                + " or set createTargetIfMissing=true on this move.");
                    }
                    ensureFolderExists(dest, target);
                }
                if (supportsUidPlus(store) && folder instanceof IMAPFolder imapSource) {
                    // COPYUID (UIDPLUS): the copy response carries the message's UID in the
                    // TARGET folder. The source uid dies with the move below, so this is the
                    // only handle a downstream node can act on afterwards.
                    AppendUID[] copied = imapSource.copyUIDMessages(new Message[]{msg}, dest);
                    if (copied != null && copied.length > 0 && copied[0] != null) {
                        newMessageUid = copied[0].uid;
                    }
                } else {
                    folder.copyMessages(new Message[]{msg}, dest);
                }
                msg.setFlag(Flags.Flag.DELETED, true);
                expungeScoped(store, folder, msg);
            }
            default -> throw new IllegalArgumentException("Unsupported email_inbox action: " + action);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", action);
        // For move this is the SOURCE uid, which is invalid once the move completes. When the
        // server supports UIDPLUS/COPYUID the live handle is newMessageUid (in targetFolder);
        // without it the field is absent and the target folder must be re-read to find the mail.
        result.put("messageUid", uid);
        if (newMessageUid != null) {
            result.put("newMessageUid", newMessageUid);
        }
        result.put("folder", fullName);
        return result;
    }

    /**
     * True when {@code store} is an IMAP store whose server advertises UIDPLUS (RFC 4315), i.e.
     * message-scoped {@code UID EXPUNGE} and {@code COPYUID} are available. A capability probe
     * failure counts as "no UIDPLUS": the safe degradation is flag-only, never a wider expunge.
     */
    private static boolean supportsUidPlus(Store store) {
        if (!(store instanceof IMAPStore imapStore)) {
            return false;
        }
        try {
            return imapStore.hasCapability("UIDPLUS");
        } catch (MessagingException e) {
            logger.warn("Could not query IMAP capabilities, assuming no UIDPLUS: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Expunge ONLY {@code msg}, never the whole folder. With UIDPLUS this issues a
     * {@code UID EXPUNGE} scoped to that one message. Without UIDPLUS the message is left
     * flagged {@code \Deleted} for the server/owner's client to expunge; falling back to the
     * folder-wide {@link Folder#expunge()} is forbidden, because it would permanently purge
     * EVERY {@code \Deleted}-flagged message in the folder - including mail the mailbox OWNER
     * deleted from their own client and had not expunged yet. That is silent data loss
     * unrelated to the requested action. The flag-only fallback is safe for our own workflows
     * because {@link #readMessages} skips {@code \Deleted} messages.
     */
    private static void expungeScoped(Store store, Folder folder, Message msg) throws MessagingException {
        if (supportsUidPlus(store) && folder instanceof IMAPFolder imapFolder) {
            imapFolder.expunge(new Message[]{msg});
        } else {
            logger.info("IMAP server lacks UIDPLUS; message left flagged \\Deleted without expunge"
                + " (the folder-wide expunge is never used: it would purge the owner's own deleted mail)");
        }
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

    /**
     * Builds an AND of the READ-mode IMAP search filters. Always includes an UNDELETED term so the
     * SERVER excludes \Deleted messages (never returned, never counted), which also keeps the read
     * off the per-message flag-fetch path on a large folder. Other filters (unreadOnly, flaggedOnly,
     * date range, from/subject/body) AND onto it. Never returns null.
     */
    private SearchTerm buildSearchTerm(ExecutionContext context) {
        List<SearchTerm> terms = new ArrayList<>();
        // \Deleted excluded server-side, unconditionally. A logically-deleted message is gone.
        terms.add(new FlagTerm(new Flags(Flags.Flag.DELETED), false));
        if (emailInboxConfig == null) return terms.get(0);
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
        String fromContains = resolveExpression(emailInboxConfig.fromContains(), context);
        if (notBlank(fromContains)) {
            terms.add(new FromStringTerm(fromContains.trim()));
        }
        String subjectContains = resolveExpression(emailInboxConfig.subjectContains(), context);
        if (notBlank(subjectContains)) {
            terms.add(new SubjectTerm(subjectContains.trim()));
        }
        String bodyContains = resolveExpression(emailInboxConfig.bodyContains(), context);
        if (notBlank(bodyContains)) {
            terms.add(new BodyTerm(bodyContains.trim()));
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
        m.put("subject", msg.getSubject() != null ? noNul(msg.getSubject()) : "");
        m.put("date", msg.getReceivedDate() != null ? msg.getReceivedDate().toInstant().toString()
                : (msg.getSentDate() != null ? msg.getSentDate().toInstant().toString() : null));
        m.put("seen", msg.isSet(Flags.Flag.SEEN));
        m.put("flagged", msg.isSet(Flags.Flag.FLAGGED));
        m.put("folder", noNul(folderName));
        String messageId = null;
        String references = null;
        if (msg instanceof MimeMessage mime) {
            messageId = mime.getMessageID();
            String[] refs = mime.getHeader("References");
            if (refs != null && refs.length > 0) references = String.join(" ", refs);
        }
        m.put("messageId", noNul(messageId));
        m.put("references", noNul(references));

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
            name = (filename != null) ? noNul(MimeUtility.decodeText(filename)) : "attachment";
        } catch (Exception e) {
            name = filename != null ? noNul(filename) : "attachment";
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
                // The message may quote the attachment name/type, so it inherits their NULs.
                att.put("downloadError", noNul(e.getMessage()));
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
        return noNul((semi >= 0 ? contentType.substring(0, semi) : contentType).trim().toLowerCase(Locale.ROOT));
    }

    private static String htmlToText(String html) {
        if (html == null) return "";
        return cap(html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim());
    }

    private static String joinAddresses(Address[] addresses) {
        if (addresses == null || addresses.length == 0) return "";
        StringJoiner sj = new StringJoiner(", ");
        for (Address a : addresses) sj.add(a.toString());
        // Chokepoint for from/to/cc/replyTo: an address header is third-party text like any other.
        return noNul(sj.toString());
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    /**
     * Remove NUL ({@code U+0000}) from a string that came off the mail server (or off a resolved
     * template), keeping every other character. PostgreSQL cannot store NUL in a text/JSONB column,
     * so a single one anywhere in this node's output makes the whole step output unpersistable.
     * Applied at the source, exactly like {@code DocumentTextExtractor} does for PDFBox output.
     */
    private static String noNul(String s) {
        return s == null ? null : s.replace("\u0000", "");
    }

    /** Chokepoint for body/bodyHtml (and, through them, snippet): NUL-stripped, then length-capped. */
    private static String cap(String s) {
        if (s == null) return "";
        // Strip BEFORE capping so the 50k budget is spent on real characters, not on NULs.
        String clean = noNul(s);
        return clean.length() > MAX_BODY_LENGTH ? clean.substring(0, MAX_BODY_LENGTH) : clean;
    }

    private String resolveExpression(String expression, ExecutionContext context) {
        if (expression == null || expression.isBlank()) return null;
        if (templateAdapter != null) {
            try {
                Map<String, Object> toResolve = Map.of("__expr__", expression);
                Map<String, Object> resolved = templateAdapter.resolveTemplates(toResolve, context);
                Object result = resolved.get("__expr__");
                // A resolved template carries UPSTREAM data (another node's output, a webhook body),
                // and every resolved value here is echoed into resolved_params, which is emitted.
                return result != null ? noNul(String.valueOf(result)) : expression;
            } catch (Exception e) {
                logger.warn("Failed to resolve expression '{}': {}", expression, e.getMessage());
                return expression;
            }
        }
        return expression;
    }

    /**
     * Close WITHOUT expunging. {@code close(true)} would permanently delete every message already
     * flagged DELETED in the folder - including ones the mailbox owner deleted from their own mail
     * client and that were still awaiting expunge - on every action and on every read opened
     * READ_WRITE (markSeen=true). That is silent data loss unrelated to the requested action.
     *
     * <p>The delete/move side of the same data-loss class is closed by {@link #expungeScoped}:
     * message-scoped {@code UID EXPUNGE} on UIDPLUS servers, flag-only otherwise. No code path
     * in this node performs a folder-wide expunge anymore.
     */
    private static void closeQuietly(Folder folder) {
        if (folder != null && folder.isOpen()) {
            try {
                folder.close(false);
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
