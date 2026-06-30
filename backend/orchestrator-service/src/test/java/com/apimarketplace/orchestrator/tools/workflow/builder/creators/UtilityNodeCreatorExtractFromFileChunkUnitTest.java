package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.apimarketplace.orchestrator.service.NodeLibraryService;
import com.apimarketplace.orchestrator.tools.workflow.builder.ResponseOptimizer;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
 * Regression for the token-chunking gap: the {@code add_node} builder for
 * {@code extract_from_file} silently dropped the {@code chunkUnit} parameter,
 * so an agent-created node fell back to character chunking at runtime even when
 * it asked for tokens. The four runtime/help/doc/frontend layers carried
 * {@code chunkUnit}, but this builder (the agent's create path) did not.
 *
 * Pre-fix, {@link #chunkUnitTokenIsPersistedInTextMode()} and
 * {@link #chunkUnitSnakeCaseAliasIsPersisted()} fail because the persisted
 * config has no {@code chunkUnit} key.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UtilityNodeCreator - extract_from_file chunkUnit persistence (add_node)")
class UtilityNodeCreatorExtractFromFileChunkUnitTest {

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
            .sessionId("s")
            .tenantId("t")
            .workflowName("w")
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
        Map<String, Object> trig = new LinkedHashMap<>();
        trig.put("label", "Start");
        trig.put("id", "trigger:start");
        trig.put("type", "webhook");
        session.getTriggers().add(trig);

        lenient().when(nodeLibraryService.findByType(anyString())).thenReturn(Optional.empty());
    }

    /** Base text-mode chunking params, WITHOUT chunkUnit (caller adds it). */
    private Map<String, Object> baseTextChunkingParams() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", "Chunk Doc");
        p.put("connect_after", "Start");
        p.put("format", "txt");
        p.put("mode", "text");
        p.put("value", "Retrieval augmented generation splits documents into passages.");
        p.put("chunking", true);
        p.put("chunkSize", 50);
        p.put("overlap", 10);
        p.put("chunkingStrategy", "recursive");
        return p;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractConfig() {
        Map<String, Object> node = session.getCores().get(0);
        return (Map<String, Object>) node.get("extractFromFile");
    }

    @Test
    @DisplayName("chunkUnit 'token' is persisted into the node config in text mode")
    void chunkUnitTokenIsPersistedInTextMode() {
        Map<String, Object> p = baseTextChunkingParams();
        p.put("chunkUnit", "token");

        ToolExecutionResult r = creator.executeAddExtractFromFile(session, p);

        assertThat(r.success()).isTrue();
        assertThat(extractConfig())
            .as("the agent's add_node create path must persist chunkUnit, not drop it")
            .containsEntry("chunkUnit", "token");
    }

    @Test
    @DisplayName("snake_case 'chunk_unit' alias is persisted as chunkUnit")
    void chunkUnitSnakeCaseAliasIsPersisted() {
        Map<String, Object> p = baseTextChunkingParams();
        p.put("chunk_unit", "token");

        ToolExecutionResult r = creator.executeAddExtractFromFile(session, p);

        assertThat(r.success()).isTrue();
        assertThat(extractConfig()).containsEntry("chunkUnit", "token");
    }

    @Test
    @DisplayName("chunkUnit 'char' is persisted verbatim in text mode")
    void chunkUnitCharIsPersistedInTextMode() {
        Map<String, Object> p = baseTextChunkingParams();
        p.put("chunkUnit", "char");

        ToolExecutionResult r = creator.executeAddExtractFromFile(session, p);

        assertThat(r.success()).isTrue();
        assertThat(extractConfig()).containsEntry("chunkUnit", "char");
    }

    @Test
    @DisplayName("chunkUnit coexists with the other chunking params, none clobbered")
    void chunkUnitCoexistsWithOtherChunkParams() {
        Map<String, Object> p = baseTextChunkingParams();
        p.put("chunkUnit", "token");

        creator.executeAddExtractFromFile(session, p);

        assertThat(extractConfig())
            .containsEntry("mode", "text")
            .containsEntry("chunking", true)
            .containsEntry("chunkSize", 50)
            .containsEntry("overlap", 10)
            .containsEntry("chunkingStrategy", "recursive")
            .containsEntry("chunkUnit", "token");
    }

    @Test
    @DisplayName("chunkUnit omitted leaves no key (runtime defaults to char) - legacy plans unchanged")
    void chunkUnitOmittedLeavesNoKey() {
        ToolExecutionResult r = creator.executeAddExtractFromFile(session, baseTextChunkingParams());

        assertThat(r.success()).isTrue();
        assertThat(extractConfig())
            .as("absent chunkUnit must stay absent so existing plans keep their char default")
            .doesNotContainKey("chunkUnit");
    }

    @Test
    @DisplayName("the agent-visible saved_params in the response carries chunkUnit")
    @SuppressWarnings("unchecked")
    void savedParamsResponseCarriesChunkUnit() {
        Map<String, Object> p = baseTextChunkingParams();
        p.put("chunkUnit", "token");

        ToolExecutionResult r = creator.executeAddExtractFromFile(session, p);

        assertThat(r.success()).isTrue();
        Map<String, Object> data = (Map<String, Object>) r.data();
        Map<String, Object> savedParams = (Map<String, Object>) data.get("saved_params");
        assertThat(savedParams)
            .as("the agent only learns what was stored from saved_params; it must echo chunkUnit")
            .containsEntry("chunkUnit", "token");
    }

    @Test
    @DisplayName("the stored config parses into the runtime record as token unit (cross-layer key contract)")
    void storedConfigParsesToTokenUnitRecord() {
        Map<String, Object> p = baseTextChunkingParams();
        p.put("chunkUnit", "token");

        creator.executeAddExtractFromFile(session, p);

        // The runtime reads the persisted 'extractFromFile' map back through this record.
        // Proves the key the builder writes is exactly the key the executor consumes.
        Core.ExtractFromFileConfig parsed =
            new ObjectMapper().convertValue(extractConfig(), Core.ExtractFromFileConfig.class);
        assertThat(parsed.chunkUnit()).isEqualTo("token");
        assertThat(parsed.isTokenUnit())
            .as("a chunkUnit='token' node built by the agent must drive token metering at runtime")
            .isTrue();
    }

    @Test
    @DisplayName("chunkUnit is not written in structured mode (text-only parameter)")
    void chunkUnitIgnoredInStructuredMode() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", "Parse CSV");
        p.put("connect_after", "Start");
        p.put("format", "csv");
        p.put("mode", "structured");
        p.put("chunkUnit", "token");

        ToolExecutionResult r = creator.executeAddExtractFromFile(session, p);

        assertThat(r.success()).isTrue();
        assertThat(extractConfig())
            .as("chunkUnit only applies to text-mode chunking")
            .doesNotContainKey("chunkUnit");
    }
}
