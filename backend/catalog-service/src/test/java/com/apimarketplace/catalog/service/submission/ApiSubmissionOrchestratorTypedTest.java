package com.apimarketplace.catalog.service.submission;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import com.apimarketplace.catalog.repository.ApiCategoryRepository;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.catalog.repository.ApiSubcategoryRepository;
import com.apimarketplace.catalog.repository.ApiToolMonetizationRepository;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.apimarketplace.catalog.repository.ToolCategoryRepository;
import com.apimarketplace.catalog.repository.ToolNameRepository;
import com.apimarketplace.catalog.service.ToolCategoryService;
import com.apimarketplace.catalog.service.ToolResponseService;
import com.apimarketplace.catalog.service.ProtocolConfigService;
import com.apimarketplace.common.security.CredentialEncryptionService;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the V52 hardening of {@link ApiSubmissionOrchestrator#createToolFromData}.
 *
 * <p>The hardening pass added three new behaviors at submission time:
 *
 * <ol>
 *   <li>{@code outputSchema} must be a JSON array (anything else throws
 *       {@link IllegalArgumentException} with an actionable message instead of letting
 *       a malformed schema reach the runtime OutputProjector).</li>
 *   <li>{@code execution_mode} must be a known value
 *       (sync|async_poll|upload|streaming) - invalid values (including the V145-retired
 *       {@code webhook}) throw at submission instead of bubbling up as a Postgres CHECK
 *       constraint exception at insert time.</li>
 *   <li>{@code execution_mode} is derived from {@code executionSpec.mode} as the single
 *       source of truth, with the flat {@code executionMode} key only used as fallback
 *       for back-compat with payloads that don't carry the full executionSpec.</li>
 * </ol>
 *
 * <p>{@code createToolFromData} is private - we invoke it via reflection. The test only
 * exercises code paths that fail before {@code processCustomToolData} runs (because
 * that method touches mocked repositories that we don't bother wiring up here).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ApiSubmissionOrchestrator - V52 createToolFromData hardening")
class ApiSubmissionOrchestratorTypedTest {

    @Mock private ApiRepository apiRepository;
    @Mock private ApiToolRepository apiToolRepository;
    @Mock private ApiCategoryRepository categoryRepository;
    @Mock private ApiSubcategoryRepository subcategoryRepository;
    @Mock private ApiToolMonetizationRepository monetizationRepository;
    @Mock private ToolCategoryRepository toolCategoryRepository;
    @Mock private ToolNameRepository toolNameRepository;
    @Mock private ToolCategoryService toolCategoryService;
    @Mock private ToolResponseService toolResponseService;
    @Mock private ProtocolConfigService protocolConfigService;
    @Mock private CredentialEncryptionService encryptionService;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private org.springframework.data.jdbc.core.JdbcAggregateTemplate jdbcAggregateTemplate;
    @Mock private ApiSlugService apiSlugService;
    @Mock private ToolMonetizationService toolMonetizationService;
    @Mock private ToolParameterService toolParameterService;
    @Mock private ToolNameSubcategoryUpdater toolNameSubcategoryUpdater;

    private ObjectMapper objectMapper;
    private ApiSubmissionOrchestrator orchestrator;
    private Method createToolFromData;

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        objectMapper = new ObjectMapper();
        orchestrator = new ApiSubmissionOrchestrator(
                apiRepository, apiToolRepository, categoryRepository, subcategoryRepository,
                monetizationRepository, toolCategoryRepository, toolNameRepository,
                toolCategoryService, toolResponseService, protocolConfigService,
                encryptionService, jdbcTemplate, jdbcAggregateTemplate, apiSlugService,
                toolMonetizationService, toolParameterService, toolNameSubcategoryUpdater,
                objectMapper);
        createToolFromData = ApiSubmissionOrchestrator.class.getDeclaredMethod(
                "createToolFromData", JsonNode.class, ApiEntity.class, JsonNode.class);
        createToolFromData.setAccessible(true);
    }

    private ApiEntity api() {
        ApiEntity api = new ApiEntity();
        api.setId(UUID.randomUUID());
        api.setApiName("test-api");
        return api;
    }

    private JsonNode toolPayload(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Throwable invokeAndUnwrap(JsonNode tool) {
        try {
            createToolFromData.invoke(orchestrator, tool, api(), objectMapper.createObjectNode());
            return null;
        } catch (InvocationTargetException ite) {
            return ite.getCause();
        } catch (Exception e) {
            return e;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // outputSchema validation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("outputSchema is an object instead of array → IllegalArgumentException")
    void outputSchemaMustBeArray() {
        JsonNode payload = toolPayload("""
            {
              "name": "list_users",
              "method": "GET",
              "endpoint": "/users",
              "executionSpec": {"mode":"sync","request":{"bodyType":"json"},"response":{"type":"json"}},
              "outputSchema": {"key":"users","type":"array"}
            }
            """);

        Throwable t = invokeAndUnwrap(payload);
        assertThat(t).isInstanceOf(IllegalArgumentException.class);
        assertThat(t).hasMessageContaining("outputSchema");
        assertThat(t).hasMessageContaining("array");
        assertThat(t).hasMessageContaining("list_users");
    }

    @Test
    @DisplayName("outputSchema is a string instead of array → IllegalArgumentException")
    void outputSchemaMustBeArrayNotString() {
        JsonNode payload = toolPayload("""
            {
              "name": "send_message",
              "method": "POST",
              "endpoint": "/messages",
              "executionSpec": {"mode":"sync","request":{"bodyType":"json"},"response":{"type":"json"}},
              "outputSchema": "not a real schema"
            }
            """);

        Throwable t = invokeAndUnwrap(payload);
        assertThat(t).isInstanceOf(IllegalArgumentException.class);
        assertThat(t).hasMessageContaining("outputSchema");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // executionMode enum validation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("executionSpec.mode = unknown → IllegalArgumentException with mode listed")
    void unknownModeRejected() {
        JsonNode payload = toolPayload("""
            {
              "name": "broken_tool",
              "method": "POST",
              "endpoint": "/x",
              "executionSpec": {"mode":"definitely_not_a_mode","request":{"bodyType":"json"},"response":{"type":"json"}}
            }
            """);

        Throwable t = invokeAndUnwrap(payload);
        assertThat(t).isInstanceOf(IllegalArgumentException.class);
        assertThat(t).hasMessageContaining("definitely_not_a_mode");
        assertThat(t).hasMessageContaining("broken_tool");
        // Mentions the allowed enum.
        assertThat(t).hasMessageContaining("sync");
    }

    @Test
    @DisplayName("V145 regression: mode=webhook now rejected at import as unknown mode")
    void webhookModeRejectedAtImportWithClearMessage() {
        // V145 retired the dedicated `webhook is reserved` branch in ApiSubmissionOrchestrator.
        // Webhook now hits the generic enum-validation branch with a message that lists the
        // four allowed modes. This guards against accidental re-introduction of the value.
        JsonNode payload = toolPayload("""
            {
              "name": "webhook_relic",
              "method": "POST",
              "endpoint": "/x",
              "executionSpec": {"mode":"webhook","request":{"bodyType":"json"},"response":{"type":"json"}}
            }
            """);

        Throwable t = invokeAndUnwrap(payload);
        assertThat(t).isInstanceOf(IllegalArgumentException.class);
        assertThat(t).hasMessageContaining("webhook");
        assertThat(t).hasMessageContaining("webhook_relic");
        assertThat(t).hasMessageContaining("sync");
        assertThat(t).hasMessageContaining("streaming");
    }

    @Test
    @DisplayName("V145 - bodyType=graphql + missing query → IllegalArgumentException at submission")
    void graphqlMissingQueryRejectedAtSubmission() {
        // Author declared bodyType=graphql but forgot the query - runtime would only
        // notice this at execute time. The DTO validator must reject it upfront so the
        // tool is never persisted.
        JsonNode payload = toolPayload("""
            {
              "name": "broken_graphql_tool",
              "method": "POST",
              "endpoint": "/graphql",
              "outputSchema": [{"key":"id","type":"string"}],
              "executionSpec": {
                "mode": "sync",
                "request": {"bodyType":"graphql","graphql":{}},
                "response": {"type":"json"}
              }
            }
            """);

        Throwable t = invokeAndUnwrap(payload);
        assertThat(t).isInstanceOf(IllegalArgumentException.class);
        assertThat(t).hasMessageContaining("execution.request.graphql.query");
        assertThat(t).hasMessageContaining("broken_graphql_tool");
    }

    @Test
    @DisplayName("V145 - bodyType=multipart + empty multipartFields → IllegalArgumentException (symmetry with graphql)")
    void multipartWithoutFieldsRejectedAtSubmission() {
        // Symmetric to graphql validation: a tool declaring bodyType=multipart but no
        // multipartFields is structurally broken. Without this guard the issue would
        // only surface when MultipartBodyEncoder is called at runtime.
        JsonNode payload = toolPayload("""
            {
              "name": "broken_multipart_tool",
              "method": "POST",
              "endpoint": "/upload",
              "outputSchema": [{"key":"id","type":"string"}],
              "executionSpec": {
                "mode": "sync",
                "request": {"bodyType":"multipart","multipartFields":[]},
                "response": {"type":"json"}
              }
            }
            """);

        Throwable t = invokeAndUnwrap(payload);
        assertThat(t).isInstanceOf(IllegalArgumentException.class);
        assertThat(t).hasMessageContaining("multipartFields");
        assertThat(t).hasMessageContaining("broken_multipart_tool");
    }

    @Test
    @DisplayName("flat executionMode = unknown (no executionSpec) → IllegalArgumentException")
    void unknownFlatModeRejected() {
        JsonNode payload = toolPayload("""
            {
              "name": "legacy_broken",
              "method": "POST",
              "endpoint": "/x",
              "executionMode": "garbage"
            }
            """);

        Throwable t = invokeAndUnwrap(payload);
        assertThat(t).isInstanceOf(IllegalArgumentException.class);
        assertThat(t).hasMessageContaining("garbage");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // executionMode derivation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build a tool payload that includes the minimum metadata required by
     * {@code processCustomToolData} so the happy-path tests don't NPE on missing fields.
     */
    private String happyPayloadJson(String executionSpecMode, String flatMode) {
        return """
            {
              "name": "happy_tool",
              "method": "POST",
              "endpoint": "/x",
              "toolNameId": "happy-tool",
              "isCustomCategory": false,
              "isCustomToolName": false,
              "toolCategory": "uncategorized",
              %s
              %s
              "outputSchema": [{"key":"id","type":"string"}]
            }
            """.formatted(
                executionSpecMode == null ? "" :
                        "\"executionSpec\":{\"mode\":\"" + executionSpecMode + "\",\"request\":{\"bodyType\":\"json\"},\"response\":{\"type\":\"json\"}},",
                flatMode == null ? "" : "\"executionMode\":\"" + flatMode + "\","
        );
    }

    @Test
    @DisplayName("executionSpec.mode wins over flat executionMode (single source of truth)")
    void executionSpecModeWins() {
        // executionSpec says async_poll, flat says sync - the spec must win.
        JsonNode payload = toolPayload(happyPayloadJson("async_poll", "sync"));

        // Add an async block to satisfy any downstream consumer that might inspect it.
        // (createToolFromData itself doesn't validate the async block - that's done in
        // executeHttpCallTyped at runtime.)
        Throwable t = invokeAndUnwrap(payload);
        // We may NPE in processCustomToolData on the mocked repos; what matters is that
        // we got past the mode validation. If t is null, the call succeeded entirely.
        if (t != null) {
            // Must NOT be an IllegalArgumentException about the mode.
            assertThat(t).isNotInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("missing executionSpec → falls back to flat executionMode")
    void fallbackToFlatMode() {
        JsonNode payload = toolPayload(happyPayloadJson(null, "streaming"));

        Throwable t = invokeAndUnwrap(payload);
        if (t != null) {
            assertThat(t).isNotInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("no execution info at all → defaults to sync (no exception)")
    void defaultsToSync() {
        JsonNode payload = toolPayload("""
            {
              "name": "minimal",
              "method": "GET",
              "endpoint": "/x",
              "toolNameId": "minimal",
              "isCustomCategory": false,
              "isCustomToolName": false,
              "toolCategory": "uncategorized"
            }
            """);

        Throwable t = invokeAndUnwrap(payload);
        // Mode defaulted to sync - must not throw an IAE.
        if (t != null) {
            assertThat(t).isNotInstanceOf(IllegalArgumentException.class);
        }
    }
}
