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
 * Regression for the 4th/5th/6th instances of the add_node allow-list bug class: the ssh,
 * sftp and database creators hand-built their config maps and NEVER read {@code credentialId},
 * even though the runtime nodes select the connection credential by it ({@code SshNode},
 * {@code SftpNode}, {@code DatabaseNode}) and ssh/database ADVERTISE it to the agent in
 * WorkflowBuilderPrompts. An agent pinning a stored credential (following the prompt's own
 * docs) silently got the default one. set_plan and modify persisted it; only add_node dropped
 * it. Same class as {@link UtilityNodeCreatorExtractFromFileChunkUnitTest},
 * {@link UtilityNodeCreatorMailParamsTest}, {@link DecisionNodeCreatorApprovalContinuationModeTest};
 * the class is guarded exhaustively by {@link AddNodeConfigRecordContractTest}.
 *
 * <p>Secondary: {@code port}/{@code timeout} were accepted only as {@code instanceof Number},
 * so an LLM-quoted "22" was silently dropped. They are now coerced via getInt like the other
 * numeric params (a numeric String survives).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UtilityNodeCreator - ssh/sftp/database credentialId + numeric coercion (add_node)")
class UtilityNodeCreatorConnectionCredentialTest {

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
    @DisplayName("ssh")
    class Ssh {
        private Map<String, Object> base() {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("label", "Run Command");
            p.put("host", "server.example.com");
            p.put("command", "ls -la");
            p.put("connect_after", "Start");
            return p;
        }

        @Test
        @DisplayName("regression: credentialId is persisted, so the pinned SSH credential is used")
        void credentialIdPersisted() {
            Map<String, Object> p = base();
            p.put("credentialId", 40);
            creator.executeAddSsh(session, p);
            assertThat(configOf("ssh")).containsEntry("credentialId", 40L);
        }

        @Test
        @DisplayName("credentialId snake_case alias + numeric string are accepted")
        void credentialIdAliasNumericString() {
            Map<String, Object> p = base();
            p.put("credential_id", "40");
            creator.executeAddSsh(session, p);
            assertThat(configOf("ssh")).containsEntry("credentialId", 40L);
        }

        @Test
        @DisplayName("regression: a quoted numeric port \"2222\" is coerced, not dropped")
        void quotedPortCoerced() {
            Map<String, Object> p = base();
            p.put("port", "2222");
            creator.executeAddSsh(session, p);
            assertThat(configOf("ssh")).containsEntry("port", 2222);
        }

        @Test
        @DisplayName("a quoted numeric timeout is coerced too")
        void quotedTimeoutCoerced() {
            Map<String, Object> p = base();
            p.put("timeout", "5000");
            creator.executeAddSsh(session, p);
            assertThat(configOf("ssh")).containsEntry("timeout", 5000);
        }

        @Test
        @DisplayName("omitting credentialId leaves the key out (default credential applies)")
        void credentialIdOmitted() {
            creator.executeAddSsh(session, base());
            assertThat(configOf("ssh")).doesNotContainKey("credentialId");
        }
    }

    @Nested
    @DisplayName("sftp")
    class Sftp {
        private Map<String, Object> base() {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("label", "List Files");
            p.put("host", "server.example.com");
            p.put("operation", "list");
            p.put("connect_after", "Start");
            return p;
        }

        @Test
        @DisplayName("regression: credentialId is persisted")
        void credentialIdPersisted() {
            Map<String, Object> p = base();
            p.put("credentialId", 41);
            creator.executeAddSftp(session, p);
            assertThat(configOf("sftp")).containsEntry("credentialId", 41L);
        }

        @Test
        @DisplayName("credentialId snake_case alias + numeric string are accepted")
        void credentialIdAliasNumericString() {
            Map<String, Object> p = base();
            p.put("credential_id", "41");
            creator.executeAddSftp(session, p);
            assertThat(configOf("sftp")).containsEntry("credentialId", 41L);
        }

        @Test
        @DisplayName("regression: a quoted numeric port is coerced")
        void quotedPortCoerced() {
            Map<String, Object> p = base();
            p.put("port", "2222");
            creator.executeAddSftp(session, p);
            assertThat(configOf("sftp")).containsEntry("port", 2222);
        }

        @Test
        @DisplayName("omitting credentialId leaves the key out")
        void credentialIdOmitted() {
            creator.executeAddSftp(session, base());
            assertThat(configOf("sftp")).doesNotContainKey("credentialId");
        }
    }

    @Nested
    @DisplayName("database")
    class Database {
        private Map<String, Object> base() {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("label", "Query Users");
            p.put("host", "db.example.com");
            p.put("databaseName", "mydb");
            p.put("query", "SELECT 1");
            p.put("operation", "select");
            p.put("connect_after", "Start");
            return p;
        }

        @Test
        @DisplayName("regression: credentialId is persisted, so the pinned database credential is used")
        void credentialIdPersisted() {
            Map<String, Object> p = base();
            p.put("credentialId", 42);
            creator.executeAddDatabase(session, p);
            assertThat(configOf("database")).containsEntry("credentialId", 42L);
        }

        @Test
        @DisplayName("credentialId snake_case alias + numeric string are accepted")
        void credentialIdAliasNumericString() {
            Map<String, Object> p = base();
            p.put("credential_id", "42");
            creator.executeAddDatabase(session, p);
            assertThat(configOf("database")).containsEntry("credentialId", 42L);
        }

        @Test
        @DisplayName("regression: a quoted numeric port is coerced")
        void quotedPortCoerced() {
            Map<String, Object> p = base();
            p.put("port", "5433");
            creator.executeAddDatabase(session, p);
            assertThat(configOf("database")).containsEntry("port", 5433);
        }

        @Test
        @DisplayName("a quoted boolean sslEnabled \"true\" is coerced, not dropped")
        void quotedSslCoerced() {
            Map<String, Object> p = base();
            p.put("sslEnabled", "true");
            creator.executeAddDatabase(session, p);
            assertThat(configOf("database")).containsEntry("sslEnabled", true);
        }

        @Test
        @DisplayName("omitting credentialId leaves the key out")
        void credentialIdOmitted() {
            creator.executeAddDatabase(session, base());
            assertThat(configOf("database")).doesNotContainKey("credentialId");
        }
    }
}
