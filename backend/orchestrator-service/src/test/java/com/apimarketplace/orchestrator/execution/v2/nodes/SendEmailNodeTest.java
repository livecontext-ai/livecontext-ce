package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.credential.client.CredentialClient;
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
            , null, null);
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
            , null, null);
            assertEquals(587, configZero.smtpPort());

            Core.SendEmailConfig configNeg = new Core.SendEmailConfig(
                "smtp.example.com", -1, null, null, false,
                null, null, "to@example.com", null, null, "Subject", "Body", false, null
            , null, null);
            assertEquals(587, configNeg.smtpPort());
        }

        @Test
        @DisplayName("Should preserve positive port value")
        void shouldPreservePositivePort() {
            Core.SendEmailConfig config = new Core.SendEmailConfig(
                "smtp.example.com", 465, null, null, false,
                null, null, "to@example.com", null, null, "Subject", "Body", false, null
            , null, null);
            assertEquals(465, config.smtpPort());
        }

        @Test
        @DisplayName("Should auto-enable TLS for port 587")
        void shouldAutoEnableTlsForPort587() {
            Core.SendEmailConfig config = new Core.SendEmailConfig(
                "smtp.example.com", 587, null, null, false,
                null, null, "to@example.com", null, null, "Subject", "Body", false, null
            , null, null);
            assertTrue(config.smtpUseTls());
        }

        @Test
        @DisplayName("Should auto-enable TLS for port 465")
        void shouldAutoEnableTlsForPort465() {
            Core.SendEmailConfig config = new Core.SendEmailConfig(
                "smtp.example.com", 465, null, null, false,
                null, null, "to@example.com", null, null, "Subject", "Body", false, null
            , null, null);
            assertTrue(config.smtpUseTls());
        }

        @Test
        @DisplayName("Should preserve explicit TLS setting for custom ports")
        void shouldPreserveExplicitTlsSetting() {
            Core.SendEmailConfig configWithTls = new Core.SendEmailConfig(
                "smtp.example.com", 2525, null, null, true,
                null, null, "to@example.com", null, null, "Subject", "Body", false, null
            , null, null);
            assertTrue(configWithTls.smtpUseTls());

            Core.SendEmailConfig configNoTls = new Core.SendEmailConfig(
                "smtp.example.com", 25, null, null, false,
                null, null, "to@example.com", null, null, "Subject", "Body", false, null
            , null, null);
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
            , null, null);
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
            , null, null);
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
            , null, null);
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
            , null, null);
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
            , null, null);
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
            , null, null);
            assertFalse(config.isHtml());
        }

        @Test
        @DisplayName("Should store isHtml=true for HTML")
        void shouldStoreIsHtmlTrueForHtml() {
            Core.SendEmailConfig config = new Core.SendEmailConfig(
                "smtp.example.com", 587, null, null, true,
                null, null, "to@example.com", null, null, "Subject",
                "<html><body><h1>Hello</h1></body></html>", true, null
            , null, null);
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
            , null, null);
            assertEquals("cc1@example.com,cc2@example.com", config.ccEmail());
            assertEquals("bcc@example.com", config.bccEmail());
        }

        @Test
        @DisplayName("Should handle null CC and BCC")
        void shouldHandleNullCcAndBcc() {
            Core.SendEmailConfig config = new Core.SendEmailConfig(
                "smtp.example.com", 587, null, null, true,
                null, null, "to@example.com", null, null, "Subject", "Body", false, null
            , null, null);
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
            , null, null);
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
            , null, null);
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
            , null, null);
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
            , null, null);
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
            , null, null);
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
            , null, null);
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
                "smtp.gmail.com", 587, "user@gmail.com", "app-password", true,
                "user@gmail.com", "Sender", "recipient@example.com",
                null, null, "Test Email", "Hello!", false, null
            , null, null);

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
}
