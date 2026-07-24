package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.service.NodeLibraryService;
import com.apimarketplace.orchestrator.tools.workflow.builder.ResponseOptimizer;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * Guards that {@code add_node} persists the mail nodes' newer config params.
 *
 * <p>{@code add_node} maps agent parameters through an explicit allow-list, so a param added to
 * the config record does NOT reach a node the agent creates unless this list is extended too. The
 * gap is invisible in review because the other two write paths need no change: set_plan
 * deserializes the whole config with Jackson, and modify merges nested config generically. Only
 * add_node, the agent's primary create path, drops the param, and the node then silently behaves
 * as if it were never set. Same bug class as
 * {@link UtilityNodeCreatorExtractFromFileChunkUnitTest}.
 *
 * <p>Covered here: {@code email_inbox.createTargetIfMissing} (without it a move against a missing
 * folder fails with "Destination folder not found", the exact error the flag exists to prevent),
 * and {@code send_email.replyTo} / {@code send_email.fromEmail}. All three are documented to the
 * agent, so a node built from the documented example must carry them.
 *
 * <p>These are guards, not regressions: {@code createTargetIfMissing} and {@code replyTo} ship in
 * the same change, and {@code fromEmail}, while it predates it, was inert because the node never
 * read it. Each test does fail if the corresponding mapping is removed.
 *
 * <p>Also covered (fourth instance of the class): {@code credentialId} on BOTH mail nodes. The
 * runtime nodes select the SMTP/IMAP credential by {@code credentialId} (falling back to the
 * default credential), set_plan and modify persist it, and the inspector sets it - but add_node
 * dropped it, so an agent pinning a specific mailbox credential silently got the default one.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UtilityNodeCreator - mail node param persistence (add_node)")
class UtilityNodeCreatorMailParamsTest {

    @Mock private WorkflowBuilderSessionStore sessionStore;
    @Mock private ResponseOptimizer responseOptimizer;
    @Mock private NodeLibraryService nodeLibraryService;
    @Mock private WorkflowRepository workflowRepository;

    private UtilityNodeCreator creator;
    private WorkflowBuilderSession session;

    @BeforeEach
    void setUp() {
        creator = new UtilityNodeCreator(sessionStore, responseOptimizer, nodeLibraryService, workflowRepository);
        session = WorkflowBuilderSession.builder()
            .sessionId("s").tenantId("t").workflowName("w")
            .createdAt(Instant.now()).updatedAt(Instant.now())
            .build();
        Map<String, Object> trig = new LinkedHashMap<>();
        trig.put("label", "Start");
        trig.put("id", "trigger:start");
        trig.put("type", "webhook");
        session.getTriggers().add(trig);

        lenient().when(nodeLibraryService.findByType(anyString())).thenReturn(Optional.empty());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> configOf(String nestedKey) {
        Map<String, Object> node = session.getCores().get(session.getCores().size() - 1);
        return (Map<String, Object>) node.get(nestedKey);
    }

    @Nested
    @DisplayName("email_inbox createTargetIfMissing")
    class EmailInboxCreateTargetIfMissing {

        private Map<String, Object> moveParams() {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("label", "File As Client");
            p.put("action", "move");
            p.put("targetFolder", "INBOX.Clients");
            p.put("messageUid", "{{core:snapshot.output.uid}}");
            p.put("connect_after", "Start");
            return p;
        }

        @Test
        @DisplayName("createTargetIfMissing=true is persisted, so the documented move example works")
        void createTargetIfMissingIsPersisted() {
            Map<String, Object> p = moveParams();
            p.put("createTargetIfMissing", true);

            creator.executeAddEmailInbox(session, p);

            assertThat(configOf("emailInbox")).containsEntry("createTargetIfMissing", true);
        }

        @Test
        @DisplayName("The snake_case alias is accepted like every other param on this node")
        void snakeCaseAliasIsPersisted() {
            Map<String, Object> p = moveParams();
            p.put("create_target_if_missing", true);

            creator.executeAddEmailInbox(session, p);

            assertThat(configOf("emailInbox")).containsEntry("createTargetIfMissing", true);
        }

        @Test
        @DisplayName("createTargetIfMissing=false is persisted, not treated as absent")
        void explicitFalseIsPersisted() {
            Map<String, Object> p = moveParams();
            p.put("createTargetIfMissing", false);

            creator.executeAddEmailInbox(session, p);

            assertThat(configOf("emailInbox")).containsEntry("createTargetIfMissing", false);
        }

        @Test
        @DisplayName("Omitting it leaves the key out, so the record default (no folder creation) applies")
        void omittedStaysAbsent() {
            creator.executeAddEmailInbox(session, moveParams());

            assertThat(configOf("emailInbox")).doesNotContainKey("createTargetIfMissing");
        }

        @Test
        @DisplayName("create_folder persists its targetFolder verbatim, keeping the server path intact")
        void createFolderPersistsTargetFolder() {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("label", "Create Clients Folder");
            p.put("action", "create_folder");
            p.put("targetFolder", "INBOX.A Repondre.Clients");
            p.put("connect_after", "Start");

            creator.executeAddEmailInbox(session, p);

            assertThat(configOf("emailInbox"))
                .containsEntry("action", "create_folder")
                .containsEntry("targetFolder", "INBOX.A Repondre.Clients");
        }
    }

    @Nested
    @DisplayName("send_email replyTo / fromEmail")
    class SendEmailReplyToAndFromEmail {

        private Map<String, Object> sendParams() {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("label", "Send Reply");
            p.put("toEmail", "to@example.com");
            p.put("subject", "Subject");
            p.put("body", "Body");
            p.put("connect_after", "Start");
            return p;
        }

        @Test
        @DisplayName("replyTo is persisted, so the param the docs advertise reaches the sent mail")
        void replyToIsPersisted() {
            Map<String, Object> p = sendParams();
            p.put("replyTo", "team@example.com");

            creator.executeAddSendEmail(session, p);

            assertThat(configOf("sendEmail")).containsEntry("replyTo", "team@example.com");
        }

        @Test
        @DisplayName("regression: fromEmail is persisted, so the now-live sender override is not silently lost")
        void fromEmailIsPersisted() {
            Map<String, Object> p = sendParams();
            p.put("fromEmail", "alias@example.com");

            creator.executeAddSendEmail(session, p);

            assertThat(configOf("sendEmail")).containsEntry("fromEmail", "alias@example.com");
        }

        @Test
        @DisplayName("snake_case aliases are accepted for both")
        void snakeCaseAliasesAccepted() {
            Map<String, Object> p = sendParams();
            p.put("reply_to", "team@example.com");
            p.put("from_email", "alias@example.com");

            creator.executeAddSendEmail(session, p);

            assertThat(configOf("sendEmail"))
                .containsEntry("replyTo", "team@example.com")
                .containsEntry("fromEmail", "alias@example.com");
        }

        @Test
        @DisplayName("Omitting them leaves the keys out, so the credential's own address is used")
        void omittedStayAbsent() {
            creator.executeAddSendEmail(session, sendParams());

            assertThat(configOf("sendEmail"))
                .doesNotContainKey("replyTo")
                .doesNotContainKey("fromEmail");
        }

        @Test
        @DisplayName("regression: credentialId is persisted, so the pinned SMTP credential is used instead of the default")
        void credentialIdIsPersisted() {
            Map<String, Object> p = sendParams();
            p.put("credentialId", 40);

            creator.executeAddSendEmail(session, p);

            assertThat(configOf("sendEmail")).containsEntry("credentialId", 40L);
        }

        @Test
        @DisplayName("credentialId accepts the snake_case alias and a numeric string (LLM-quoted \"40\")")
        void credentialIdAliasAndNumericString() {
            Map<String, Object> p = sendParams();
            p.put("credential_id", "40");

            creator.executeAddSendEmail(session, p);

            assertThat(configOf("sendEmail")).containsEntry("credentialId", 40L);
        }

        @Test
        @DisplayName("Omitting credentialId leaves the key out, so the default SMTP credential applies")
        void credentialIdOmittedStaysAbsent() {
            creator.executeAddSendEmail(session, sendParams());

            assertThat(configOf("sendEmail")).doesNotContainKey("credentialId");
        }
    }

    @Nested
    @DisplayName("email_inbox credentialId")
    class EmailInboxCredentialId {

        private Map<String, Object> readParams() {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("label", "Check Inbox");
            p.put("folder", "INBOX");
            p.put("connect_after", "Start");
            return p;
        }

        @Test
        @DisplayName("regression: credentialId is persisted, so the pinned IMAP credential is used instead of the default")
        void credentialIdIsPersisted() {
            Map<String, Object> p = readParams();
            p.put("credentialId", 41);

            creator.executeAddEmailInbox(session, p);

            assertThat(configOf("emailInbox")).containsEntry("credentialId", 41L);
        }

        @Test
        @DisplayName("credentialId accepts the snake_case alias and a numeric string")
        void credentialIdAliasAndNumericString() {
            Map<String, Object> p = readParams();
            p.put("credential_id", "41");

            creator.executeAddEmailInbox(session, p);

            assertThat(configOf("emailInbox")).containsEntry("credentialId", 41L);
        }

        @Test
        @DisplayName("Omitting credentialId leaves the key out, so the default IMAP credential applies")
        void credentialIdOmittedStaysAbsent() {
            creator.executeAddEmailInbox(session, readParams());

            assertThat(configOf("emailInbox")).doesNotContainKey("credentialId");
        }
    }
}
