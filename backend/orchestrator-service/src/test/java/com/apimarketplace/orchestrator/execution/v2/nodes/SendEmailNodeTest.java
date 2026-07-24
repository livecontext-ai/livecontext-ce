package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.credential.client.CredentialClient;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import com.apimarketplace.credential.client.dto.CredentialSummaryDto;
import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import static org.mockito.Mockito.when;

/**
 * Unit tests for SendEmailNode.
 * SendEmailNode sends emails via SMTP using user-provided credentials.
 *
 * Since actual email sending requires an SMTP server, tests focus on:
 * - Config validation (missing required fields)
 * - SpEL expression resolution
 * - Builder pattern
 * - Node metadata and type
 * - getNextNodes behavior
 * - Config defaults (port, TLS)
 *
 * Actual SMTP sending is NOT tested (would require a mock SMTP server).
 * The node will fail with a connection error when no real SMTP server is available,
 * which validates the error handling path.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SendEmailNode")
class SendEmailNodeTest {

    @Mock
    private WorkflowPlan mockPlan;

    @Mock
    private V2TemplateAdapter mockTemplateAdapter;

    @Mock
    private CredentialClient mockCredentialClient;

    @Mock
    private ServiceRegistry mockServiceRegistry;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("recipientEmail", "user@example.com");
        triggerData.put("emailSubject", "Hello World");

        context = ExecutionContext.create(
            "run-1",
            "workflow-run-1",
            "tenant-1",
            "item-1",
            0,
            triggerData,
            mockPlan
        );

        // Wire credential client into the service registry
        when(mockServiceRegistry.getCredentialClient()).thenReturn(mockCredentialClient);
    }

    /**
     * Creates a valid SMTP credential data map for use in tests.
     */
    private Map<String, Object> validSmtpCredentialData() {
        Map<String, Object> data = new HashMap<>();
        data.put("host", "smtp.example.com");
        data.put("port", 587);
        data.put("username", "user");
        data.put("password", "pass");
        data.put("use_tls", "true");
        data.put("from_email", "from@example.com");
        data.put("from_name", "From Name");
        return data;
    }

    /**
     * Creates a CredentialSummaryDto wrapping the given credential data map.
     */
    private CredentialSummaryDto credentialWith(Map<String, Object> data) {
        CredentialSummaryDto dto = new CredentialSummaryDto();
        dto.setId(1L);
        dto.setName("SMTP");
        dto.setIntegration("smtp");
        dto.setStatus("active");
        dto.setDefault(true);
        dto.setCredentialData(data);
        return dto;
    }

    /**
     * Wires the credential client into a SendEmailNode via acceptServices.
     * Also stubs getDefaultCredential to return the given credential data by default.
     */
    private void wireCredentialClient(SendEmailNode node, Map<String, Object> credData) {
        if (credData != null) {
            when(mockCredentialClient.getDefaultCredential(anyString(), eq("smtp")))
                .thenReturn(Optional.of(credentialWith(credData)));
        } else {
            when(mockCredentialClient.getDefaultCredential(anyString(), eq("smtp")))
                .thenReturn(Optional.empty());
        }
        node.acceptServices(mockServiceRegistry);
    }

    // ===============================================================
    // Constructor tests
    // ===============================================================

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should create SendEmailNode with nodeId and config")
        void shouldCreateSendEmailNodeWithNodeIdAndConfig() {
            Core.SendEmailConfig config = new Core.SendEmailConfig(
                "smtp.example.com", 587, "user", "pass", true,
                "from@example.com", "From Name", "to@example.com",
                null, null, "Subject", "Body", false, null
            , null, null, null);
            SendEmailNode node = new SendEmailNode("core:send_email", config);

            assertEquals("core:send_email", node.getNodeId());
            assertEquals(NodeType.SEND_EMAIL, node.getType());
            assertNotNull(node.getSendEmailConfig());
            assertEquals("smtp.example.com", node.getSendEmailConfig().smtpHost());
            assertEquals(587, node.getSendEmailConfig().smtpPort());
        }

        @Test
        @DisplayName("Should handle null config")
        void shouldHandleNullConfig() {
            SendEmailNode node = new SendEmailNode("core:send_email", null);

            assertEquals("core:send_email", node.getNodeId());
            assertNull(node.getSendEmailConfig());
        }

        @Test
        @DisplayName("Should default port to 587 when zero or negative")
        void shouldDefaultPortWhenZeroOrNegative() {
            Core.SendEmailConfig configZero = new Core.SendEmailConfig(
                "smtp.example.com", 0, null, null, false,
                null, null, "to@example.com", null, null, "Subject", "Body", false, null
            , null, null, null);
            assertEquals(587, configZero.smtpPort());

            Core.SendEmailConfig configNeg = new Core.SendEmailConfig(
                "smtp.example.com", -1, null, null, false,
                null, null, "to@example.com", null, null, "Subject", "Body", false, null
            , null, null, null);
            assertEquals(587, configNeg.smtpPort());
        }

        @Test
        @DisplayName("Should preserve positive port value")
        void shouldPreservePositivePort() {
            Core.SendEmailConfig config = new Core.SendEmailConfig(
                "smtp.example.com", 465, null, null, false,
                null, null, "to@example.com", null, null, "Subject", "Body", false, null
            , null, null, null);
            assertEquals(465, config.smtpPort());
        }

        @Test
        @DisplayName("Should auto-enable TLS for port 587")
        void shouldAutoEnableTlsForPort587() {
            Core.SendEmailConfig config = new Core.SendEmailConfig(
                "smtp.example.com", 587, null, null, false,
                null, null, "to@example.com", null, null, "Subject", "Body", false, null
            , null, null, null);
            assertTrue(config.smtpUseTls());
        }

        @Test
        @DisplayName("Should auto-enable TLS for port 465")
        void shouldAutoEnableTlsForPort465() {
            Core.SendEmailConfig config = new Core.SendEmailConfig(
                "smtp.example.com", 465, null, null, false,
                null, null, "to@example.com", null, null, "Subject", "Body", false, null
            , null, null, null);
            assertTrue(config.smtpUseTls());
        }

        @Test
        @DisplayName("Should preserve explicit TLS setting for custom ports")
        void shouldPreserveExplicitTlsSetting() {
            Core.SendEmailConfig configWithTls = new Core.SendEmailConfig(
                "smtp.example.com", 2525, null, null, true,
                null, null, "to@example.com", null, null, "Subject", "Body", false, null
            , null, null, null);
            assertTrue(configWithTls.smtpUseTls());

            Core.SendEmailConfig configNoTls = new Core.SendEmailConfig(
                "smtp.example.com", 25, null, null, false,
                null, null, "to@example.com", null, null, "Subject", "Body", false, null
            , null, null, null);
            assertFalse(configNoTls.smtpUseTls());
        }
    }

    // ===============================================================
    // Execution - missing required fields
    // ===============================================================

    @Nested
    @DisplayName("Missing required fields")
    class MissingRequiredFieldsTests {

        @Test
        @DisplayName("Should fail when config is null")
        void shouldFailWhenConfigIsNull() {
            SendEmailNode node = new SendEmailNode("core:send_email", null);
            // Wire credential service returning valid SMTP cred so we reach validation
            wireCredentialClient(node, validSmtpCredentialData());
            NodeExecutionResult result = node.execute(context);

            // With null config, toEmail and subject will be null -> validation fails
            assertTrue(result.isFailure());
        }

        @Test
        @DisplayName("Should fail when SMTP host is null")
        void shouldFailWhenSmtpHostIsNull() {
            Core.SendEmailConfig config = new Core.SendEmailConfig(
                null, 587, null, null, true,
                null, null, "to@example.com", null, null, "Subject", "Body", false, null
            , null, null, null);
            SendEmailNode node = new SendEmailNode("core:send_email", config);

            // Return credential with missing host
            Map<String, Object> credData = validSmtpCredentialData();
            credData.remove("host");
            wireCredentialClient(node, credData);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().orElse("").contains("SMTP host"));
        }

        @Test
        @DisplayName("Should fail when SMTP host is blank")
        void shouldFailWhenSmtpHostIsBlank() {
            Core.SendEmailConfig config = new Core.SendEmailConfig(
                "   ", 587, null, null, true,
                null, null, "to@example.com", null, null, "Subject", "Body", false, null
            , null, null, null);
            SendEmailNode node = new SendEmailNode("core:send_email", config);

            // Return credential with blank host
            Map<String, Object> credData = validSmtpCredentialData();
            credData.put("host", "   ");
            wireCredentialClient(node, credData);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().orElse("").contains("SMTP host"));
        }

        @Test
        @DisplayName("Should fail when toEmail is null")
        void shouldFailWhenToEmailIsNull() {
            Core.SendEmailConfig config = new Core.SendEmailConfig(
                null, 587, null, null, true,
                null, null, null, null, null, "Subject", "Body", false, null
            , null, null, null);
            SendEmailNode node = new SendEmailNode("core:send_email", config);
            wireCredentialClient(node, validSmtpCredentialData());

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().orElse("").contains("toEmail"));
        }

        @Test
        @DisplayName("Should fail when subject is null")
        void shouldFailWhenSubjectIsNull() {
            Core.SendEmailConfig config = new Core.SendEmailConfig(
                null, 587, null, null, true,
                null, null, "to@example.com", null, null, null, "Body", false, null
            , null, null, null);
            SendEmailNode node = new SendEmailNode("core:send_email", config);
            wireCredentialClient(node, validSmtpCredentialData());

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().orElse("").contains("subject"));
        }
    }

    // ===============================================================
    // SMTP connection error tests (no real SMTP server)
    // ===============================================================

    @Nested
    @DisplayName("SMTP connection errors")
    class SmtpConnectionErrorTests {

        @Test
        @DisplayName("Should fail gracefully when SMTP server is unreachable")
        void shouldFailGracefullyWhenSmtpUnreachable() {
            Core.SendEmailConfig config = new Core.SendEmailConfig(
                null, 587,
                null, null, true,
                null, null, "to@example.com",
                null, null, "Test Subject", "Test Body", false, null
            , null, null, null);
            SendEmailNode node = new SendEmailNode("core:send_email", config);

            // Credential with an unreachable host
            Map<String, Object> credData = validSmtpCredentialData();
            credData.put("host", "invalid-smtp-host.nonexistent.example");
            wireCredentialClient(node, credData);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertNotNull(result.errorMessage());
        }
    }

    // ===============================================================
    // HTML vs Plain text
    // ===============================================================

    @Nested
    @DisplayName("HTML vs Plain text config")
    class HtmlConfigTests {

        @Test
        @DisplayName("Should store isHtml=false for plain text")
        void shouldStoreIsHtmlFalseForPlainText() {
            Core.SendEmailConfig config = new Core.SendEmailConfig(
                "smtp.example.com", 587, null, null, true,
                null, null, "to@example.com", null, null, "Subject", "Plain body", false, null
            , null, null, null);
            assertFalse(config.isHtml());
        }

        @Test
        @DisplayName("Should store isHtml=true for HTML")
        void shouldStoreIsHtmlTrueForHtml() {
            Core.SendEmailConfig config = new Core.SendEmailConfig(
                "smtp.example.com", 587, null, null, true,
                null, null, "to@example.com", null, null, "Subject",
                "<html><body><h1>Hello</h1></body></html>", true, null
            , null, null, null);
            assertTrue(config.isHtml());
        }
    }

    // ===============================================================
    // CC/BCC config
    // ===============================================================

    @Nested
    @DisplayName("CC/BCC configuration")
    class CcBccConfigTests {

        @Test
        @DisplayName("Should store CC and BCC in config")
        void shouldStoreCcAndBccInConfig() {
            Core.SendEmailConfig config = new Core.SendEmailConfig(
                "smtp.example.com", 587, null, null, true,
                null, null, "to@example.com",
                "cc1@example.com,cc2@example.com",
                "bcc@example.com",
                "Subject", "Body", false, null
            , null, null, null);
            assertEquals("cc1@example.com,cc2@example.com", config.ccEmail());
            assertEquals("bcc@example.com", config.bccEmail());
        }

        @Test
        @DisplayName("Should handle null CC and BCC")
        void shouldHandleNullCcAndBcc() {
            Core.SendEmailConfig config = new Core.SendEmailConfig(
                "smtp.example.com", 587, null, null, true,
                null, null, "to@example.com", null, null, "Subject", "Body", false, null
            , null, null, null);
            assertNull(config.ccEmail());
            assertNull(config.bccEmail());
        }
    }

    // ===============================================================
    // Multiple recipients
    // ===============================================================

    @Nested
    @DisplayName("Multiple recipients")
    class MultipleRecipientsTests {

        @Test
        @DisplayName("Should store comma-separated recipients in config")
        void shouldStoreCommaSeparatedRecipients() {
            Core.SendEmailConfig config = new Core.SendEmailConfig(
                "smtp.example.com", 587, null, null, true,
                null, null, "user1@example.com,user2@example.com,user3@example.com",
                null, null, "Subject", "Body", false, null
            , null, null, null);
            assertEquals("user1@example.com,user2@example.com,user3@example.com", config.toEmail());
        }
    }

    // ===============================================================
    // SpEL resolution
    // ===============================================================

    @Nested
    @DisplayName("SpEL resolution")
    class SpelResolutionTests {

        @Test
        @DisplayName("Should resolve SpEL expressions in fields when template adapter is set")
        void shouldResolveSpelExpressions() {
            // Config with SpEL-like expressions (SMTP fields come from credential, not config)
            Core.SendEmailConfig config = new Core.SendEmailConfig(
                null, 587, null, null, true,
                null, null, "{{trigger:form.output.email}}", null, null,
                "{{trigger:form.output.subject}}", "Body content", false, null
            , null, null, null);
            SendEmailNode node = new SendEmailNode("core:send_email", config);
            node.setTemplateAdapter(mockTemplateAdapter);
            wireCredentialClient(node, validSmtpCredentialData());

            // Mock templateAdapter to resolve expressions
            when(mockTemplateAdapter.resolveTemplates(any(), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> templates = (Map<String, Object>) invocation.getArgument(0);
                    Map<String, Object> resolved = new HashMap<>();
                    for (Map.Entry<String, Object> entry : templates.entrySet()) {
                        String val = String.valueOf(entry.getValue());
                        if (val.contains("email")) {
                            resolved.put(entry.getKey(), "resolved@example.com");
                        } else if (val.contains("subject")) {
                            resolved.put(entry.getKey(), "Resolved Subject");
                        } else {
                            resolved.put(entry.getKey(), val);
                        }
                    }
                    return resolved;
                });

            // This will fail at Transport.send because no SMTP server,
            // but the SpEL resolution happens before that
            NodeExecutionResult result = node.execute(context);

            // Will fail because no SMTP server, but that means SpEL was resolved successfully
            // (if SpEL failed, we'd get a different error)
            assertTrue(result.isFailure());
            // The error should be about SMTP connection, not about missing fields
            assertFalse(result.errorMessage().orElse("").contains("is required"));
        }
    }

    // ===============================================================
    // Credential loading tests
    // ===============================================================

    @Nested
    @DisplayName("Credential loading")
    class CredentialLoadingTests {

        @Test
        @DisplayName("Should use specific credentialId when provided")
        void shouldUseSpecificCredentialId() {
            Core.SendEmailConfig config = new Core.SendEmailConfig(
                null, 587, null, null, true,
                null, null, "to@example.com", null, null, "Subject", "Body", false, 42L
            , null, null, null);
            SendEmailNode node = new SendEmailNode("core:send_email", config);

            Map<String, Object> credData = validSmtpCredentialData();
            when(mockCredentialClient.getCredentialById(anyString(), eq(42L)))
                .thenReturn(Optional.of(credentialWith(credData)));
            node.acceptServices(mockServiceRegistry);

            // Will fail at Transport.send (no real SMTP) but should reach that point
            NodeExecutionResult result = node.execute(context);

            verify(mockCredentialClient).getCredentialById(anyString(), eq(42L));
            verify(mockCredentialClient, never()).getDefaultCredential(anyString(), anyString());
            assertTrue(result.isFailure());
            // Error should be about SMTP connection, not about missing credential
            assertFalse(result.errorMessage().orElse("").contains("No SMTP credential"));
        }

        @Test
        @DisplayName("Should fall back to default credential when credentialId is null")
        void shouldFallBackToDefaultCredentialWhenIdIsNull() {
            Core.SendEmailConfig config = new Core.SendEmailConfig(
                null, 587, null, null, true,
                null, null, "to@example.com", null, null, "Subject", "Body", false, null
            , null, null, null);
            SendEmailNode node = new SendEmailNode("core:send_email", config);
            wireCredentialClient(node, validSmtpCredentialData());

            NodeExecutionResult result = node.execute(context);

            verify(mockCredentialClient, never()).getCredentialById(anyString(), any());
            verify(mockCredentialClient).getDefaultCredential("tenant-1", "smtp");
            // Fails at SMTP transport, not at credential loading
            assertTrue(result.isFailure());
            assertFalse(result.errorMessage().orElse("").contains("No SMTP credential"));
        }

        @Test
        @DisplayName("Should fail with clear message when no credential found")
        void shouldFailWhenNoCredentialFound() {
            Core.SendEmailConfig config = new Core.SendEmailConfig(
                null, 587, null, null, true,
                null, null, "to@example.com", null, null, "Subject", "Body", false, null
            , null, null, null);
            SendEmailNode node = new SendEmailNode("core:send_email", config);
            wireCredentialClient(node, null); // No credential returned

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().orElse("").contains("No SMTP credential"));
            // TC-005: the agent-facing error must not leak UI navigation (the agent has no UI to click).
            assertFalse(result.errorMessage().orElse("").contains("Go to Settings"));
            assertFalse(result.errorMessage().orElse("").contains("Settings >"));
        }

        @Test
        @DisplayName("Should fail when credential service is not wired")
        void shouldFailWhenCredentialServiceNotWired() {
            Core.SendEmailConfig config = new Core.SendEmailConfig(
                null, 587, null, null, true,
                null, null, "to@example.com", null, null, "Subject", "Body", false, null
            , null, null, null);
            // Do NOT call acceptServices - credentialService stays null
            SendEmailNode node = new SendEmailNode("core:send_email", config);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().orElse("").contains("CredentialClient is not available"));
        }
    }

    // ===============================================================
    // Builder tests
    // ===============================================================

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("Should create SendEmailNode using builder")
        void shouldCreateSendEmailNodeUsingBuilder() {
            Core.SendEmailConfig config = new Core.SendEmailConfig(
                "smtp.gmail.com", 587, "user@example.com", "app-password", true,
                "user@example.com", "Sender", "recipient@example.com",
                null, null, "Test Email", "Hello!", false, null
            , null, null, null);

            SendEmailNode node = SendEmailNode.builder()
                .nodeId("core:my_email")
                .sendEmailConfig(config)
                .build();

            assertEquals("core:my_email", node.getNodeId());
            assertEquals(NodeType.SEND_EMAIL, node.getType());
            assertEquals("smtp.gmail.com", node.getSendEmailConfig().smtpHost());
            assertEquals("recipient@example.com", node.getSendEmailConfig().toEmail());
        }

        @Test
        @DisplayName("Should create SendEmailNode with null config using builder")
        void shouldCreateSendEmailNodeWithNullConfigUsingBuilder() {
            SendEmailNode node = SendEmailNode.builder()
                .nodeId("core:email_node")
                .sendEmailConfig(null)
                .build();

            assertEquals("core:email_node", node.getNodeId());
            assertNull(node.getSendEmailConfig());
        }

        @Test
        @DisplayName("Should return builder from static method")
        void shouldReturnBuilderFromStaticMethod() {
            SendEmailNode.Builder builder = SendEmailNode.builder();
            assertNotNull(builder);
        }
    }

    // ===============================================================
    // getNextNodes() tests
    // ===============================================================

    @Nested
    @DisplayName("getNextNodes()")
    class GetNextNodesTests {

        @Test
        @DisplayName("Should return all successors on success")
        void shouldReturnAllSuccessorsOnSuccess() {
            SendEmailNode node = new SendEmailNode("core:send_email", null);

            ExecutionNode successor1 = createMockNode("mcp:next1");
            ExecutionNode successor2 = createMockNode("mcp:next2");
            node.addSuccessor(successor1);
            node.addSuccessor(successor2);

            NodeExecutionResult result = NodeExecutionResult.success("core:send_email", Map.of());

            List<ExecutionNode> nextNodes = node.getNextNodes(result);
            assertEquals(2, nextNodes.size());
        }

        @Test
        @DisplayName("Should return empty list on failure")
        void shouldReturnEmptyListOnFailure() {
            SendEmailNode node = new SendEmailNode("core:send_email", null);

            ExecutionNode successor = createMockNode("mcp:next");
            node.addSuccessor(successor);

            NodeExecutionResult result = NodeExecutionResult.failure("core:send_email", "Error");

            List<ExecutionNode> nextNodes = node.getNextNodes(result);
            assertTrue(nextNodes.isEmpty());
        }
    }

    // ===============================================================
    // onComplete() tests
    // ===============================================================

    @Nested
    @DisplayName("onComplete()")
    class OnCompleteTests {

        @Test
        @DisplayName("Should not throw exception on success result")
        void shouldNotThrowExceptionOnSuccessResult() {
            SendEmailNode node = new SendEmailNode("core:send_email", null);
            NodeExecutionResult result = NodeExecutionResult.success("core:send_email", Map.of());
            assertDoesNotThrow(() -> node.onComplete(context, result));
        }

        @Test
        @DisplayName("Should not throw exception on failure result")
        void shouldNotThrowExceptionOnFailureResult() {
            SendEmailNode node = new SendEmailNode("core:send_email", null);
            NodeExecutionResult result = NodeExecutionResult.failure("core:send_email", "Error");
            assertDoesNotThrow(() -> node.onComplete(context, result));
        }
    }

    // ===============================================================
    // Metadata tests
    // ===============================================================

    @Nested
    @DisplayName("Metadata")
    class MetadataTests {

        @Test
        @DisplayName("Should return correct metadata")
        void shouldReturnCorrectMetadata() {
            SendEmailNode node = new SendEmailNode("core:send_email", null);
            Map<String, Object> metadata = node.getMetadata();

            assertEquals("core:send_email", metadata.get("nodeId"));
            assertEquals("SEND_EMAIL", metadata.get("type"));
            assertEquals(0, metadata.get("successorCount"));
        }

        @Test
        @DisplayName("Should return correct type")
        void shouldReturnCorrectType() {
            SendEmailNode node = new SendEmailNode("core:send_email", null);
            assertEquals(NodeType.SEND_EMAIL, node.getType());
        }
    }

    // ===============================================================
    // Helper methods
    // ===============================================================

    private ExecutionNode createMockNode(String nodeId) {
        return new BaseNode(nodeId, NodeType.MCP) {
            @Override
            public NodeExecutionResult execute(ExecutionContext context) {
                return NodeExecutionResult.success(nodeId, Map.of());
            }
        };
    }

    @Nested
    @DisplayName("SMTP transport security")
    class SmtpTransportSecurityTests {

        @Test
        @DisplayName("regression: never disables certificate validation via mail.smtp.ssl.trust on the STARTTLS path")
        void starttlsDoesNotTrustTheHostBlindly() {
            // Pre-fix this path set mail.smtp.ssl.trust=<host>, which turns off certificate-chain
            // validation for that host: a spoofed or self-signed server is accepted and handed the
            // SMTP credentials. The 465 path never set it, so the two paths also disagreed.
            Properties props = SendEmailNode.buildSmtpProperties("smtp.example.com", 587, "user", true);

            assertNull(props.get("mail.smtp.ssl.trust"));
            assertEquals("true", props.get("mail.smtp.starttls.required"));
        }

        @Test
        @DisplayName("Should enable implicit SSL on port 465 without trusting the host blindly")
        void port465UsesImplicitSslAndNoBlindTrust() {
            Properties props = SendEmailNode.buildSmtpProperties("smtp.example.com", 465, "user", true);

            assertEquals("true", props.get("mail.smtp.ssl.enable"));
            assertNull(props.get("mail.smtp.ssl.trust"));
        }

        @Test
        @DisplayName("regression: a credential with NO use_tls key resolves to TLS on, not off")
        void absentUseTlsKeyDefaultsToOn() {
            // THE security fix. Pre-fix this was "true".equalsIgnoreCase(null) = false, and since
            // a custom port is neither 587 nor 465, buildSmtpProperties then added no TLS props at
            // all: the mail left in CLEARTEXT with no warning. Assert the resolution itself, on the
            // code path execute() actually takes.
            assertTrue(SendEmailNode.resolveUseTls(null));
            assertTrue(SendEmailNode.resolveUseTls(""));
            assertTrue(SendEmailNode.resolveUseTls("true"));
            assertTrue(SendEmailNode.resolveUseTls("yes"));
        }

        @Test
        @DisplayName("Only an explicit use_tls=false opts out, case-insensitively")
        void explicitFalseOptsOut() {
            assertFalse(SendEmailNode.resolveUseTls("false"));
            assertFalse(SendEmailNode.resolveUseTls("FALSE"));
            assertFalse(SendEmailNode.resolveUseTls("False"));
        }

        @Test
        @DisplayName("An absent use_tls key ends up enabling STARTTLS on a non-standard port")
        void absentUseTlsKeyYieldsStarttlsOnCustomPort() {
            // The two halves joined: resolution (absent -> true) feeding the props builder is what
            // stops the cleartext send on a port that is neither 587 nor 465.
            Properties props = SendEmailNode.buildSmtpProperties(
                "smtp.example.com", 2525, "user", SendEmailNode.resolveUseTls(null));

            assertEquals("true", props.get("mail.smtp.starttls.enable"));
            assertEquals("true", props.get("mail.smtp.starttls.required"));
        }

        @Test
        @DisplayName("An explicit use_tls=false on a non-standard port still opts out of TLS")
        void explicitOptOutIsHonoured() {
            // Secure-by-default must stay overridable for a plaintext relay on a custom port.
            Properties props = SendEmailNode.buildSmtpProperties("smtp.example.com", 2525, "user", false);

            assertNull(props.get("mail.smtp.starttls.enable"));
            assertNull(props.get("mail.smtp.ssl.enable"));
        }

        @Test
        @DisplayName("Ports 587 and 465 keep TLS even when use_tls is explicitly false")
        void standardPortsAlwaysUseTls() {
            assertEquals("true",
                SendEmailNode.buildSmtpProperties("h", 587, "u", false).get("mail.smtp.starttls.enable"));
            assertEquals("true",
                SendEmailNode.buildSmtpProperties("h", 465, "u", false).get("mail.smtp.ssl.enable"));
        }

        @Test
        @DisplayName("regression: execute() feeds the CREDENTIAL's use_tls into the transport decision")
        void executeWiresCredentialUseTlsIntoTheTransport() {
            // resolveUseTls() being correct is not enough: execute() must actually read the
            // credential and use the result. Reverting the resolution to the old
            // "true".equalsIgnoreCase(...) leaves every isolated SMTP test green, so assert the
            // wiring through the resolved_params the node reports back.
            Map<String, Object> cred = validSmtpCredentialData();
            cred.remove("use_tls");        // the pre-existing credential shape that leaked cleartext
            cred.put("host", "127.0.0.1"); // refused fast: we assert on the decision, not on a send

            SendEmailNode node = new SendEmailNode("core:send", new Core.SendEmailConfig(
                null, 2525, null, null, false, null, null, "to@example.com",
                null, null, "Subject", "Body", false, null, null, null, null));
            wireCredentialClient(node, cred);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            Map<String, Object> resolved = (Map<String, Object>) result.output().get("resolved_params");
            assertEquals(true, resolved.get("useTls"),
                "an absent use_tls key must resolve to TLS ON by the time execute() picks a transport");
        }

        @Test
        @DisplayName("An explicit use_tls=false in the credential reaches the transport decision as false")
        void executeHonoursExplicitOptOutFromCredential() {
            Map<String, Object> cred = validSmtpCredentialData();
            cred.put("use_tls", "false");
            cred.put("host", "127.0.0.1");

            SendEmailNode node = new SendEmailNode("core:send", new Core.SendEmailConfig(
                null, 2525, null, null, false, null, null, "to@example.com",
                null, null, "Subject", "Body", false, null, null, null, null));
            wireCredentialClient(node, cred);

            NodeExecutionResult result = node.execute(context);

            Map<String, Object> resolved = (Map<String, Object>) result.output().get("resolved_params");
            assertEquals(false, resolved.get("useTls"));
        }

        @Test
        @DisplayName("Should disable auth when the credential has no username")
        void authOffWithoutUsername() {
            assertEquals("false", SendEmailNode.buildSmtpProperties("h", 587, "  ", true).get("mail.smtp.auth"));
            assertEquals("true", SendEmailNode.buildSmtpProperties("h", 587, "u", true).get("mail.smtp.auth"));
        }
    }

    @Nested
    @DisplayName("Message headers")
    class BuildMessageTests {

        private final Session session = Session.getInstance(new Properties());

        private SendEmailNode.MessageFields fields(String fromEmail, String replyTo, String to) {
            return new SendEmailNode.MessageFields(
                fromEmail, null, "creds@example.com", replyTo, to, null, null,
                "Subject", "Body", false, null, null);
        }

        @Test
        @DisplayName("buildMessage puts replyTo in the Reply-To header")
        void replyToIsApplied() throws Exception {
            MimeMessage m = SendEmailNode.buildMessage(session,
                fields(null, "team@example.com", "to@example.com"));

            assertEquals("team@example.com", ((InternetAddress) m.getReplyTo()[0]).getAddress());
        }

        @Test
        @DisplayName("replyTo accepts several comma-separated addresses")
        void replyToAcceptsMultiple() throws Exception {
            MimeMessage m = SendEmailNode.buildMessage(session,
                fields(null, "a@example.com, b@example.com", "to@example.com"));

            assertEquals(2, m.getReplyTo().length);
        }

        @Test
        @DisplayName("No replyTo leaves the header unset, so replies default to the sender")
        void noReplyToLeavesHeaderUnset() throws Exception {
            MimeMessage m = SendEmailNode.buildMessage(session, fields(null, null, "to@example.com"));

            // JavaMail falls back to From when Reply-To is absent.
            assertEquals("creds@example.com", ((InternetAddress) m.getReplyTo()[0]).getAddress());
            assertNull(m.getHeader("Reply-To"));
        }

        @Test
        @DisplayName("buildMessage prefers a node-level fromEmail over the credential address")
        void nodeFromEmailOverridesCredential() throws Exception {
            MimeMessage m = SendEmailNode.buildMessage(session,
                fields("alias@example.com", null, "to@example.com"));

            assertEquals("alias@example.com", ((InternetAddress) m.getFrom()[0]).getAddress());
        }

        @Test
        @DisplayName("Without fromEmail the credential username is the sender")
        void fallsBackToCredentialSender() throws Exception {
            MimeMessage m = SendEmailNode.buildMessage(session, fields(null, null, "to@example.com"));

            assertEquals("creds@example.com", ((InternetAddress) m.getFrom()[0]).getAddress());
        }

        @Test
        @DisplayName("References is seeded from inReplyTo when not given explicitly")
        void referencesSeededFromInReplyTo() throws Exception {
            MimeMessage m = SendEmailNode.buildMessage(session, new SendEmailNode.MessageFields(
                null, null, "creds@example.com", null, "to@example.com", null, null,
                "S", "B", false, "<orig@host>", null));

            assertEquals("<orig@host>", m.getHeader("In-Reply-To")[0]);
            assertEquals("<orig@host>", m.getHeader("References")[0]);
        }

        @Test
        @DisplayName("An explicit References chain is not overwritten by inReplyTo")
        void explicitReferencesWins() throws Exception {
            MimeMessage m = SendEmailNode.buildMessage(session, new SendEmailNode.MessageFields(
                null, null, "creds@example.com", null, "to@example.com", null, null,
                "S", "B", false, "<b@host>", "<a@host> <b@host>"));

            assertEquals("<a@host> <b@host>", m.getHeader("References")[0]);
        }
    }

    @Nested
    @DisplayName("Sender identity (fromEmail / replyTo)")
    class SenderIdentityTests {

        @Test
        @DisplayName("execute() surfaces the node's fromEmail in resolved_params (debug visibility)")
        void executeReadsFromEmailFromConfig() {
            // Scope: only that execute() reads the config and REPORTS it. That the address reaches
            // the actual message is a different claim, guarded on the SMTP wire by
            // SendEmailE2ETest#shouldWriteNodeFromEmailAndReplyToOntoTheWire - this test would
            // stay green if the field were read, surfaced, and then never wired into the message.
            Map<String, Object> cred = validSmtpCredentialData();
            cred.put("host", "127.0.0.1");

            SendEmailNode node = new SendEmailNode("core:send", new Core.SendEmailConfig(
                null, 587, null, null, true, "alias@example.com", null, "to@example.com",
                null, null, "Subject", "Body", false, null, null, null, null));
            wireCredentialClient(node, cred);

            NodeExecutionResult result = node.execute(context);

            Map<String, Object> resolved = (Map<String, Object>) result.output().get("resolved_params");
            assertEquals("alias@example.com", resolved.get("fromEmail"),
                "execute() must read sendEmailConfig.fromEmail and report it in resolved_params");
        }

        @Test
        @DisplayName("execute() surfaces the node's replyTo in resolved_params (debug visibility)")
        void executeReadsReplyToFromConfig() {
            Map<String, Object> cred = validSmtpCredentialData();
            cred.put("host", "127.0.0.1");

            SendEmailNode node = new SendEmailNode("core:send", new Core.SendEmailConfig(
                null, 587, null, null, true, null, null, "to@example.com",
                null, null, "Subject", "Body", false, null, null, null, "team@example.com"));
            wireCredentialClient(node, cred);

            NodeExecutionResult result = node.execute(context);

            Map<String, Object> resolved = (Map<String, Object>) result.output().get("resolved_params");
            assertEquals("team@example.com", resolved.get("replyTo"),
                "execute() must read sendEmailConfig.replyTo and report it in resolved_params");
        }

        @Test
        @DisplayName("Omitting fromEmail/replyTo leaves them out of resolved_params (credential address is used)")
        void executeOmitsAbsentOverrides() {
            Map<String, Object> cred = validSmtpCredentialData();
            cred.put("host", "127.0.0.1");

            SendEmailNode node = new SendEmailNode("core:send", new Core.SendEmailConfig(
                null, 587, null, null, true, null, null, "to@example.com",
                null, null, "Subject", "Body", false, null, null, null, null));
            wireCredentialClient(node, cred);

            NodeExecutionResult result = node.execute(context);

            Map<String, Object> resolved = (Map<String, Object>) result.output().get("resolved_params");
            assertFalse(resolved.containsKey("fromEmail"));
            assertFalse(resolved.containsKey("replyTo"));
        }
    }

    @Nested
    @DisplayName("Output schema alignment")
    class OutputSchemaTests {

        @Test
        @DisplayName("recipients is declared as a string, matching what the node emits and the agent docs say")
        void recipientsIsDeclaredString() {
            // The contract test only compares output KEYS, not their types, so nothing else stops
            // this drifting back to "array" while the node keeps emitting the comma-joined string
            // that V11's agent docs promise.
            String type = new SendEmailNodeSpec().definition().outputs().stream()
                .filter(o -> "recipients".equals(o.key()))
                .findFirst().orElseThrow()
                .type();

            assertEquals("string", type);
        }
    }

    @Nested
    @DisplayName("Failure output contract")
    class FailureOutputTests {

        @Test
        @DisplayName("regression: a failed send reports success=false and sent=false, not null")
        void failureOutputCarriesSuccessFalse() {
            // Pre-fix the failure path omitted both, so a downstream {{...output.success}} guard
            // read null instead of false and a false branch could be taken as "not failed".
            SendEmailNode node = new SendEmailNode("core:send", new Core.SendEmailConfig(
                null, 587, null, null, true, null, null, null,
                null, null, "Subject", "Body", false, null, null, null, null));
            wireCredentialClient(node, validSmtpCredentialData());

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            Map<String, Object> out = result.output();
            assertEquals(false, out.get("success"));
            assertEquals(false, out.get("sent"));
        }
    }
}
