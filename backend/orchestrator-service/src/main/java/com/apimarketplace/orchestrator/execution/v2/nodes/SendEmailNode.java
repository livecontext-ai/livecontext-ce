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
            boolean smtpUseTls = "true".equalsIgnoreCase(getString(cred, "use_tls"));

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

            // Override fromName from node config if provided
            String nodeFromName = resolveExpression(
                    sendEmailConfig != null ? sendEmailConfig.fromName() : null, context);
            if (nodeFromName != null && !nodeFromName.isBlank()) {
                fromName = nodeFromName;
            }

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
                    props.put("mail.smtp.ssl.trust", smtpHost);
                }
            }

            props.put("mail.smtp.connectiontimeout", "10000");
            props.put("mail.smtp.timeout", "10000");
            props.put("mail.smtp.writetimeout", "10000");

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
            MimeMessage message = new MimeMessage(session);

            String senderEmail = fromEmail != null && !fromEmail.isBlank() ? fromEmail : smtpUsername;
            if (fromName != null && !fromName.isBlank()) {
                message.setFrom(new InternetAddress(senderEmail, fromName));
            } else {
                message.setFrom(new InternetAddress(senderEmail));
            }

            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail));

            if (ccEmail != null && !ccEmail.isBlank()) {
                message.setRecipients(Message.RecipientType.CC, InternetAddress.parse(ccEmail));
            }
            if (bccEmail != null && !bccEmail.isBlank()) {
                message.setRecipients(Message.RecipientType.BCC, InternetAddress.parse(bccEmail));
            }

            message.setSubject(subject);

            // Reply threading - In-Reply-To / References headers (resolved from config)
            String inReplyTo = resolveExpression(
                    sendEmailConfig != null ? sendEmailConfig.inReplyTo() : null, context);
            String references = resolveExpression(
                    sendEmailConfig != null ? sendEmailConfig.references() : null, context);
            if (inReplyTo != null && !inReplyTo.isBlank()) {
                message.setHeader("In-Reply-To", inReplyTo);
                // If no explicit References chain, seed it with the message being replied to
                if (references == null || references.isBlank()) {
                    references = inReplyTo;
                }
            }
            if (references != null && !references.isBlank()) {
                message.setHeader("References", references);
            }

            if (isHtml) {
                message.setContent(body != null ? body : "", "text/html; charset=utf-8");
            } else {
                message.setText(body != null ? body : "", "utf-8");
            }

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
            failOutput.put("error", e.getMessage());
            return NodeExecutionResult.failureWithOutput(nodeId, e.getMessage(), failOutput, 0L);
        }
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
