package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.CredentialSummaryDto;
import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.services.file.FileStorageService;
import jakarta.mail.Part;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EmailInboxNode}.
 *
 * <p>EmailInboxNode reads/acts on a mailbox via IMAP using user-provided credentials.
 * Actual IMAP traffic requires a live server, so these tests focus on the deterministic,
 * pre-connection behaviour:
 * <ul>
 *   <li>Config record defaults & validation (folder, limit, action)</li>
 *   <li>Constructor / builder / node metadata</li>
 *   <li>Fail-fast paths reached BEFORE opening a connection: missing credential,
 *       missing IMAP host, action without messageUid, move without targetFolder</li>
 *   <li>The error-handling path when the IMAP server is unreachable</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EmailInboxNode")
class EmailInboxNodeTest {

    @Mock
    private WorkflowPlan mockPlan;

    @Mock
    private CredentialClient mockCredentialClient;

    @Mock
    private ServiceRegistry mockServiceRegistry;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        context = ExecutionContext.create(
            "run-1", "workflow-run-1", "tenant-1", "item-1", 0,
            new HashMap<>(), mockPlan);
        when(mockServiceRegistry.getCredentialClient()).thenReturn(mockCredentialClient);
    }

    private Map<String, Object> validImapCredentialData() {
        Map<String, Object> data = new HashMap<>();
        data.put("host", "imap.example.com");
        data.put("port", 993);
        data.put("username", "user");
        data.put("password", "pass");
        data.put("use_ssl", "true");
        return data;
    }

    private CredentialSummaryDto credentialWith(Map<String, Object> data) {
        CredentialSummaryDto dto = new CredentialSummaryDto();
        dto.setId(1L);
        dto.setName("IMAP");
        dto.setIntegration("imap");
        dto.setStatus("active");
        dto.setDefault(true);
        dto.setCredentialData(data);
        return dto;
    }

    /** Wires the credential client + stubs the default IMAP credential (null = none configured). */
    private void wireCredentialClient(EmailInboxNode node, Map<String, Object> credData) {
        when(mockCredentialClient.getDefaultCredential(anyString(), eq("imap")))
            .thenReturn(credData != null ? Optional.of(credentialWith(credData)) : Optional.empty());
        node.acceptServices(mockServiceRegistry);
    }

    private Core.EmailInboxConfig config(String action, String messageUid, String targetFolder) {
        return new Core.EmailInboxConfig(null, "INBOX", false, 10, false, 0, action, messageUid, targetFolder, null, null, null, false, 0, false);
    }

    /** Same as {@link #config} but pins a specific credentialId (to exercise the by-id lookup path). */
    private Core.EmailInboxConfig configWithCredentialId(Long credentialId) {
        return new Core.EmailInboxConfig(credentialId, "INBOX", false, 10, false, 0, "none", null, null, null, null, null, false, 0, false);
    }

    // ===============================================================
    @Nested
    @DisplayName("EmailInboxConfig record")
    class ConfigRecordTests {

        @Test
        @DisplayName("Should default folder to INBOX when null or blank")
        void shouldDefaultFolder() {
            assertEquals("INBOX", new Core.EmailInboxConfig(null, null, false, 10, false, 0, "none", null, null, null, null, null, false, 0, false).folder());
            assertEquals("INBOX", new Core.EmailInboxConfig(null, "  ", false, 10, false, 0, "none", null, null, null, null, null, false, 0, false).folder());
        }

        @Test
        @DisplayName("Should clamp limit to [1,100] and default to 10")
        void shouldClampLimit() {
            assertEquals(10, new Core.EmailInboxConfig(null, "INBOX", false, 0, false, 0, "none", null, null, null, null, null, false, 0, false).limit());
            assertEquals(10, new Core.EmailInboxConfig(null, "INBOX", false, -5, false, 0, "none", null, null, null, null, null, false, 0, false).limit());
            assertEquals(100, new Core.EmailInboxConfig(null, "INBOX", false, 500, false, 0, "none", null, null, null, null, null, false, 0, false).limit());
            assertEquals(25, new Core.EmailInboxConfig(null, "INBOX", false, 25, false, 0, "none", null, null, null, null, null, false, 0, false).limit());
        }

        @Test
        @DisplayName("Should default action to none and lowercase it")
        void shouldDefaultAndNormalizeAction() {
            assertEquals("none", new Core.EmailInboxConfig(null, "INBOX", false, 10, false, 0, null, null, null, null, null, null, false, 0, false).action());
            assertEquals("none", new Core.EmailInboxConfig(null, "INBOX", false, 10, false, 0, "  ", null, null, null, null, null, false, 0, false).action());
            assertEquals("mark_read", new Core.EmailInboxConfig(null, "INBOX", false, 10, false, 0, "MARK_READ", "1", null, null, null, null, false, 0, false).action());
        }

        @Test
        @DisplayName("Should reject an invalid action")
        void shouldRejectInvalidAction() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new Core.EmailInboxConfig(null, "INBOX", false, 10, false, 0, "archive", "1", null, null, null, null, false, 0, false));
            assertTrue(ex.getMessage().contains("Invalid email_inbox action"));
        }

        @Test
        @DisplayName("Should floor negative sinceDays to 0")
        void shouldFloorSinceDays() {
            assertEquals(0, new Core.EmailInboxConfig(null, "INBOX", false, 10, false, -3, "none", null, null, null, null, null, false, 0, false).sinceDays());
            assertEquals(7, new Core.EmailInboxConfig(null, "INBOX", false, 10, false, 7, "none", null, null, null, null, null, false, 0, false).sinceDays());
        }

        @Test
        @DisplayName("Should floor negative beforeDays to 0")
        void shouldFloorBeforeDays() {
            assertEquals(0, new Core.EmailInboxConfig(null, "INBOX", false, 10, false, 0, "none", null, null, null, null, null, false, -4, false).beforeDays());
            assertEquals(30, new Core.EmailInboxConfig(null, "INBOX", false, 10, false, 0, "none", null, null, null, null, null, false, 30, false).beforeDays());
        }

        @Test
        @DisplayName("Should accept list_folders as a valid action")
        void shouldAcceptListFolders() {
            assertEquals("list_folders", new Core.EmailInboxConfig(null, "INBOX", false, 10, false, 0, "list_folders", null, null, null, null, null, false, 0, false).action());
        }

        @Test
        @DisplayName("isMessageAction is true only for single-message actions")
        void isMessageActionClassification() {
            assertFalse(config("none", null, null).isMessageAction());
            assertFalse(config("list_folders", null, null).isMessageAction());
            assertTrue(config("flag", "1", null).isMessageAction());
            assertTrue(config("move", "1", "Archive").isMessageAction());
            assertTrue(config("delete", "1", null).isMessageAction());
        }

        @Test
        @DisplayName("Should carry search filters + downloadAttachments through the record")
        void shouldCarrySearchAndAttachmentFields() {
            Core.EmailInboxConfig c = new Core.EmailInboxConfig(null, "INBOX", false, 10, false, 0, "none", null, null,
                "boss@acme.com", "invoice", "overdue", true, 7, true);
            assertEquals("boss@acme.com", c.fromContains());
            assertEquals("invoice", c.subjectContains());
            assertEquals("overdue", c.bodyContains());
            assertTrue(c.flaggedOnly());
            assertEquals(7, c.beforeDays());
            assertTrue(c.downloadAttachments());
        }
    }

    // ===============================================================
    @Nested
    @DisplayName("Constructor / builder")
    class ConstructorTests {

        @Test
        @DisplayName("Should expose nodeId, EMAIL_INBOX type and config")
        void shouldExposeNodeMetadata() {
            Core.EmailInboxConfig cfg = config("none", null, null);
            EmailInboxNode node = new EmailInboxNode("core:read_inbox", cfg);
            assertEquals("core:read_inbox", node.getNodeId());
            assertEquals(NodeType.EMAIL_INBOX, node.getType());
            assertSame(cfg, node.getEmailInboxConfig());
        }

        @Test
        @DisplayName("Builder should produce an equivalent node")
        void builderShouldWork() {
            Core.EmailInboxConfig cfg = config("none", null, null);
            EmailInboxNode node = EmailInboxNode.builder().nodeId("core:x").emailInboxConfig(cfg).build();
            assertEquals("core:x", node.getNodeId());
            assertSame(cfg, node.getEmailInboxConfig());
        }

        @Test
        @DisplayName("Should handle null config")
        void shouldHandleNullConfig() {
            EmailInboxNode node = new EmailInboxNode("core:x", null);
            assertNull(node.getEmailInboxConfig());
            assertEquals(NodeType.EMAIL_INBOX, node.getType());
        }
    }

    // ===============================================================
    @Nested
    @DisplayName("execute() - fail-fast paths (no IMAP connection needed)")
    class FailFastTests {

        @Test
        @DisplayName("Should fail when no IMAP credential is configured")
        void shouldFailWhenNoCredential() {
            EmailInboxNode node = new EmailInboxNode("core:read", config("none", null, null));
            wireCredentialClient(node, null);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().orElse("").contains("No IMAP credential"));
        }

        @Test
        @DisplayName("Should fall back to the default credential when the selected credentialId is not found")
        void shouldFallBackToDefaultWhenSelectedCredentialMissing() {
            // credentialId 5 is configured but absent; the node must fall back to the default IMAP credential.
            Map<String, Object> fallbackCred = validImapCredentialData();
            fallbackCred.put("host", "127.0.0.1");
            fallbackCred.put("port", 1);        // closed port -> connect fails, but credential resolution succeeded
            fallbackCred.put("use_ssl", "false");

            when(mockCredentialClient.getCredentialById(anyString(), eq(5L)))
                .thenReturn(Optional.empty());
            when(mockCredentialClient.getDefaultCredential(anyString(), eq("imap")))
                .thenReturn(Optional.of(credentialWith(fallbackCred)));

            EmailInboxNode node = new EmailInboxNode("core:read", configWithCredentialId(5L));
            node.acceptServices(mockServiceRegistry);

            NodeExecutionResult result = node.execute(context);

            // The by-id lookup is tried first, then the default is consulted as the fallback source.
            verify(mockCredentialClient).getCredentialById(anyString(), eq(5L));
            verify(mockCredentialClient).getDefaultCredential(anyString(), eq("imap"));
            // Fallback credential WAS used: we got past credential resolution and failed at connect,
            // NOT with the "No IMAP credential configured" error.
            assertTrue(result.isFailure());
            assertFalse(result.errorMessage().orElse("").contains("No IMAP credential"));
        }

        @Test
        @DisplayName("Should fail when IMAP host is blank in the credential")
        void shouldFailWhenHostBlank() {
            EmailInboxNode node = new EmailInboxNode("core:read", config("none", null, null));
            Map<String, Object> cred = validImapCredentialData();
            cred.put("host", "   ");
            wireCredentialClient(node, cred);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().orElse("").contains("IMAP host"));
        }

        @Test
        @DisplayName("Should fail an action other than none without messageUid")
        void shouldFailActionWithoutUid() {
            EmailInboxNode node = new EmailInboxNode("core:flag", config("flag", null, null));
            wireCredentialClient(node, validImapCredentialData());

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().orElse("").contains("messageUid is required"));
        }

        @Test
        @DisplayName("Should fail a move action without targetFolder")
        void shouldFailMoveWithoutTargetFolder() {
            EmailInboxNode node = new EmailInboxNode("core:move", config("move", "42", null));
            wireCredentialClient(node, validImapCredentialData());

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().orElse("").contains("targetFolder is required"));
        }

        @Test
        @DisplayName("list_folders must NOT require messageUid (mailbox-level action)")
        void listFoldersDoesNotRequireMessageUid() {
            EmailInboxNode node = new EmailInboxNode("core:lf", config("list_folders", null, null));
            wireCredentialClient(node, validImapCredentialData());

            NodeExecutionResult result = node.execute(context);

            // It will fail at connect (unreachable host) - but NOT with a messageUid error.
            assertTrue(result.isFailure());
            assertFalse(result.errorMessage().orElse("").contains("messageUid is required"));
        }

        @Test
        @DisplayName("Should surface EMAIL_INBOX metadata on the failure output")
        void shouldSurfaceMetadataOnFailure() {
            EmailInboxNode node = new EmailInboxNode("core:read", config("none", null, null));
            wireCredentialClient(node, null);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            Map<String, Object> out = result.output();
            assertNotNull(out);
            assertEquals("EMAIL_INBOX", out.get("node_type"));
            assertEquals(false, out.get("success"));
            assertNotNull(out.get("resolved_params"));
        }
    }

    // ===============================================================
    @Nested
    @DisplayName("execute() - read mode error handling")
    class ReadErrorTests {

        @Test
        @DisplayName("Should fail gracefully when the IMAP server is unreachable")
        void shouldFailOnUnreachableServer() {
            // host:port that will not accept an IMAP connection -> connection error path
            Map<String, Object> cred = validImapCredentialData();
            cred.put("host", "127.0.0.1");
            cred.put("port", 1);            // unused/closed port
            cred.put("use_ssl", "false");
            EmailInboxNode node = new EmailInboxNode("core:read", config("none", null, null));
            wireCredentialClient(node, cred);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertEquals("EMAIL_INBOX", result.output().get("node_type"));
        }
    }

    // ===============================================================
    @Nested
    @DisplayName("Attachment 25MB safety cap")
    class AttachmentSizeCapTests {

        private static final long MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024;

        /** A zero-filled stream of exactly {@code total} bytes that does NOT allocate that many bytes of heap. */
        private InputStream fixedSizeStream(long total) {
            return new InputStream() {
                long remaining = total;
                @Override
                public int read() {
                    if (remaining <= 0) return -1;
                    remaining--;
                    return 0;
                }
                @Override
                public int read(byte[] b, int off, int len) {
                    if (remaining <= 0) return -1;
                    int n = (int) Math.min(len, remaining);
                    remaining -= n;
                    return n; // bytes are already zero in the buffer
                }
            };
        }

        private byte[] invokeReadCapped(InputStream is, long max) throws Exception {
            Method m = EmailInboxNode.class.getDeclaredMethod("readCapped", InputStream.class, long.class);
            m.setAccessible(true);
            return (byte[]) m.invoke(null, is, max);
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> invokeToAttachment(EmailInboxNode node, Part part) throws Exception {
            Method m = EmailInboxNode.class.getDeclaredMethod(
                "toAttachment", Part.class, String.class, boolean.class, ExecutionContext.class);
            m.setAccessible(true);
            return (Map<String, Object>) m.invoke(node, part, "big.bin", true, context);
        }

        @Test
        @DisplayName("readCapped returns null when the stream exceeds the 25MB cap")
        void readCappedReturnsNullWhenOverCap() throws Exception {
            // One byte over the cap must trip the guard and abort the read.
            byte[] result = invokeReadCapped(fixedSizeStream(MAX_ATTACHMENT_BYTES + 1), MAX_ATTACHMENT_BYTES);
            assertNull(result, "readCapped must return null once total bytes exceed the cap");
        }

        @Test
        @DisplayName("readCapped returns the exact bytes when the stream is within the cap")
        void readCappedReturnsBytesWhenWithinCap() throws Exception {
            byte[] payload = "hello-attachment".getBytes();
            byte[] result = invokeReadCapped(new ByteArrayInputStream(payload), MAX_ATTACHMENT_BYTES);
            assertNotNull(result, "readCapped must return the buffered bytes when under the cap");
            assertArrayEquals(payload, result);
        }

        @Test
        @DisplayName("readCapped returns bytes exactly at the cap boundary (cap is inclusive)")
        void readCappedReturnsBytesAtExactCapBoundary() throws Exception {
            // total == max is allowed; only total > max returns null.
            byte[] result = invokeReadCapped(fixedSizeStream(MAX_ATTACHMENT_BYTES), MAX_ATTACHMENT_BYTES);
            assertNotNull(result, "a stream of exactly cap bytes must NOT be skipped");
            assertEquals(MAX_ATTACHMENT_BYTES, result.length);
        }

        @Test
        @DisplayName("toAttachment sets downloadSkipped and does NOT upload when the attachment exceeds the 25MB cap")
        void toAttachmentSkipsOversizeAttachment() throws Exception {
            FileStorageService storage = mock(FileStorageService.class);
            when(mockServiceRegistry.getFileStorageService()).thenReturn(storage);

            Part part = mock(Part.class);
            when(part.getInputStream()).thenReturn(fixedSizeStream(MAX_ATTACHMENT_BYTES + 1));
            when(part.getContentType()).thenReturn("application/pdf");
            when(part.getSize()).thenReturn(-1);

            EmailInboxNode node = new EmailInboxNode("core:read", config("none", null, null));
            node.acceptServices(mockServiceRegistry); // injects fileStorageService

            Map<String, Object> att = invokeToAttachment(node, part);

            // Oversize attachment: skipped with a cap message, no file ref, and upload never attempted.
            assertEquals("attachment exceeds 25MB cap", att.get("downloadSkipped"));
            assertNull(att.get("file"), "no file ref must be produced for a skipped attachment");
            verify(storage, never()).upload(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.any(byte[].class));
        }
    }
}
