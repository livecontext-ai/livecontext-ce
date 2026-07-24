package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.CredentialSummaryDto;
import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.util.*;

/**
 * SendEmail node - Sends emails via SMTP using platform credentials.
 *
 * SMTP credentials (host, port, username, password, from_email, from_name, use_tls)
 * are stored in the credential system (Settings > Credentials > SMTP).
 *
 * The node config only contains per-email fields:
 * - toEmail (required), ccEmail, bccEmail, subject (required), body, isHtml
 */
public class SendEmailNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(SendEmailNode.class);
    private static final String SMTP_INTEGRATION = "smtp";

    private final Core.SendEmailConfig sendEmailConfig;
    private CredentialClient credentialClient;

    public SendEmailNode(String nodeId, Core.SendEmailConfig sendEmailConfig) {
        super(nodeId, NodeType.SEND_EMAIL);
        this.sendEmailConfig = sendEmailConfig;
    }

    @Override
    public void acceptServices(ServiceRegistry registry) {
        super.acceptServices(registry);
        this.credentialClient = registry.getCredentialClient();
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        logger.info("SendEmail node executing: nodeId={}, itemId={}", nodeId, context.itemId());

        // Captured outside the try so failure paths still surface the resolved inputs
        // to the inspector "Resolved parameters" panel.
        Map<String, Object> resolvedParams = new LinkedHashMap<>();

        try {
            // 1. Load SMTP credentials from credential system
            if (credentialClient == null) {
                throw new IllegalStateException("CredentialClient is not available");
            }

            // Use specific credential from plan if selected, otherwise fall back to default
            Long credentialId = sendEmailConfig != null ? sendEmailConfig.credentialId() : null;
            Optional<CredentialSummaryDto> smtpCred;
            if (credentialId != null) {
                smtpCred = credentialClient.getCredentialById(context.tenantId(), credentialId);
                if (smtpCred.isEmpty()) {
                    logger.warn("Selected SMTP credential {} not found, falling back to default", credentialId);
                    smtpCred = credentialClient.getDefaultCredential(context.tenantId(), SMTP_INTEGRATION);
                }
            } else {
                smtpCred = credentialClient.getDefaultCredential(context.tenantId(), SMTP_INTEGRATION);
            }

            if (smtpCred.isEmpty()) {
                throw new IllegalStateException(
                        "No SMTP credential configured. Configure an SMTP credential and set it on this node before running.");
            }

            Map<String, Object> cred = smtpCred.get().getCredentialData();
            String smtpHost = getString(cred, "host");
            int smtpPort = getInt(cred, "port", 587);
            String smtpUsername = getString(cred, "username");
            String smtpPassword = getString(cred, "password");
            String fromEmail = getString(cred, "from_email");
            String fromName = getString(cred, "from_name");
            boolean smtpUseTls = resolveUseTls(getString(cred, "use_tls"));
            // Surfaced so a failed send shows WHY the transport was chosen: "useTls=true" on a
            // relay with no STARTTLS is the whole diagnosis, and it is otherwise invisible.
            resolvedParams.put("useTls", smtpUseTls);

            // 2. Resolve per-email fields from node config via SpEL
            String toEmail = resolveExpression(
                    sendEmailConfig != null ? sendEmailConfig.toEmail() : null, context);
            String ccEmail = resolveExpression(
                    sendEmailConfig != null ? sendEmailConfig.ccEmail() : null, context);
            String bccEmail = resolveExpression(
                    sendEmailConfig != null ? sendEmailConfig.bccEmail() : null, context);
            String subject = resolveExpression(
                    sendEmailConfig != null ? sendEmailConfig.subject() : null, context);
            String body = resolveExpression(
                    sendEmailConfig != null ? sendEmailConfig.body() : null, context);
            boolean isHtml = sendEmailConfig != null && sendEmailConfig.isHtml();

            resolvedParams.put("toEmail", toEmail);
            resolvedParams.put("subject", subject);
            resolvedParams.put("isHtml", isHtml);

            // Override fromName / fromEmail from node config if provided. fromEmail was declared
            // and documented as the sender address but never read, so a node that set it silently
            // sent from the credential address instead.
            String nodeFromName = resolveExpression(
                    sendEmailConfig != null ? sendEmailConfig.fromName() : null, context);
            if (nodeFromName != null && !nodeFromName.isBlank()) {
                fromName = nodeFromName;
            }
            String nodeFromEmail = resolveExpression(
                    sendEmailConfig != null ? sendEmailConfig.fromEmail() : null, context);
            if (nodeFromEmail != null && !nodeFromEmail.isBlank()) {
                fromEmail = nodeFromEmail;
            }
            String replyTo = resolveExpression(
                    sendEmailConfig != null ? sendEmailConfig.replyTo() : null, context);
            // Surface both: a fromEmail the relay rejects is the likeliest cause of a failed send,
            // and it must be visible in resolved_params rather than guessed at.
            if (nodeFromEmail != null && !nodeFromEmail.isBlank()) resolvedParams.put("fromEmail", fromEmail);
            if (replyTo != null && !replyTo.isBlank()) resolvedParams.put("replyTo", replyTo);

            // 3. Validate required fields
            if (smtpHost == null || smtpHost.isBlank()) {
                throw new IllegalArgumentException("SMTP host is missing in credential configuration");
            }
            if (toEmail == null || toEmail.isBlank()) {
                throw new IllegalArgumentException("Recipient email (toEmail) is required");
            }
            if (subject == null || subject.isBlank()) {
                throw new IllegalArgumentException("Email subject is required");
            }

            // 4. Build SMTP session
            Properties props = buildSmtpProperties(smtpHost, smtpPort, smtpUsername, smtpUseTls);

            Session session;
            if (smtpUsername != null && !smtpUsername.isBlank()) {
                session = Session.getInstance(props, new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(smtpUsername, smtpPassword != null ? smtpPassword : "");
                    }
                });
            } else {
                session = Session.getInstance(props);
            }

            // 5. Build and send message
            String inReplyTo = resolveExpression(
                    sendEmailConfig != null ? sendEmailConfig.inReplyTo() : null, context);
            String references = resolveExpression(
                    sendEmailConfig != null ? sendEmailConfig.references() : null, context);

            MimeMessage message = buildMessage(session, new MessageFields(
                    fromEmail, fromName, smtpUsername, replyTo, toEmail, ccEmail, bccEmail,
                    subject, body, isHtml, inReplyTo, references));

            Transport.send(message);

            String messageId = message.getMessageID();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sent", true);
            result.put("messageId", messageId);
            result.put("recipients", toEmail);
            result.put("subject", subject);
            result.put("isHtml", isHtml);
            result.put("success", true);

            // MANDATORY metadata
            result.put("node_type", "SEND_EMAIL");
            result.put("item_index", context.itemIndex());
            result.put("itemIndex", context.itemIndex());
            result.put("item_id", context.itemId());
            result.put("resolved_params", resolvedParams);

            logger.info("SendEmail completed: nodeId={}, recipients={}, subject={}",
                    nodeId, toEmail, subject);
            return NodeExecutionResult.success(nodeId, result);

        } catch (Exception e) {
            logger.error("SendEmail execution failed: nodeId={}, error={}", nodeId, e.getMessage(), e);
            Map<String, Object> failOutput = new LinkedHashMap<>();
            failOutput.put("node_type", "SEND_EMAIL");
            failOutput.put("item_index", context.itemIndex());
            failOutput.put("itemIndex", context.itemIndex());
            failOutput.put("item_id", context.itemId());
            failOutput.put("resolved_params", resolvedParams);
            // sent/success must be present and FALSE on the failure path: a downstream
            // {{core:send.output.success}} check read null (not false) when they were omitted.
            failOutput.put("sent", false);
            failOutput.put("success", false);
            failOutput.put("error", e.getMessage());
            return NodeExecutionResult.failureWithOutput(nodeId, e.getMessage(), failOutput, 0L);
        }
    }

    /** The already-resolved per-email fields that {@link #buildMessage} turns into a MimeMessage. */
    record MessageFields(
        String fromEmail, String fromName, String smtpUsername, String replyTo,
        String toEmail, String ccEmail, String bccEmail,
        String subject, String body, boolean isHtml,
        String inReplyTo, String references
    ) {}

    /**
     * Assemble the MimeMessage from already-resolved fields. Extracted from execute() so the
     * header decisions (sender override, Reply-To, threading) are testable without a live SMTP
     * server: execute() only builds the session, calls this, and hands the result to Transport.
     */
    static MimeMessage buildMessage(Session session, MessageFields f) throws Exception {
        MimeMessage message = new MimeMessage(session);

        // A node-level fromEmail wins over the credential's from_email; the credential's username
        // is the last resort.
        String senderEmail = notBlank(f.fromEmail()) ? f.fromEmail() : f.smtpUsername();
        if (notBlank(f.fromName())) {
            message.setFrom(new InternetAddress(senderEmail, f.fromName()));
        } else {
            message.setFrom(new InternetAddress(senderEmail));
        }

        if (notBlank(f.replyTo())) {
            message.setReplyTo(InternetAddress.parse(f.replyTo()));
        }

        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(f.toEmail()));
        if (notBlank(f.ccEmail())) {
            message.setRecipients(Message.RecipientType.CC, InternetAddress.parse(f.ccEmail()));
        }
        if (notBlank(f.bccEmail())) {
            message.setRecipients(Message.RecipientType.BCC, InternetAddress.parse(f.bccEmail()));
        }

        message.setSubject(f.subject());

        // Reply threading - In-Reply-To / References headers.
        String references = f.references();
        if (notBlank(f.inReplyTo())) {
            message.setHeader("In-Reply-To", f.inReplyTo());
            // If no explicit References chain, seed it with the message being replied to.
            if (!notBlank(references)) {
                references = f.inReplyTo();
            }
        }
        if (notBlank(references)) {
            message.setHeader("References", references);
        }

        if (f.isHtml()) {
            message.setContent(f.body() != null ? f.body() : "", "text/html; charset=utf-8");
        } else {
            message.setText(f.body() != null ? f.body() : "", "utf-8");
        }
        return message;
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    /**
     * Resolve the credential's {@code use_tls} flag. Secure by default, matching the IMAP
     * credential: an ABSENT or unrecognised value means TLS on, and only an explicit "false" opts
     * out. The previous {@code "true".equalsIgnoreCase(...)} defaulted an absent key to false, so
     * a credential on any port other than 587/465 sent in CLEARTEXT with no warning.
     *
     * <p>Extracted from execute() so this decision is testable without a live SMTP server.
     */
    static boolean resolveUseTls(String credentialUseTls) {
        return !"false".equalsIgnoreCase(credentialUseTls);
    }

    /**
     * SMTP session properties. Extracted so the transport-security decisions are testable without
     * a live SMTP server: whether TLS is enabled at all, and that certificate validation is never
     * disabled.
     */
    static Properties buildSmtpProperties(String smtpHost, int smtpPort, String smtpUsername, boolean smtpUseTls) {
        Properties props = new Properties();
        props.put("mail.smtp.host", smtpHost);
        props.put("mail.smtp.port", String.valueOf(smtpPort));
        props.put("mail.smtp.auth", String.valueOf(smtpUsername != null && !smtpUsername.isBlank()));

        if (smtpUseTls || smtpPort == 587 || smtpPort == 465) {
            if (smtpPort == 465) {
                props.put("mail.smtp.ssl.enable", "true");
                props.put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3");
            } else {
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.starttls.required", "true");
                props.put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3");
                // Deliberately NO mail.smtp.ssl.trust: trusting the very host we are connecting to
                // disables certificate-chain validation, so a spoofed or self-signed server would
                // be accepted and handed the SMTP credentials. The 465 path never set it, and the
                // two paths must agree.
            }
        }

        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");
        return props;
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

    public Core.SendEmailConfig getSendEmailConfig() { return sendEmailConfig; }

    public static class Builder {
        private String nodeId;
        private Core.SendEmailConfig sendEmailConfig;

        public Builder nodeId(String nodeId) { this.nodeId = nodeId; return this; }
        public Builder sendEmailConfig(Core.SendEmailConfig sendEmailConfig) { this.sendEmailConfig = sendEmailConfig; return this; }
        public SendEmailNode build() { return new SendEmailNode(nodeId, sendEmailConfig); }
    }

    public static Builder builder() { return new Builder(); }
}
