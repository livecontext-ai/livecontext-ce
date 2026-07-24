package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.CredentialSummaryDto;
import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.services.file.FileStorageService;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import org.eclipse.angus.mail.imap.AppendUID;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPStore;

import jakarta.mail.Address;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Part;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.search.BodyTerm;
import jakarta.mail.search.FromStringTerm;
import jakarta.mail.search.SearchTerm;
import jakarta.mail.search.SubjectTerm;
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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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
        return new Core.EmailInboxConfig(null, "INBOX", false, 10, false, 0, action, messageUid, targetFolder, null, null, null, false, 0, false, false);
    }

    /** Same as {@link #config} but pins a specific credentialId (to exercise the by-id lookup path). */
    private Core.EmailInboxConfig configWithCredentialId(Long credentialId) {
        return new Core.EmailInboxConfig(credentialId, "INBOX", false, 10, false, 0, "none", null, null, null, null, null, false, 0, false, false);
    }

    // ===============================================================
    @Nested
    @DisplayName("EmailInboxConfig record")
    class ConfigRecordTests {

        @Test
        @DisplayName("Should default folder to INBOX when null or blank")
        void shouldDefaultFolder() {
            assertEquals("INBOX", new Core.EmailInboxConfig(null, null, false, 10, false, 0, "none", null, null, null, null, null, false, 0, false, false).folder());
            assertEquals("INBOX", new Core.EmailInboxConfig(null, "  ", false, 10, false, 0, "none", null, null, null, null, null, false, 0, false, false).folder());
        }

        @Test
        @DisplayName("Should clamp limit to [1,100] and default to 10")
        void shouldClampLimit() {
            assertEquals(10, new Core.EmailInboxConfig(null, "INBOX", false, 0, false, 0, "none", null, null, null, null, null, false, 0, false, false).limit());
            assertEquals(10, new Core.EmailInboxConfig(null, "INBOX", false, -5, false, 0, "none", null, null, null, null, null, false, 0, false, false).limit());
            assertEquals(100, new Core.EmailInboxConfig(null, "INBOX", false, 500, false, 0, "none", null, null, null, null, null, false, 0, false, false).limit());
            assertEquals(25, new Core.EmailInboxConfig(null, "INBOX", false, 25, false, 0, "none", null, null, null, null, null, false, 0, false, false).limit());
        }

        @Test
        @DisplayName("Should default action to none and lowercase it")
        void shouldDefaultAndNormalizeAction() {
            assertEquals("none", new Core.EmailInboxConfig(null, "INBOX", false, 10, false, 0, null, null, null, null, null, null, false, 0, false, false).action());
            assertEquals("none", new Core.EmailInboxConfig(null, "INBOX", false, 10, false, 0, "  ", null, null, null, null, null, false, 0, false, false).action());
            assertEquals("mark_read", new Core.EmailInboxConfig(null, "INBOX", false, 10, false, 0, "MARK_READ", "1", null, null, null, null, false, 0, false, false).action());
        }

        @Test
        @DisplayName("Should reject an invalid action")
        void shouldRejectInvalidAction() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new Core.EmailInboxConfig(null, "INBOX", false, 10, false, 0, "archive", "1", null, null, null, null, false, 0, false, false));
            assertTrue(ex.getMessage().contains("Invalid email_inbox action"));
        }

        @Test
        @DisplayName("Should floor negative sinceDays to 0")
        void shouldFloorSinceDays() {
            assertEquals(0, new Core.EmailInboxConfig(null, "INBOX", false, 10, false, -3, "none", null, null, null, null, null, false, 0, false, false).sinceDays());
            assertEquals(7, new Core.EmailInboxConfig(null, "INBOX", false, 10, false, 7, "none", null, null, null, null, null, false, 0, false, false).sinceDays());
        }

        @Test
        @DisplayName("Should floor negative beforeDays to 0")
        void shouldFloorBeforeDays() {
            assertEquals(0, new Core.EmailInboxConfig(null, "INBOX", false, 10, false, 0, "none", null, null, null, null, null, false, -4, false, false).beforeDays());
            assertEquals(30, new Core.EmailInboxConfig(null, "INBOX", false, 10, false, 0, "none", null, null, null, null, null, false, 30, false, false).beforeDays());
        }

        @Test
        @DisplayName("Should accept list_folders as a valid action")
        void shouldAcceptListFolders() {
            assertEquals("list_folders", new Core.EmailInboxConfig(null, "INBOX", false, 10, false, 0, "list_folders", null, null, null, null, null, false, 0, false, false).action());
        }

        @Test
        @DisplayName("Should accept create_folder as a valid action")
        void shouldAcceptCreateFolder() {
            assertEquals("create_folder", config("create_folder", null, "Clients").action());
        }

        @Test
        @DisplayName("createTargetIfMissing defaults to false and is carried through the record")
        void shouldCarryCreateTargetIfMissing() {
            // Default must stay false: auto-creating a folder is a side effect on the user's
            // mailbox and must never happen unless the workflow asked for it.
            assertFalse(config("move", "1", "Clients").createTargetIfMissing());
            Core.EmailInboxConfig c = new Core.EmailInboxConfig(null, "INBOX", false, 10, false, 0,
                "move", "1", "Clients", null, null, null, false, 0, false, true);
            assertTrue(c.createTargetIfMissing());
        }

        @Test
        @DisplayName("isMessageAction is true only for single-message actions")
        void isMessageActionClassification() {
            assertFalse(config("none", null, null).isMessageAction());
            assertFalse(config("list_folders", null, null).isMessageAction());
            assertFalse(config("create_folder", null, "Clients").isMessageAction());
            assertTrue(config("flag", "1", null).isMessageAction());
            assertTrue(config("move", "1", "Archive").isMessageAction());
            assertTrue(config("delete", "1", null).isMessageAction());
        }

        @Test
        @DisplayName("Should carry search filters + downloadAttachments through the record")
        void shouldCarrySearchAndAttachmentFields() {
            Core.EmailInboxConfig c = new Core.EmailInboxConfig(null, "INBOX", false, 10, false, 0, "none", null, null,
                "boss@acme.com", "invoice", "overdue", true, 7, true, false);
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
        @DisplayName("Should fail a create_folder action without targetFolder")
        void shouldFailCreateFolderWithoutTargetFolder() {
            EmailInboxNode node = new EmailInboxNode("core:cf", config("create_folder", null, null));
            wireCredentialClient(node, validImapCredentialData());

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().orElse("").contains("targetFolder is required for the create_folder action"));
        }

        @Test
        @DisplayName("create_folder must NOT require messageUid (mailbox-level action)")
        void createFolderDoesNotRequireMessageUid() {
            EmailInboxNode node = new EmailInboxNode("core:cf", config("create_folder", null, "Clients"));
            wireCredentialClient(node, validImapCredentialData());

            NodeExecutionResult result = node.execute(context);

            // It will fail at connect (unreachable host) - but NOT with a messageUid error.
            assertTrue(result.isFailure());
            assertFalse(result.errorMessage().orElse("").contains("messageUid is required"));
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
    @DisplayName("create_folder / ensureFolderExists")
    class CreateFolderTests {

        private final EmailInboxNode node = new EmailInboxNode("core:cf", config("create_folder", null, "INBOX.Clients"));

        /** A Store whose getFolder(name) returns the given folder. */
        private Store storeReturning(Folder folder) throws Exception {
            Store store = mock(Store.class);
            when(store.getFolder(anyString())).thenReturn(folder);
            Folder root = mock(Folder.class);
            when(root.list(anyString())).thenReturn(new Folder[]{});
            when(store.getDefaultFolder()).thenReturn(root);
            return store;
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> invokeCreateFolder(Store store, String name) throws Exception {
            Method m = EmailInboxNode.class.getDeclaredMethod(
                "createFolder", Store.class, String.class, Map.class);
            m.setAccessible(true);
            try {
                return (Map<String, Object>) m.invoke(node, store, name, new LinkedHashMap<String, Object>());
            } catch (InvocationTargetException e) {
                throw (Exception) e.getCause();
            }
        }

        @Test
        @DisplayName("Should create the folder and report created=true when it does not exist")
        void shouldCreateWhenMissing() throws Exception {
            Folder target = mock(Folder.class);
            when(target.exists()).thenReturn(false);
            when(target.create(Folder.HOLDS_MESSAGES | Folder.HOLDS_FOLDERS)).thenReturn(true);

            Map<String, Object> result = invokeCreateFolder(storeReturning(target), "INBOX.Clients");

            verify(target).create(Folder.HOLDS_MESSAGES | Folder.HOLDS_FOLDERS);
            assertEquals(true, result.get("created"));
            assertEquals("INBOX.Clients", result.get("folder"));
            // The action output is documented for create_folder too, so it must be populated.
            assertEquals("create_folder", result.get("action"));
        }

        @Test
        @DisplayName("Should be idempotent: an existing folder reports created=false and is NOT re-created")
        void shouldBeIdempotentWhenFolderExists() throws Exception {
            // Idempotency is what lets a workflow run create_folder on every tick without failing.
            Folder target = mock(Folder.class);
            when(target.exists()).thenReturn(true);

            Map<String, Object> result = invokeCreateFolder(storeReturning(target), "INBOX.Clients");

            verify(target, never()).create(org.mockito.ArgumentMatchers.anyInt());
            assertEquals(false, result.get("created"));
        }

        @Test
        @DisplayName("Should fail when the IMAP server refuses to create the folder")
        void shouldFailWhenServerRefusesCreate() throws Exception {
            Folder target = mock(Folder.class);
            when(target.exists()).thenReturn(false);
            when(target.create(Folder.HOLDS_MESSAGES | Folder.HOLDS_FOLDERS)).thenReturn(false);

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> invokeCreateFolder(storeReturning(target), "INBOX.Clients"));
            assertTrue(ex.getMessage().contains("refused to create folder"));
        }

        @Test
        @DisplayName("Should fail cleanly when the store returns no folder handle for the name")
        void shouldFailWhenStoreReturnsNoFolderHandle() throws Exception {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> invokeCreateFolder(storeReturning(null), "??"));
            assertTrue(ex.getMessage().contains("Invalid folder name"));
        }

        @Test
        @DisplayName("Should report the mailbox folder list back, so the agent can reuse a real server path")
        void shouldReturnFolderList() throws Exception {
            Folder target = mock(Folder.class);
            when(target.exists()).thenReturn(true);
            Store store = mock(Store.class);
            when(store.getFolder(anyString())).thenReturn(target);
            Folder root = mock(Folder.class);
            Folder existing = mock(Folder.class);
            when(existing.getType()).thenReturn(Folder.HOLDS_MESSAGES);
            when(existing.getFullName()).thenReturn("INBOX.Drafts");
            when(root.list(anyString())).thenReturn(new Folder[]{existing});
            when(store.getDefaultFolder()).thenReturn(root);

            Map<String, Object> result = invokeCreateFolder(store, "INBOX.Clients");

            assertEquals(List.of("INBOX.Drafts"), result.get("folders"));
        }

        @Test
        @DisplayName("Should record the created folder in resolved_params, matching output.folder")
        void shouldRecordResolvedParams() throws Exception {
            Folder target = mock(Folder.class);
            when(target.exists()).thenReturn(true);
            Map<String, Object> resolved = new LinkedHashMap<>();
            Method m = EmailInboxNode.class.getDeclaredMethod(
                "createFolder", Store.class, String.class, Map.class);
            m.setAccessible(true);
            m.invoke(node, storeReturning(target), "INBOX.Clients", resolved);

            // create_folder acts on targetFolder, not on the read folder: both keys must name it,
            // or an agent debugging via resolved_params is told it acted on INBOX.
            assertEquals("INBOX.Clients", resolved.get("targetFolder"));
            assertEquals("INBOX.Clients", resolved.get("folder"));
        }

        @Test
        @DisplayName("Should pass the folder name to the server verbatim, never rewriting the namespace")
        void shouldNotRewriteFolderName() throws Exception {
            // A server that namespaces under INBOX expects "INBOX.Clients"; guessing here would
            // silently create a folder the caller never asked for.
            Folder target = mock(Folder.class);
            when(target.exists()).thenReturn(false);
            when(target.create(Folder.HOLDS_MESSAGES | Folder.HOLDS_FOLDERS)).thenReturn(true);
            Store store = storeReturning(target);

            invokeCreateFolder(store, "INBOX.A Repondre.Clients");

            verify(store).getFolder("INBOX.A Repondre.Clients");
        }
    }

    @Nested
    @DisplayName("SpEL resolution of user-supplied fields")
    class TemplateResolutionTests {

        /** Resolves any {{x}} to the literal RESOLVED, so we can see which fields went through. */
        private void wireResolvingAdapter(EmailInboxNode node) {
            V2TemplateAdapter adapter = mock(V2TemplateAdapter.class);
            when(adapter.resolveTemplates(any(), any())).thenAnswer(inv -> {
                Map<String, Object> in = inv.getArgument(0);
                Map<String, Object> out = new LinkedHashMap<>();
                in.forEach((k, v) -> out.put(k, String.valueOf(v).replaceAll("\\{\\{[^}]+\\}\\}", "RESOLVED")));
                return out;
            });
            when(mockServiceRegistry.getTemplateAdapter()).thenReturn(adapter);
        }

        private SearchTerm invokeBuildSearchTerm(EmailInboxNode node) throws Exception {
            Method m = EmailInboxNode.class.getDeclaredMethod("buildSearchTerm", ExecutionContext.class);
            m.setAccessible(true);
            return (SearchTerm) m.invoke(node, context);
        }

        /** Finds the first term of {@code type} in the (possibly AndTerm) built search term. */
        @SuppressWarnings("unchecked")
        private <T extends SearchTerm> T findTerm(SearchTerm root, Class<T> type) {
            if (type.isInstance(root)) return (T) root;
            if (root instanceof jakarta.mail.search.AndTerm and) {
                for (SearchTerm t : and.getTerms()) {
                    T found = findTerm(t, type);
                    if (found != null) return found;
                }
            }
            return null;
        }

        @Test
        @DisplayName("regression: a fromContains template is SpEL-resolved, not sent to IMAP literally")
        void fromContainsIsResolved() throws Exception {
            // Pre-fix only messageUid went through SpEL: a {{template}} in a search filter reached
            // the IMAP SEARCH command as the literal string "{{...}}" and matched nothing, silently.
            EmailInboxNode node = new EmailInboxNode("core:read", new Core.EmailInboxConfig(
                null, "INBOX", false, 10, false, 0, "none", null, null,
                "{{trigger:t.output.sender}}", null, null, false, 0, false, false));
            wireCredentialClient(node, validImapCredentialData());
            wireResolvingAdapter(node);
            node.acceptServices(mockServiceRegistry);

            FromStringTerm term = findTerm(invokeBuildSearchTerm(node), FromStringTerm.class);

            assertEquals("RESOLVED", term.getPattern());
        }

        @Test
        @DisplayName("regression: a subjectContains template is SpEL-resolved too")
        void subjectContainsIsResolved() throws Exception {
            EmailInboxNode node = new EmailInboxNode("core:read", new Core.EmailInboxConfig(
                null, "INBOX", false, 10, false, 0, "none", null, null,
                null, "{{trigger:t.output.subj}}", null, false, 0, false, false));
            wireCredentialClient(node, validImapCredentialData());
            wireResolvingAdapter(node);
            node.acceptServices(mockServiceRegistry);

            SubjectTerm term = findTerm(invokeBuildSearchTerm(node), SubjectTerm.class);

            assertEquals("RESOLVED", term.getPattern());
        }

        @Test
        @DisplayName("A filter with no template reaches IMAP unchanged")
        void plainFiltersSurviveResolution() throws Exception {
            EmailInboxNode node = new EmailInboxNode("core:read", new Core.EmailInboxConfig(
                null, "INBOX", false, 10, false, 0, "none", null, null,
                "boss@acme.com", null, null, false, 0, false, false));
            wireCredentialClient(node, validImapCredentialData());
            wireResolvingAdapter(node);
            node.acceptServices(mockServiceRegistry);

            FromStringTerm term = findTerm(invokeBuildSearchTerm(node), FromStringTerm.class);

            assertEquals("boss@acme.com", term.getPattern());
        }

        @Test
        @DisplayName("regression: bodyContains is resolved too (the third identical branch)")
        void bodyContainsIsResolved() throws Exception {
            EmailInboxNode node = new EmailInboxNode("core:read", new Core.EmailInboxConfig(
                null, "INBOX", false, 10, false, 0, "none", null, null,
                null, null, "{{trigger:t.output.q}}", false, 0, false, false));
            wireCredentialClient(node, validImapCredentialData());
            wireResolvingAdapter(node);
            node.acceptServices(mockServiceRegistry);

            BodyTerm term = findTerm(invokeBuildSearchTerm(node), BodyTerm.class);

            assertEquals("RESOLVED", term.getPattern());
        }

        @Test
        @DisplayName("perf/correctness: the SEARCH always excludes \\Deleted server-side (UNDELETED term), even with no user filters")
        void searchAlwaysExcludesDeletedServerSide() throws Exception {
            // The server-side UNDELETED term is what keeps a big-folder read off the per-message
            // flag-fetch path AND is the primary guarantee that \Deleted mail is never returned.
            // A FlagTerm(DELETED,false) is "excluded"; assert it is present on the plain read path.
            EmailInboxNode node = new EmailInboxNode("core:read", config("none", null, null));
            wireCredentialClient(node, validImapCredentialData());
            wireResolvingAdapter(node);
            node.acceptServices(mockServiceRegistry);

            SearchTerm root = invokeBuildSearchTerm(node);
            assertNotNull(root, "buildSearchTerm must never be null now: it always carries UNDELETED");

            jakarta.mail.search.FlagTerm deletedFilter = findTerm(root, jakarta.mail.search.FlagTerm.class);
            assertNotNull(deletedFilter, "the built term must contain a FlagTerm; got: " + root);
            assertTrue(deletedFilter.getFlags().contains(Flags.Flag.DELETED),
                "the FlagTerm must target the \\Deleted flag");
            assertFalse(deletedFilter.getTestSet(),
                "the DELETED FlagTerm must test for ABSENCE (UNDELETED), so the server excludes deleted mail");
        }

        @Test
        @DisplayName("the UNDELETED term ANDs together with a user filter (both reach the server)")
        void undeletedComposesWithUserFilters() throws Exception {
            // Adding one more AND term is correct: it composes with existing filters rather than
            // replacing them. A fromContains read must carry BOTH the UNDELETED and the FROM term.
            EmailInboxNode node = new EmailInboxNode("core:read", new Core.EmailInboxConfig(
                null, "INBOX", false, 10, false, 0, "none", null, null,
                "boss@acme.com", null, null, false, 0, false, false));
            wireCredentialClient(node, validImapCredentialData());
            wireResolvingAdapter(node);
            node.acceptServices(mockServiceRegistry);

            SearchTerm root = invokeBuildSearchTerm(node);

            jakarta.mail.search.FlagTerm deletedFilter = findTerm(root, jakarta.mail.search.FlagTerm.class);
            FromStringTerm fromFilter = findTerm(root, FromStringTerm.class);
            assertNotNull(deletedFilter, "UNDELETED must still be present alongside the user filter");
            assertFalse(deletedFilter.getTestSet(), "UNDELETED tests for absence of \\Deleted");
            assertEquals("boss@acme.com", fromFilter.getPattern(), "the user filter must survive");
        }

        @Test
        @DisplayName("regression: a targetFolder template is resolved BEFORE reaching execute()'s validation")
        void targetFolderTemplateIsResolvedInExecute() {
            // Covers the resolution in execute() itself, not just the fields buildSearchTerm reads.
            // A template that resolves to blank must trip the "targetFolder is required" guard;
            // pre-fix the raw "{{...}}" string was non-blank, so the guard passed and the literal
            // template was handed to IMAP as a folder name.
            EmailInboxNode node = new EmailInboxNode("core:move", new Core.EmailInboxConfig(
                null, "INBOX", false, 10, false, 0, "move", "42", "{{trigger:t.output.missing}}",
                null, null, null, false, 0, false, false));
            wireCredentialClient(node, validImapCredentialData());
            // Realistic resolver: an unresolvable template yields blank, a literal passes through.
            // (Blanking literals too would make messageUid trip its own guard first.)
            V2TemplateAdapter adapter = mock(V2TemplateAdapter.class);
            when(adapter.resolveTemplates(any(), any())).thenAnswer(inv -> {
                Map<String, Object> out = new LinkedHashMap<>();
                ((Map<String, Object>) inv.getArgument(0)).forEach((k, v) ->
                    out.put(k, String.valueOf(v).contains("{{") ? "" : v));
                return out;
            });
            when(mockServiceRegistry.getTemplateAdapter()).thenReturn(adapter);
            node.acceptServices(mockServiceRegistry);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().orElse("").contains("targetFolder is required"),
                "a template resolving to blank must be treated as absent, got: " + result.errorMessage().orElse(""));
        }

        @Test
        @DisplayName("regression: the read folder is SpEL-resolved before it reaches IMAP")
        void readFolderIsResolved() {
            // The field most able to address the WRONG mailbox. A template resolving to blank must
            // fall back to INBOX, not be sent literally.
            EmailInboxNode node = new EmailInboxNode("core:read", new Core.EmailInboxConfig(
                null, "{{trigger:t.output.box}}", false, 10, false, 0, "none", null, null,
                null, null, null, false, 0, false, false));
            wireCredentialClient(node, validImapCredentialData());
            V2TemplateAdapter adapter = mock(V2TemplateAdapter.class);
            when(adapter.resolveTemplates(any(), any())).thenAnswer(inv -> {
                Map<String, Object> out = new LinkedHashMap<>();
                ((Map<String, Object>) inv.getArgument(0)).forEach((k, v) -> out.put(k, "INBOX.Archive"));
                return out;
            });
            when(mockServiceRegistry.getTemplateAdapter()).thenReturn(adapter);
            node.acceptServices(mockServiceRegistry);

            NodeExecutionResult result = node.execute(context);

            // Fails at connect (unreachable host), but resolved_params must already show the
            // RESOLVED folder, never the raw template.
            Map<String, Object> resolved = (Map<String, Object>) result.output().get("resolved_params");
            assertEquals("INBOX.Archive", resolved.get("folder"));
        }

        @Test
        @DisplayName("A messageUid template resolving to blank is treated as absent, before any connection")
        void blankResolvedMessageUidFailsFast() {
            // The guard reads the RESOLVED uid like targetFolder does. Checking the raw value let
            // a template that resolves to nothing pass, open an IMAP connection, and only fail
            // deeper in with a confusing "must be a numeric IMAP UID, got: {{...}}".
            EmailInboxNode node = new EmailInboxNode("core:flag", new Core.EmailInboxConfig(
                null, "INBOX", false, 10, false, 0, "flag", "{{core:snapshot.output.uid}}", null,
                null, null, null, false, 0, false, false));
            wireCredentialClient(node, validImapCredentialData());
            V2TemplateAdapter adapter = mock(V2TemplateAdapter.class);
            when(adapter.resolveTemplates(any(), any())).thenAnswer(inv -> {
                Map<String, Object> out = new LinkedHashMap<>();
                ((Map<String, Object>) inv.getArgument(0)).forEach((k, v) -> out.put(k, ""));
                return out;
            });
            when(mockServiceRegistry.getTemplateAdapter()).thenReturn(adapter);
            node.acceptServices(mockServiceRegistry);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().orElse("").contains("messageUid is required"),
                "got: " + result.errorMessage().orElse(""));
        }

        @Test
        @DisplayName("targetFolder is trimmed once, so create_folder and move cannot diverge")
        void targetFolderIsTrimmedOnce() {
            // Both actions ship together, so nothing regressed: this pins the invariant. An
            // untrimmed move against a trimmed create_folder would file mail into a second,
            // whitespace-named twin of the same folder.
            EmailInboxNode node = new EmailInboxNode("core:move", new Core.EmailInboxConfig(
                null, "INBOX", false, 10, false, 0, "move", "42", "  INBOX.Clients  ",
                null, null, null, false, 0, false, false));
            wireCredentialClient(node, validImapCredentialData());

            NodeExecutionResult result = node.execute(context);

            Map<String, Object> resolved = (Map<String, Object>) result.output().get("resolved_params");
            assertEquals("INBOX.Clients", resolved.get("targetFolder"));
        }

        @Test
        @DisplayName("No user filters still yields the UNDELETED term (deleted mail is always excluded server-side)")
        void noFiltersYieldsUndeletedTerm() throws Exception {
            // Pre-perf-fix this returned null (read everything, including \Deleted). Now the
            // UNDELETED term is unconditional, so a no-filter read is a single FlagTerm, never null.
            EmailInboxNode node = new EmailInboxNode("core:read", config("none", null, null));
            wireCredentialClient(node, validImapCredentialData());
            wireResolvingAdapter(node);
            node.acceptServices(mockServiceRegistry);

            SearchTerm root = invokeBuildSearchTerm(node);
            assertTrue(root instanceof jakarta.mail.search.FlagTerm,
                "a no-filter read must be exactly the UNDELETED FlagTerm; got: " + root);
            assertFalse(((jakarta.mail.search.FlagTerm) root).getTestSet());
        }
    }

    @Nested
    @DisplayName("move: createTargetIfMissing")
    class MoveCreateTargetTests {

        /** Invokes applyAction("move") against mocked IMAP objects. */
        private Map<String, Object> invokeMove(EmailInboxNode node, Store store, Folder source, String target)
                throws Exception {
            Method m = EmailInboxNode.class.getDeclaredMethod("applyAction",
                Store.class, Folder.class, String.class, String.class, ExecutionContext.class, Map.class);
            m.setAccessible(true);
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> r = (Map<String, Object>) m.invoke(
                    node, store, source, "move", target, context, new LinkedHashMap<String, Object>());
                return r;
            } catch (InvocationTargetException e) {
                throw (Exception) e.getCause();
            }
        }

        /** A source folder that resolves UID 42 to a message. */
        private Folder sourceFolderWithMessage(Message msg) throws Exception {
            Folder source = mock(Folder.class, org.mockito.Mockito.withSettings()
                .extraInterfaces(UIDFolder.class));
            when(((UIDFolder) source).getMessageByUID(42L)).thenReturn(msg);
            when(source.getFullName()).thenReturn("INBOX");
            return source;
        }

        private EmailInboxNode moveNode(boolean createTargetIfMissing) {
            return new EmailInboxNode("core:move", new Core.EmailInboxConfig(
                null, "INBOX", false, 10, false, 0, "move", "42", "INBOX.Clients",
                null, null, null, false, 0, false, createTargetIfMissing));
        }

        @Test
        @DisplayName("createTargetIfMissing=true creates the absent destination and still moves the message")
        void createsMissingDestinationThenMoves() throws Exception {
            Message msg = mock(Message.class);
            Folder source = sourceFolderWithMessage(msg);
            Folder dest = mock(Folder.class);
            when(dest.exists()).thenReturn(false);
            when(dest.create(Folder.HOLDS_MESSAGES | Folder.HOLDS_FOLDERS)).thenReturn(true);
            Store store = mock(Store.class);
            when(store.getFolder("INBOX.Clients")).thenReturn(dest);

            Map<String, Object> result = invokeMove(moveNode(true), store, source, "INBOX.Clients");

            verify(dest).create(Folder.HOLDS_MESSAGES | Folder.HOLDS_FOLDERS);
            verify(source).copyMessages(new Message[]{msg}, dest);
            verify(msg).setFlag(Flags.Flag.DELETED, true);
            assertEquals("move", result.get("action"));
        }

        @Test
        @DisplayName("createTargetIfMissing=true does NOT re-create a destination that already exists")
        void doesNotRecreateExistingDestination() throws Exception {
            Message msg = mock(Message.class);
            Folder source = sourceFolderWithMessage(msg);
            Folder dest = mock(Folder.class);
            when(dest.exists()).thenReturn(true);
            Store store = mock(Store.class);
            when(store.getFolder("INBOX.Clients")).thenReturn(dest);

            invokeMove(moveNode(true), store, source, "INBOX.Clients");

            verify(dest, never()).create(org.mockito.ArgumentMatchers.anyInt());
            verify(source).copyMessages(new Message[]{msg}, dest);
        }

        @Test
        @DisplayName("createTargetIfMissing=false on an absent destination fails and names the folders that DO exist")
        void failsWithFolderListWhenNotCreating() throws Exception {
            // The bare "Destination folder not found: X" cost a real debugging detour: it never
            // said what the server actually has, so a guessed name (display label instead of the
            // server path) looked identical to a missing folder.
            Message msg = mock(Message.class);
            Folder source = sourceFolderWithMessage(msg);
            Folder dest = mock(Folder.class);
            when(dest.exists()).thenReturn(false);
            Store store = mock(Store.class);
            when(store.getFolder("INBOX.Clients")).thenReturn(dest);
            Folder root = mock(Folder.class);
            Folder existing = mock(Folder.class);
            when(existing.getType()).thenReturn(Folder.HOLDS_MESSAGES);
            when(existing.getFullName()).thenReturn("INBOX.Drafts");
            when(root.list("*")).thenReturn(new Folder[]{existing});
            when(store.getDefaultFolder()).thenReturn(root);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> invokeMove(moveNode(false), store, source, "INBOX.Clients"));

            assertTrue(ex.getMessage().contains("Destination folder not found: INBOX.Clients"));
            assertTrue(ex.getMessage().contains("INBOX.Drafts"), "must list the folders that exist");
            assertTrue(ex.getMessage().contains("create_folder"), "must point at the way out");
            verify(dest, never()).create(org.mockito.ArgumentMatchers.anyInt());
            verify(source, never()).copyMessages(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }
    }

    @Nested
    @DisplayName("delete/move: message-scoped expunge (the folder-wide expunge is banned)")
    class ScopedExpungeTests {

        /** Invokes applyAction against the given store/folder, unwrapping reflection failures. */
        @SuppressWarnings("unchecked")
        private Map<String, Object> invokeAction(EmailInboxNode node, Store store, Folder folder,
                                                 String action, String target) throws Exception {
            Method m = EmailInboxNode.class.getDeclaredMethod("applyAction",
                Store.class, Folder.class, String.class, String.class, ExecutionContext.class, Map.class);
            m.setAccessible(true);
            try {
                return (Map<String, Object>) m.invoke(node, store, folder, action, target, context,
                    new LinkedHashMap<String, Object>());
            } catch (InvocationTargetException e) {
                throw (Exception) e.getCause();
            }
        }

        /** An IMAP store that does (or does not) advertise UIDPLUS. */
        private IMAPStore imapStore(boolean uidplus) throws Exception {
            IMAPStore store = mock(IMAPStore.class);
            when(store.hasCapability("UIDPLUS")).thenReturn(uidplus);
            return store;
        }

        /** An IMAP source folder that resolves UID 42 to {@code msg}. */
        private IMAPFolder imapFolderWithMessage(Message msg) throws Exception {
            IMAPFolder folder = mock(IMAPFolder.class);
            when(folder.getMessageByUID(42L)).thenReturn(msg);
            when(folder.getFullName()).thenReturn("INBOX");
            return folder;
        }

        private EmailInboxNode deleteNode() {
            return new EmailInboxNode("core:del", config("delete", "42", null));
        }

        @Test
        @DisplayName("regression: delete on a UIDPLUS server expunges ONLY the targeted message, never the folder")
        void deleteOnUidPlusServerExpungesOnlyTheTargetMessage() throws Exception {
            // Pre-fix the delete action called folder.expunge(), which permanently purges EVERY
            // \Deleted-flagged message in the folder - including mail the mailbox OWNER deleted
            // from their own client and had not expunged yet. Silent data loss.
            Message msg = mock(Message.class);
            IMAPFolder folder = imapFolderWithMessage(msg);
            IMAPStore store = imapStore(true);

            Map<String, Object> result = invokeAction(deleteNode(), store, folder, "delete", null);

            verify(msg).setFlag(Flags.Flag.DELETED, true);
            verify(folder).expunge(new Message[]{msg});
            verify(folder, never()).expunge();
            assertEquals(42L, result.get("messageUid"));
        }

        @Test
        @DisplayName("regression: delete without UIDPLUS flags only, it NEVER falls back to the folder-wide expunge")
        void deleteWithoutUidPlusFlagsOnlyAndNeverExpunges() throws Exception {
            Message msg = mock(Message.class);
            IMAPFolder folder = imapFolderWithMessage(msg);
            IMAPStore store = imapStore(false);

            Map<String, Object> result = invokeAction(deleteNode(), store, folder, "delete", null);

            verify(msg).setFlag(Flags.Flag.DELETED, true);
            // No expunge of ANY kind: scoped is unavailable, folder-wide is forbidden.
            verify(folder, never()).expunge();
            verify(folder, never()).expunge(any(Message[].class));
            assertEquals(42L, result.get("messageUid"));
        }

        @Test
        @DisplayName("regression: delete on a non-IMAP store never expunges (and never ClassCastExceptions)")
        void deleteOnNonImapStoreNeverExpunges() throws Exception {
            Message msg = mock(Message.class);
            IMAPFolder folder = imapFolderWithMessage(msg);
            Store plainStore = mock(Store.class);

            Map<String, Object> result = invokeAction(deleteNode(), plainStore, folder, "delete", null);

            verify(msg).setFlag(Flags.Flag.DELETED, true);
            verify(folder, never()).expunge();
            verify(folder, never()).expunge(any(Message[].class));
            assertEquals("delete", result.get("action"));
        }

        @Test
        @DisplayName("regression: a failed capability probe degrades to flag-only, never to a wider expunge")
        void capabilityProbeFailureIsTreatedAsNoUidPlus() throws Exception {
            Message msg = mock(Message.class);
            IMAPFolder folder = imapFolderWithMessage(msg);
            IMAPStore store = mock(IMAPStore.class);
            when(store.hasCapability("UIDPLUS"))
                .thenThrow(new jakarta.mail.MessagingException("capability probe failed"));

            Map<String, Object> result = invokeAction(deleteNode(), store, folder, "delete", null);

            verify(msg).setFlag(Flags.Flag.DELETED, true);
            verify(folder, never()).expunge();
            verify(folder, never()).expunge(any(Message[].class));
            assertEquals(true, result.containsKey("messageUid"));
        }

        // ---- move: COPYUID / newMessageUid --------------------------------

        private EmailInboxNode moveNode() {
            return new EmailInboxNode("core:move", new Core.EmailInboxConfig(
                null, "INBOX", false, 10, false, 0, "move", "42", "INBOX.Clients",
                null, null, null, false, 0, false, false));
        }

        private Folder existingDest(Store store) throws Exception {
            Folder dest = mock(Folder.class);
            when(dest.exists()).thenReturn(true);
            when(store.getFolder("INBOX.Clients")).thenReturn(dest);
            return dest;
        }

        @Test
        @DisplayName("regression: move on a UIDPLUS server surfaces the NEW uid from COPYUID and expunges only the source message")
        void moveOnUidPlusServerSurfacesNewMessageUid() throws Exception {
            // Pre-fix move returned only the SOURCE uid, which is invalid the instant the move
            // completes: a downstream node acting on it fails with "No message with UID".
            Message msg = mock(Message.class);
            IMAPFolder source = imapFolderWithMessage(msg);
            IMAPStore store = imapStore(true);
            Folder dest = existingDest(store);
            when(source.copyUIDMessages(new Message[]{msg}, dest))
                .thenReturn(new AppendUID[]{new AppendUID(9L, 77L)});

            Map<String, Object> result = invokeAction(moveNode(), store, source, "move", "INBOX.Clients");

            verify(source).copyUIDMessages(new Message[]{msg}, dest);
            verify(source, never()).copyMessages(any(), any());
            verify(msg).setFlag(Flags.Flag.DELETED, true);
            verify(source).expunge(new Message[]{msg});
            verify(source, never()).expunge();
            assertEquals(77L, result.get("newMessageUid"));
            assertEquals(42L, result.get("messageUid"), "the source uid stays documented as messageUid");
        }

        @Test
        @DisplayName("move on a UIDPLUS server whose reply carries no COPYUID omits newMessageUid instead of failing")
        void moveWithoutCopyUidResponseOmitsNewMessageUid() throws Exception {
            Message msg = mock(Message.class);
            IMAPFolder source = imapFolderWithMessage(msg);
            IMAPStore store = imapStore(true);
            Folder dest = existingDest(store);
            when(source.copyUIDMessages(new Message[]{msg}, dest)).thenReturn(null);

            Map<String, Object> result = invokeAction(moveNode(), store, source, "move", "INBOX.Clients");

            verify(source).copyUIDMessages(new Message[]{msg}, dest);
            assertFalse(result.containsKey("newMessageUid"),
                "no COPYUID response means no new uid to promise; the field must be absent, not null or 0");
        }

        @Test
        @DisplayName("regression: move without UIDPLUS copies plainly, omits newMessageUid and never expunges the folder")
        void moveWithoutUidPlusCopiesPlainlyAndOmitsNewMessageUid() throws Exception {
            Message msg = mock(Message.class);
            IMAPFolder source = imapFolderWithMessage(msg);
            IMAPStore store = imapStore(false);
            Folder dest = existingDest(store);

            Map<String, Object> result = invokeAction(moveNode(), store, source, "move", "INBOX.Clients");

            verify(source).copyMessages(new Message[]{msg}, dest);
            verify(source, never()).copyUIDMessages(any(), any());
            verify(msg).setFlag(Flags.Flag.DELETED, true);
            verify(source, never()).expunge();
            verify(source, never()).expunge(any(Message[].class));
            assertFalse(result.containsKey("newMessageUid"),
                "without UIDPLUS the source uid is dead after the move and no new uid exists to report");
            assertEquals(42L, result.get("messageUid"));
        }
    }

    // ===============================================================
    @Nested
    @DisplayName("readMessages skips \\Deleted-flagged messages")
    class ReadSkipsDeletedTests {

        /**
         * A folder (with UID support) whose SEARCH returns exactly {@code messages}. readMessages
         * always takes the search path now (buildSearchTerm always carries UNDELETED), so the
         * mock stubs search, not getMessages. The messages still carry a \Deleted flag here so the
         * post-fetch defensive skip is what the test exercises.
         */
        private Folder folderWith(Message... messages) throws Exception {
            Folder folder = mock(Folder.class, org.mockito.Mockito.withSettings()
                .extraInterfaces(UIDFolder.class));
            when(folder.getFullName()).thenReturn("INBOX");
            when(folder.search(any(SearchTerm.class))).thenReturn(messages);
            return folder;
        }

        private Message message(boolean deleted) throws Exception {
            Message msg = mock(Message.class);
            when(msg.isSet(Flags.Flag.DELETED)).thenReturn(deleted);
            return msg;
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> invokeRead(EmailInboxNode node, Folder folder) throws Exception {
            Method m = EmailInboxNode.class.getDeclaredMethod(
                "readMessages", Folder.class, ExecutionContext.class, Map.class);
            m.setAccessible(true);
            try {
                return (Map<String, Object>) m.invoke(node, folder, context, new LinkedHashMap<String, Object>());
            } catch (InvocationTargetException e) {
                throw (Exception) e.getCause();
            }
        }

        @Test
        @DisplayName("regression: a \\Deleted-flagged message is excluded from messages and count")
        void deletedFlaggedMessageIsExcluded() throws Exception {
            // A flagged-but-unexpunged message is logically gone: re-processing it would act on
            // mail the user already deleted. It is also what makes the no-UIDPLUS delete fallback
            // (flag without expunge) safe: our own reads never resurface it.
            Message live = message(false);
            Message deleted = message(true);
            Folder folder = folderWith(live, deleted);
            when(((UIDFolder) folder).getUID(live)).thenReturn(1L);

            EmailInboxNode node = new EmailInboxNode("core:read", config("none", null, null));

            Map<String, Object> result = invokeRead(node, folder);

            assertEquals(1, result.get("count"), "count must cover live messages only");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> out = (List<Map<String, Object>>) result.get("messages");
            assertEquals(1, out.size());
            assertEquals(1L, out.get(0).get("uid"), "the surviving message is the live one");
        }

        @Test
        @DisplayName("regression: the limit is spent on live messages, deleted ones are skipped BEFORE it applies")
        void limitIsSpentOnLiveMessagesOnly() throws Exception {
            // Newest message is deleted; with limit=1 the node must return the newest LIVE
            // message, not burn the whole limit on a logically-gone one.
            Message oldest = message(false);
            Message middle = message(false);
            Message newestDeleted = message(true);
            Folder folder = folderWith(oldest, middle, newestDeleted);
            when(((UIDFolder) folder).getUID(middle)).thenReturn(2L);

            EmailInboxNode node = new EmailInboxNode("core:read", new Core.EmailInboxConfig(
                null, "INBOX", false, 1, false, 0, "none", null, null,
                null, null, null, false, 0, false, false));

            Map<String, Object> result = invokeRead(node, folder);

            assertEquals(1, result.get("count"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> out = (List<Map<String, Object>>) result.get("messages");
            assertEquals(2L, out.get(0).get("uid"),
                "limit=1 must yield the newest LIVE message, not an empty page because the newest was deleted");
        }
    }

    @Nested
    @DisplayName("closeQuietly must not expunge")
    class CloseQuietlyTests {

        private void invokeCloseQuietly(Folder folder) throws Exception {
            Method m = EmailInboxNode.class.getDeclaredMethod("closeQuietly", Folder.class);
            m.setAccessible(true);
            m.invoke(null, folder);
        }

        @Test
        @DisplayName("regression: closes with expunge=false so DELETED-flagged mail the user has not purged survives")
        void closesWithoutExpunging() throws Exception {
            // Pre-fix this was close(true), which permanently deletes EVERY message already
            // flagged DELETED in the folder - including ones the mailbox owner deleted from
            // their own client - on every action and on every markSeen read. Silent data loss
            // with no relation to the requested action.
            Folder folder = mock(Folder.class);
            when(folder.isOpen()).thenReturn(true);

            invokeCloseQuietly(folder);

            verify(folder).close(false);
            verify(folder, never()).close(true);
        }

        @Test
        @DisplayName("Should not touch a folder that is not open")
        void skipsClosedFolder() throws Exception {
            Folder folder = mock(Folder.class);
            when(folder.isOpen()).thenReturn(false);

            invokeCloseQuietly(folder);

            verify(folder, never()).close(org.mockito.ArgumentMatchers.anyBoolean());
        }

        @Test
        @DisplayName("Should swallow a close failure (best effort)")
        void swallowsCloseFailure() throws Exception {
            Folder folder = mock(Folder.class);
            when(folder.isOpen()).thenReturn(true);
            org.mockito.Mockito.doThrow(new jakarta.mail.MessagingException("boom"))
                .when(folder).close(false);

            assertDoesNotThrow(() -> invokeCloseQuietly(folder));
        }
    }

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

    // ===============================================================
    @Nested
    @DisplayName("NUL (U+0000) sanitization of everything the node emits")
    class NulSanitizationTests {

        /**
         * One NUL character, built from its code point so no unicode escape can hide invisibly in
         * this test source.
         *
         * <p>Regression context: a single mail whose text carried a NUL broke the ENTIRE folder
         * read. PostgreSQL cannot store NUL in a text/JSONB column (SQLSTATE 22P05, "unsupported
         * Unicode escape sequence"), so persisting the step-output payload poisoned the
         * transaction, the output blob was dropped, and the node still reported COMPLETED while
         * every downstream node read an EMPTY output. Live symptom on the reporter's mailbox:
         * limit=1 worked, limit=2 failed.
         */
        private static final String NUL = String.valueOf((char) 0);

        private final EmailInboxNode node = new EmailInboxNode("core:read_inbox", config("none", null, null));

        /** An Address whose toString() is exactly {@code raw} (InternetAddress would re-encode it). */
        private Address addr(String raw) {
            return new Address() {
                @Override public String getType() { return "rfc822"; }
                @Override public String toString() { return raw; }
                @Override public boolean equals(Object o) { return this == o; }
                @Override public int hashCode() { return raw.hashCode(); }
            };
        }

        /** Every string field a real message can carry, each poisoned with one NUL mid-text. */
        private MimeMessage poisonedMessage() throws Exception {
            MimeMessage msg = mock(MimeMessage.class);
            when(msg.getFrom()).thenReturn(new Address[]{addr("bo" + NUL + "ss@acme.com")});
            when(msg.getRecipients(Message.RecipientType.TO)).thenReturn(new Address[]{addr("m" + NUL + "e@acme.com")});
            when(msg.getRecipients(Message.RecipientType.CC)).thenReturn(new Address[]{addr("te" + NUL + "am@acme.com")});
            when(msg.getReplyTo()).thenReturn(new Address[]{addr("re" + NUL + "ply@acme.com")});
            when(msg.getSubject()).thenReturn("Invoice" + NUL + " overdue");
            when(msg.getMessageID()).thenReturn("<id" + NUL + "1@acme>");
            when(msg.getHeader("References")).thenReturn(new String[]{"<ref" + NUL + "0@acme>"});
            when(msg.getReceivedDate()).thenReturn(new Date(0));
            when(msg.isMimeType("text/plain")).thenReturn(true);
            when(msg.getContent()).thenReturn("Hello" + NUL + " world");
            return msg;
        }

        private UIDFolder uidFolderFor(Message msg) throws Exception {
            UIDFolder uidFolder = mock(UIDFolder.class);
            when(uidFolder.getUID(msg)).thenReturn(7L);
            return uidFolder;
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> invokeToMessageMap(Message msg, UIDFolder uidFolder, String folderName)
                throws Exception {
            Method m = EmailInboxNode.class.getDeclaredMethod("toMessageMap",
                Message.class, UIDFolder.class, String.class, ExecutionContext.class, boolean.class);
            m.setAccessible(true);
            try {
                return (Map<String, Object>) m.invoke(node, msg, uidFolder, folderName, context, false);
            } catch (InvocationTargetException e) {
                throw (Exception) e.getCause();
            }
        }

        /** The whole emitted structure, flattened to one string, so nothing can hide in a nested map/list. */
        private String flatten(Object value) {
            if (value instanceof Map<?, ?> map) {
                StringBuilder sb = new StringBuilder();
                map.forEach((k, v) -> sb.append(flatten(k)).append('=').append(flatten(v)).append(';'));
                return sb.toString();
            }
            if (value instanceof Collection<?> collection) {
                StringBuilder sb = new StringBuilder();
                collection.forEach(v -> sb.append(flatten(v)).append(','));
                return sb.toString();
            }
            return String.valueOf(value);
        }

        private void assertNoNulAnywhere(Object emitted, String what) {
            assertFalse(flatten(emitted).contains(NUL),
                what + " still carries a NUL, which makes the whole step output unstorable");
        }

        @Test
        @DisplayName("regression: subject keeps its text and loses only the NUL")
        void subjectNulStrippedKeepingSurroundingText() throws Exception {
            MimeMessage msg = poisonedMessage();

            Map<String, Object> m = invokeToMessageMap(msg, uidFolderFor(msg), "INBOX");

            // The meaning must survive: not "", not truncated at the NUL, only the NUL removed.
            assertEquals("Invoice overdue", m.get("subject"));
        }

        @Test
        @DisplayName("regression: from/to/cc/replyTo keep their text and lose only the NUL")
        void addressHeadersNulStripped() throws Exception {
            // Header fields never went through cap(), so they had no sanitization chokepoint at all.
            MimeMessage msg = poisonedMessage();

            Map<String, Object> m = invokeToMessageMap(msg, uidFolderFor(msg), "INBOX");

            assertEquals("boss@acme.com", m.get("from"));
            assertEquals("me@acme.com", m.get("to"));
            assertEquals("team@acme.com", m.get("cc"));
            assertEquals("reply@acme.com", m.get("replyTo"));
        }

        @Test
        @DisplayName("regression: messageId and references keep their text and lose only the NUL")
        void messageIdAndReferencesNulStripped() throws Exception {
            MimeMessage msg = poisonedMessage();

            Map<String, Object> m = invokeToMessageMap(msg, uidFolderFor(msg), "INBOX");

            assertEquals("<id1@acme>", m.get("messageId"));
            assertEquals("<ref0@acme>", m.get("references"));
        }

        @Test
        @DisplayName("regression: the folder name on the message map loses only the NUL")
        void folderNameNulStripped() throws Exception {
            MimeMessage msg = poisonedMessage();

            Map<String, Object> m = invokeToMessageMap(msg, uidFolderFor(msg), "INBOX" + NUL + ".Clients");

            assertEquals("INBOX.Clients", m.get("folder"));
        }

        @Test
        @DisplayName("regression: body and snippet keep their text and lose only the NUL")
        void bodyAndSnippetNulStripped() throws Exception {
            MimeMessage msg = poisonedMessage();

            Map<String, Object> m = invokeToMessageMap(msg, uidFolderFor(msg), "INBOX");

            assertEquals("Hello world", m.get("body"));
            assertEquals("Hello world", m.get("snippet"));
        }

        @Test
        @DisplayName("regression: bodyHtml (and the text derived from it) keep their content and lose only the NUL")
        void bodyHtmlNulStripped() throws Exception {
            // An html-only mail: bodyHtml is emitted raw and body is derived from it.
            MimeMessage msg = mock(MimeMessage.class);
            when(msg.getSubject()).thenReturn("hi");
            when(msg.isMimeType("text/plain")).thenReturn(false);
            when(msg.isMimeType("text/html")).thenReturn(true);
            when(msg.getContent()).thenReturn("<p>Hel" + NUL + "lo</p>");

            Map<String, Object> m = invokeToMessageMap(msg, uidFolderFor(msg), "INBOX");

            assertEquals("<p>Hello</p>", m.get("bodyHtml"));
            assertEquals("Hello", m.get("body"));
        }

        @Test
        @DisplayName("regression: no field of the emitted message map carries a NUL")
        void wholeMessageMapIsFreeOfNul() throws Exception {
            // The catch-all: one poisoned mail must not leave a NUL ANYWHERE in the payload,
            // because a single one anywhere kills the whole read.
            MimeMessage msg = poisonedMessage();

            Map<String, Object> m = invokeToMessageMap(msg, uidFolderFor(msg), "INBOX" + NUL + ".Clients");

            assertNoNulAnywhere(m, "the message map");
        }

        @Test
        @DisplayName("A healthy message is emitted unchanged (the strip is a no-op without a NUL)")
        void healthyMessageIsUnchanged() throws Exception {
            MimeMessage msg = mock(MimeMessage.class);
            when(msg.getFrom()).thenReturn(new Address[]{addr("boss@acme.com")});
            when(msg.getSubject()).thenReturn("Invoice overdue");
            when(msg.getMessageID()).thenReturn("<id1@acme>");
            when(msg.getHeader("References")).thenReturn(new String[]{"<ref0@acme>"});
            when(msg.isMimeType("text/plain")).thenReturn(true);
            when(msg.getContent()).thenReturn("Hello world");

            Map<String, Object> m = invokeToMessageMap(msg, uidFolderFor(msg), "INBOX.Clients");

            assertEquals("boss@acme.com", m.get("from"));
            assertEquals("Invoice overdue", m.get("subject"));
            assertEquals("<id1@acme>", m.get("messageId"));
            assertEquals("<ref0@acme>", m.get("references"));
            assertEquals("INBOX.Clients", m.get("folder"));
            assertEquals("Hello world", m.get("body"));
            assertEquals("Hello world", m.get("snippet"));
            assertEquals(7L, m.get("uid"));
        }

        @Test
        @DisplayName("regression: attachment filename and contentType keep their text and lose only the NUL")
        void attachmentMetadataNulStripped() throws Exception {
            Part part = mock(Part.class);
            when(part.getContentType()).thenReturn("application/p" + NUL + "df; name=x");
            when(part.getSize()).thenReturn(12);

            Method m = EmailInboxNode.class.getDeclaredMethod(
                "toAttachment", Part.class, String.class, boolean.class, ExecutionContext.class);
            m.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Object> att = (Map<String, Object>) m.invoke(
                node, part, "in" + NUL + "voice.pdf", false, context);

            assertEquals("invoice.pdf", att.get("filename"));
            assertEquals("application/pdf", att.get("contentType"));
            assertNoNulAnywhere(att, "the attachment map");
        }

        @Test
        @DisplayName("regression: server folder names emitted by list_folders lose only the NUL")
        void listedFolderNamesNulStripped() throws Exception {
            Folder poisoned = mock(Folder.class);
            when(poisoned.getType()).thenReturn(Folder.HOLDS_MESSAGES);
            when(poisoned.getFullName()).thenReturn("INBOX" + NUL + ".Clients");
            Folder root = mock(Folder.class);
            when(root.list("*")).thenReturn(new Folder[]{poisoned});
            Store store = mock(Store.class);
            when(store.getDefaultFolder()).thenReturn(root);

            Method m = EmailInboxNode.class.getDeclaredMethod("listFolders", Store.class);
            m.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) m.invoke(node, store);

            assertEquals(List.of("INBOX.Clients"), result.get("folders"));
        }

        @Test
        @DisplayName("regression: the read result reports the folder without its NUL")
        void readResultFolderNulStripped() throws Exception {
            // readMessages emits its OWN copy of the server folder name, next to the per-message
            // one. An empty folder is enough: the name is emitted whether or not mail came back.
            Folder folder = mock(Folder.class, org.mockito.Mockito.withSettings()
                .extraInterfaces(UIDFolder.class));
            when(folder.getFullName()).thenReturn("INBOX" + NUL + ".Clients");
            // readMessages always takes the SEARCH path now (UNDELETED term), so stub search.
            when(folder.search(any(SearchTerm.class))).thenReturn(new Message[]{});

            Method m = EmailInboxNode.class.getDeclaredMethod(
                "readMessages", Folder.class, ExecutionContext.class, Map.class);
            m.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) m.invoke(
                node, folder, context, new LinkedHashMap<String, Object>());

            assertEquals("INBOX.Clients", result.get("folder"));
            assertEquals(0, result.get("count"));
        }

        @Test
        @DisplayName("regression: a message action reports the folder without its NUL")
        void actionResultFolderNulStripped() throws Exception {
            Message msg = mock(Message.class);
            Folder source = mock(Folder.class, org.mockito.Mockito.withSettings()
                .extraInterfaces(UIDFolder.class));
            when(((UIDFolder) source).getMessageByUID(42L)).thenReturn(msg);
            when(source.getFullName()).thenReturn("INBOX" + NUL + ".Clients");
            EmailInboxNode flagNode = new EmailInboxNode("core:flag", config("flag", "42", null));

            Method m = EmailInboxNode.class.getDeclaredMethod("applyAction",
                Store.class, Folder.class, String.class, String.class, ExecutionContext.class, Map.class);
            m.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) m.invoke(
                flagNode, mock(Store.class), source, "flag", null, context, new LinkedHashMap<String, Object>());

            verify(msg).setFlag(Flags.Flag.FLAGGED, true);
            assertEquals("INBOX.Clients", result.get("folder"));
        }

        @Test
        @DisplayName("regression: an error quoting a server folder name carries no NUL into the output")
        void actionErrorMessageNulStripped() throws Exception {
            // "No message with UID 42 in folder <name>" quotes a server-owned string, and that
            // error is emitted as output.error - the same unstorable payload as a success.
            Folder source = mock(Folder.class, org.mockito.Mockito.withSettings()
                .extraInterfaces(UIDFolder.class));
            when(((UIDFolder) source).getMessageByUID(42L)).thenReturn(null);
            when(source.getFullName()).thenReturn("INBOX" + NUL + ".Clients");
            EmailInboxNode flagNode = new EmailInboxNode("core:flag", config("flag", "42", null));

            Method m = EmailInboxNode.class.getDeclaredMethod("applyAction",
                Store.class, Folder.class, String.class, String.class, ExecutionContext.class, Map.class);
            m.setAccessible(true);
            InvocationTargetException wrapper = assertThrows(InvocationTargetException.class,
                () -> m.invoke(flagNode, mock(Store.class), source, "flag", null, context,
                    new LinkedHashMap<String, Object>()));

            String message = wrapper.getCause().getMessage();
            assertEquals("No message with UID 42 in folder INBOX.Clients", message);
        }

        @Test
        @DisplayName("regression: a template resolving to NUL-bearing upstream data is stripped before it reaches resolved_params")
        void resolvedParamsFolderNulStripped() {
            // resolved_params is emitted on success AND on failure, so upstream data reaching it
            // (another node's output, a webhook body) is the same unstorable payload.
            EmailInboxNode readNode = new EmailInboxNode("core:read", new Core.EmailInboxConfig(
                null, "{{trigger:t.output.box}}", false, 10, false, 0, "none", null, null,
                null, null, null, false, 0, false, false));
            Map<String, Object> cred = validImapCredentialData();
            cred.put("host", "127.0.0.1");
            cred.put("port", 1);
            cred.put("use_ssl", "false");
            wireCredentialClient(readNode, cred);
            V2TemplateAdapter adapter = mock(V2TemplateAdapter.class);
            when(adapter.resolveTemplates(any(), any())).thenAnswer(inv -> {
                Map<String, Object> out = new LinkedHashMap<>();
                ((Map<String, Object>) inv.getArgument(0))
                    .forEach((k, v) -> out.put(k, "INBOX" + NUL + ".Archive"));
                return out;
            });
            when(mockServiceRegistry.getTemplateAdapter()).thenReturn(adapter);
            readNode.acceptServices(mockServiceRegistry);

            NodeExecutionResult result = readNode.execute(context);

            @SuppressWarnings("unchecked")
            Map<String, Object> resolved = (Map<String, Object>) result.output().get("resolved_params");
            assertEquals("INBOX.Archive", resolved.get("folder"));
            assertNoNulAnywhere(result.output(), "the failure output");
        }
    }
}
