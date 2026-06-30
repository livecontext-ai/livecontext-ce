package com.apimarketplace.orchestrator.tools.workflow.builder.validation;

import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.datasource.client.dto.DataSourceDto;
import com.apimarketplace.orchestrator.domain.NodeTypeDocumentationEntity;
import com.apimarketplace.orchestrator.tools.workflow.builder.ToolSchemaFetcher;
import com.apimarketplace.orchestrator.tools.workflow.builder.ToolSchemaFetcher.ToolInputSchema;
import com.apimarketplace.orchestrator.tools.workflow.builder.ToolSchemaFetcher.ToolParameter;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderValidator.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for StepValidator.
 * Validates step labels, tool IDs, required inputs, CRUD datasource references,
 * column references, and agent configuration.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StepValidator")
class StepValidatorTest {

    @Mock
    private ToolSchemaFetcher toolSchemaFetcher;

    @Mock
    private DataSourceClient dataSourceClient;

    @Mock
    private com.apimarketplace.orchestrator.service.NodeLibraryService nodeLibraryService;

    @Mock
    private WorkflowBuilderSession session;

    private StepValidator validator;

    @BeforeEach
    void setUp() {
        validator = new StepValidator(toolSchemaFetcher, dataSourceClient, nodeLibraryService);
    }

    private void stubBasicSession(List<Map<String, Object>> mcps) {
        when(session.getMcps()).thenReturn(mcps);
        lenient().when(session.getTriggers()).thenReturn(List.of());
        lenient().when(session.getNodeSchemas()).thenReturn(Map.of());
    }

    private void stubSessionWithTrigger(List<Map<String, Object>> mcps, List<Map<String, Object>> triggers) {
        when(session.getMcps()).thenReturn(mcps);
        when(session.getTriggers()).thenReturn(triggers);
        lenient().when(session.getNodeSchemas()).thenReturn(Map.of());
    }

    @Nested
    @DisplayName("Step count validation")
    class StepCountTests {

        @Test
        @DisplayName("Should add error when no steps exist")
        void shouldAddErrorWhenNoSteps() {
            stubBasicSession(List.of());

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e ->
                    e.code().equals("MISSING_MCPS"));
        }

        @Test
        @DisplayName("Should not add MISSING_MCPS when steps exist")
        void shouldNotAddMissingMcpsWhenStepsExist() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "My Step");
            step.put("id", "some-tool-id");
            step.put("params", null);

            stubBasicSession(List.of(step));
            lenient().when(toolSchemaFetcher.fetchToolInputSchema(anyString())).thenReturn(Optional.empty());

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                    e.code().equals("MISSING_MCPS"));
        }
    }

    @Nested
    @DisplayName("Label validation")
    class LabelTests {

        @Test
        @DisplayName("Should add error when step has no label")
        void shouldAddErrorWhenNoLabel() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", null);

            stubBasicSession(List.of(step));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e ->
                    e.code().equals("MISSING_LABEL"));
        }

        @Test
        @DisplayName("Should add error when step label is blank")
        void shouldAddErrorWhenLabelBlank() {
            stubBasicSession(List.of(Map.of("label", "   ")));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e ->
                    e.code().equals("MISSING_LABEL"));
        }

        @Test
        @DisplayName("Should skip further validation when label is missing")
        void shouldSkipFurtherValidationWhenLabelMissing() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", null);

            stubBasicSession(List.of(step));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            // Should only have MISSING_LABEL and MISSING_MCPS-related errors
            assertThat(result.getErrors()).noneMatch(e ->
                    e.code().equals("MISSING_TOOL_ID"));
        }
    }

    @Nested
    @DisplayName("Tool ID validation")
    class ToolIdTests {

        @Test
        @DisplayName("Should add error when non-agent step has no tool ID")
        void shouldAddErrorWhenNoToolId() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "My Step");
            step.put("id", null);
            step.put("toolId", null);
            step.put("params", null);

            stubBasicSession(List.of(step));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e ->
                    e.code().equals("MISSING_TOOL_ID"));
        }

        @Test
        @DisplayName("Should not error when step has id field")
        void shouldNotErrorWhenStepHasIdField() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "My Step");
            step.put("id", "some-tool-id");
            step.put("params", null);

            stubBasicSession(List.of(step));
            lenient().when(toolSchemaFetcher.fetchToolInputSchema("some-tool-id")).thenReturn(Optional.empty());

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                    e.code().equals("MISSING_TOOL_ID"));
        }

        @Test
        @DisplayName("Should not error when step has legacy toolId field")
        void shouldNotErrorWhenStepHasLegacyToolIdField() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "My Step");
            step.put("id", null);
            step.put("toolId", "legacy-tool-id");
            step.put("params", null);

            stubBasicSession(List.of(step));
            lenient().when(toolSchemaFetcher.fetchToolInputSchema("legacy-tool-id")).thenReturn(Optional.empty());

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                    e.code().equals("MISSING_TOOL_ID"));
        }

        @Test
        @DisplayName("Should not require tool ID for agent steps")
        void shouldNotRequireToolIdForAgentSteps() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "My Agent");
            step.put("isAgent", true);
            step.put("prompt", "Do something");
            step.put("params", null);

            stubBasicSession(List.of(step));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                    e.code().equals("MISSING_TOOL_ID"));
        }

        @Test
        @DisplayName("Should add TOOL_NOT_FOUND error when tool id does not exist in catalog")
        void shouldAddToolNotFoundErrorOnNonExistentToolId() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "Bogus");
            step.put("id", "Label_1"); // hallucinated placeholder
            step.put("params", null);

            stubBasicSession(List.of(step));
            lenient().when(toolSchemaFetcher.checkToolExists("Label_1"))
                    .thenReturn(ToolSchemaFetcher.ToolExistence.NOT_FOUND);
            lenient().when(toolSchemaFetcher.fetchToolInputSchema(anyString())).thenReturn(Optional.empty());

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e -> e.code().equals("TOOL_NOT_FOUND"));
        }

        @Test
        @DisplayName("Should NOT add TOOL_NOT_FOUND when catalog returns UNKNOWN (transient outage)")
        void shouldBePermissiveOnUnknown() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "Send Email");
            step.put("id", "550e8400-e29b-41d4-a716-446655440099");
            step.put("params", null);

            stubBasicSession(List.of(step));
            lenient().when(toolSchemaFetcher.checkToolExists(anyString()))
                    .thenReturn(ToolSchemaFetcher.ToolExistence.UNKNOWN);
            lenient().when(toolSchemaFetcher.fetchToolInputSchema(anyString())).thenReturn(Optional.empty());

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e -> e.code().equals("TOOL_NOT_FOUND"));
        }

        @Test
        @DisplayName("Should NOT check existence for reserved sentinels (__transform__/__wait__)")
        void shouldSkipCheckForSentinels() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "Transform");
            step.put("id", "__transform__");
            step.put("params", null);

            stubBasicSession(List.of(step));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e -> e.code().equals("TOOL_NOT_FOUND"));
            // Verify catalog was never consulted for sentinels
            verify(toolSchemaFetcher, never()).checkToolExists("__transform__");
        }

        @Test
        @DisplayName("Should NOT add TOOL_NOT_FOUND when catalog returns EXISTS")
        void shouldNotErrorOnExists() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "Send Email");
            step.put("id", "550e8400-e29b-41d4-a716-446655440099");
            step.put("params", null);

            stubBasicSession(List.of(step));
            lenient().when(toolSchemaFetcher.checkToolExists(anyString()))
                    .thenReturn(ToolSchemaFetcher.ToolExistence.EXISTS);
            lenient().when(toolSchemaFetcher.fetchToolInputSchema(anyString())).thenReturn(Optional.empty());

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e -> e.code().equals("TOOL_NOT_FOUND"));
        }

        @Test
        @DisplayName("Should NOT check catalog for CRUD pseudo-IDs (crud/...)")
        void shouldSkipCheckForCrudIds() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "Insert Row");
            step.put("id", "crud/users/insert");
            step.put("params", null);

            stubBasicSession(List.of(step));
            lenient().when(toolSchemaFetcher.fetchToolInputSchema(anyString())).thenReturn(Optional.empty());

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e -> e.code().equals("TOOL_NOT_FOUND"));
            verify(toolSchemaFetcher, never()).checkToolExists("crud/users/insert");
        }

        @Test
        @DisplayName("Should NOT check catalog for agent steps (isAgent=true)")
        void shouldSkipCheckForAgentSteps() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "My Agent");
            step.put("isAgent", true);
            step.put("id", "agent-config-uuid");
            step.put("prompt", "Do something");
            step.put("params", null);

            stubBasicSession(List.of(step));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e -> e.code().equals("TOOL_NOT_FOUND"));
            verify(toolSchemaFetcher, never()).checkToolExists("agent-config-uuid");
        }

        @Test
        @DisplayName("Should aggregate TOOL_NOT_FOUND errors when mixing valid and invalid mcps")
        void shouldAggregateMixedValidAndInvalid() {
            Map<String, Object> validStep = new HashMap<>();
            validStep.put("label", "Send Email");
            validStep.put("id", "550e8400-e29b-41d4-a716-446655440001");
            validStep.put("params", null);

            Map<String, Object> badStep = new HashMap<>();
            badStep.put("label", "Apply Label");
            badStep.put("id", "Label_99");
            badStep.put("params", null);

            stubBasicSession(List.of(validStep, badStep));
            lenient().when(toolSchemaFetcher.checkToolExists("550e8400-e29b-41d4-a716-446655440001"))
                    .thenReturn(ToolSchemaFetcher.ToolExistence.EXISTS);
            lenient().when(toolSchemaFetcher.checkToolExists("Label_99"))
                    .thenReturn(ToolSchemaFetcher.ToolExistence.NOT_FOUND);
            lenient().when(toolSchemaFetcher.fetchToolInputSchema(anyString())).thenReturn(Optional.empty());

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            // Exactly one TOOL_NOT_FOUND, naming the bad step
            long count = result.getErrors().stream()
                    .filter(e -> e.code().equals("TOOL_NOT_FOUND"))
                    .count();
            assertThat(count).isEqualTo(1);
            assertThat(result.getErrors()).anyMatch(e ->
                    e.code().equals("TOOL_NOT_FOUND") && e.message().contains("Apply Label"));
        }
    }

    @Nested
    @DisplayName("Required inputs validation")
    class RequiredInputsTests {

        @Test
        @DisplayName("Should add error when required params are missing")
        void shouldAddErrorWhenRequiredParamsMissing() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "API Call");
            step.put("id", "tool-123");
            step.put("params", Map.of("optionalField", "value"));

            stubBasicSession(List.of(step));

            ToolInputSchema schema = ToolInputSchema.builder()
                    .toolId("tool-123")
                    .requiredParameters(Map.of(
                            "url", new ToolParameter("url", "string", "The URL"),
                            "method", new ToolParameter("method", "string", "HTTP method")
                    ))
                    .optionalParameters(Map.of())
                    .build();
            when(toolSchemaFetcher.fetchToolInputSchema("tool-123")).thenReturn(Optional.of(schema));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e ->
                    e.code().equals("MISSING_REQUIRED_INPUTS") &&
                    e.message().contains("url"));
        }

        @Test
        @DisplayName("Should not error when all required params are provided")
        void shouldNotErrorWhenAllRequiredParamsProvided() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "API Call");
            step.put("id", "tool-123");
            step.put("params", Map.of("url", "https://example.com", "method", "GET"));

            stubBasicSession(List.of(step));

            ToolInputSchema schema = ToolInputSchema.builder()
                    .toolId("tool-123")
                    .requiredParameters(Map.of(
                            "url", new ToolParameter("url", "string", "The URL"),
                            "method", new ToolParameter("method", "string", "HTTP method")
                    ))
                    .optionalParameters(Map.of())
                    .build();
            when(toolSchemaFetcher.fetchToolInputSchema("tool-123")).thenReturn(Optional.of(schema));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                    e.code().equals("MISSING_REQUIRED_INPUTS"));
        }

        @Test
        @DisplayName("Should not error when schema has no required parameters")
        void shouldNotErrorWhenNoRequiredParams() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "API Call");
            step.put("id", "tool-123");
            step.put("params", null);

            stubBasicSession(List.of(step));

            ToolInputSchema schema = ToolInputSchema.builder()
                    .toolId("tool-123")
                    .requiredParameters(Map.of())
                    .optionalParameters(Map.of())
                    .build();
            when(toolSchemaFetcher.fetchToolInputSchema("tool-123")).thenReturn(Optional.of(schema));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                    e.code().equals("MISSING_REQUIRED_INPUTS"));
        }

        @Test
        @DisplayName("Should handle when tool schema fetch returns empty")
        void shouldHandleWhenToolSchemaFetchReturnsEmpty() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "API Call");
            step.put("id", "tool-123");
            step.put("params", null);

            stubBasicSession(List.of(step));
            when(toolSchemaFetcher.fetchToolInputSchema("tool-123")).thenReturn(Optional.empty());

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                    e.code().equals("MISSING_REQUIRED_INPUTS"));
        }

        @Test
        @DisplayName("Should handle null params gracefully")
        void shouldHandleNullParamsGracefully() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "API Call");
            step.put("id", "tool-123");
            step.put("params", null);

            stubBasicSession(List.of(step));

            ToolInputSchema schema = ToolInputSchema.builder()
                    .toolId("tool-123")
                    .requiredParameters(Map.of(
                            "url", new ToolParameter("url", "string", "The URL")
                    ))
                    .optionalParameters(Map.of())
                    .build();
            when(toolSchemaFetcher.fetchToolInputSchema("tool-123")).thenReturn(Optional.of(schema));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e ->
                    e.code().equals("MISSING_REQUIRED_INPUTS"));
        }
    }

    @Nested
    @DisplayName("CRUD datasource validation")
    class CrudDatasourceTests {

        @Test
        @DisplayName("Should add error when CRUD step has no datasource ID")
        void shouldAddErrorWhenCrudStepHasNoDatasourceId() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "Insert Row");
            step.put("id", "crud/insert");
            step.put("params", Map.of());

            stubBasicSession(List.of(step));
            lenient().when(toolSchemaFetcher.fetchToolInputSchema("crud/insert")).thenReturn(Optional.empty());

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e ->
                    e.code().equals("CRUD_MISSING_DATASOURCE"));
        }

        @Test
        @DisplayName("Should add error when CRUD datasource does not exist")
        void shouldAddErrorWhenCrudDatasourceDoesNotExist() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "Insert Row");
            step.put("id", "crud/insert");
            step.put("dataSourceId", 42L);
            step.put("params", Map.of());

            stubBasicSession(List.of(step));
            lenient().when(session.getTenantId()).thenReturn("tenant-1");
            lenient().when(toolSchemaFetcher.fetchToolInputSchema("crud/insert")).thenReturn(Optional.empty());
            when(dataSourceClient.findByIdAndTenantId(42L, "tenant-1")).thenReturn(null);

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e ->
                    e.code().equals("CRUD_INVALID_DATASOURCE"));
        }

        @Test
        @DisplayName("Should not error when CRUD datasource exists")
        void shouldNotErrorWhenCrudDatasourceExists() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "Insert Row");
            step.put("id", "crud/insert");
            step.put("dataSourceId", 42L);
            step.put("params", Map.of());

            stubBasicSession(List.of(step));
            lenient().when(session.getTenantId()).thenReturn("tenant-1");
            lenient().when(toolSchemaFetcher.fetchToolInputSchema("crud/insert")).thenReturn(Optional.empty());

            DataSourceDto ds = new DataSourceDto(42L, "tenant-1", "Users", null, null, null,
                    null, null, null, null, null, null, null, null, null, null);
            when(dataSourceClient.findByIdAndTenantId(42L, "tenant-1")).thenReturn(ds);

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                    e.code().equals("CRUD_INVALID_DATASOURCE") ||
                    e.code().equals("CRUD_MISSING_DATASOURCE"));
        }

        @Test
        @DisplayName("Should find datasource ID in params.dataSourceId")
        void shouldFindDatasourceIdInParams() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "Insert Row");
            step.put("id", "crud/insert");
            step.put("params", Map.of("dataSourceId", 42));

            stubBasicSession(List.of(step));
            lenient().when(session.getTenantId()).thenReturn("tenant-1");
            lenient().when(toolSchemaFetcher.fetchToolInputSchema("crud/insert")).thenReturn(Optional.empty());

            DataSourceDto ds = new DataSourceDto(42L, "tenant-1", "Users", null, null, null,
                    null, null, null, null, null, null, null, null, null, null);
            when(dataSourceClient.findByIdAndTenantId(42L, "tenant-1")).thenReturn(ds);

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                    e.code().equals("CRUD_MISSING_DATASOURCE"));
        }

        @Test
        @DisplayName("Should find datasource ID in params.table_id")
        void shouldFindDatasourceIdInParamsTableId() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "Insert Row");
            step.put("id", "crud/insert");
            step.put("params", Map.of("table_id", "42"));

            stubBasicSession(List.of(step));
            lenient().when(session.getTenantId()).thenReturn("tenant-1");
            lenient().when(toolSchemaFetcher.fetchToolInputSchema("crud/insert")).thenReturn(Optional.empty());

            DataSourceDto ds = new DataSourceDto(42L, "tenant-1", "Users", null, null, null,
                    null, null, null, null, null, null, null, null, null, null);
            when(dataSourceClient.findByIdAndTenantId(42L, "tenant-1")).thenReturn(ds);

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                    e.code().equals("CRUD_MISSING_DATASOURCE"));
        }

        @Test
        @DisplayName("Should find datasource ID in step.datasource_id")
        void shouldFindDatasourceIdInStepDatasourceId() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "Insert Row");
            step.put("id", "crud/insert");
            step.put("datasource_id", "42");
            step.put("params", Map.of());

            stubBasicSession(List.of(step));
            lenient().when(session.getTenantId()).thenReturn("tenant-1");
            lenient().when(toolSchemaFetcher.fetchToolInputSchema("crud/insert")).thenReturn(Optional.empty());

            DataSourceDto ds = new DataSourceDto(42L, "tenant-1", "Users", null, null, null,
                    null, null, null, null, null, null, null, null, null, null);
            when(dataSourceClient.findByIdAndTenantId(42L, "tenant-1")).thenReturn(ds);

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                    e.code().equals("CRUD_MISSING_DATASOURCE"));
        }

        @Test
        @DisplayName("Should not validate CRUD for non-crud tool IDs")
        void shouldNotValidateCrudForNonCrudToolIds() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "API Call");
            step.put("id", "some-uuid");
            step.put("params", Map.of());

            stubBasicSession(List.of(step));
            lenient().when(toolSchemaFetcher.fetchToolInputSchema("some-uuid")).thenReturn(Optional.empty());

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                    e.code().equals("CRUD_MISSING_DATASOURCE") ||
                    e.code().equals("CRUD_INVALID_DATASOURCE"));
        }
    }

    @Nested
    @DisplayName("Agent validation")
    class AgentTests {

        @Test
        @DisplayName("Should add warning when agent has no prompt or tools")
        void shouldAddWarningWhenAgentHasNoPromptOrTools() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "My Agent");
            step.put("isAgent", true);
            step.put("params", null);

            stubBasicSession(List.of(step));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getWarnings()).anyMatch(w ->
                    w.code().equals("AGENT_NO_CONFIG"));
        }

        @Test
        @DisplayName("Should not warn when agent has prompt")
        void shouldNotWarnWhenAgentHasPrompt() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "My Agent");
            step.put("isAgent", true);
            step.put("prompt", "Analyze the data");
            step.put("params", null);

            stubBasicSession(List.of(step));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getWarnings()).noneMatch(w ->
                    w.code().equals("AGENT_NO_CONFIG"));
        }

        @Test
        @DisplayName("Should not warn when agent has tools")
        void shouldNotWarnWhenAgentHasTools() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "My Agent");
            step.put("isAgent", true);
            step.put("tools", List.of("tool-1", "tool-2"));
            step.put("params", null);

            stubBasicSession(List.of(step));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getWarnings()).noneMatch(w ->
                    w.code().equals("AGENT_NO_CONFIG"));
        }
    }

    @Nested
    @DisplayName("Column reference validation")
    class ColumnReferenceTests {

        @Test
        @DisplayName("Should add error for simple column reference syntax")
        void shouldAddErrorForSimpleColumnReferenceSyntax() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "Process");
            step.put("id", "tool-1");
            step.put("params", Map.of("field", "{{username}}"));

            Map<String, Object> trigger = new HashMap<>();
            trigger.put("label", "Start");
            trigger.put("datasource_id", "10");

            stubSessionWithTrigger(List.of(step), List.of(trigger));
            lenient().when(toolSchemaFetcher.fetchToolInputSchema("tool-1")).thenReturn(Optional.empty());

            Map<String, com.apimarketplace.datasource.client.dto.ColumnMappingSpecDto> mappingSpec = new HashMap<>();
            mappingSpec.put("username", null);
            DataSourceDto ds = new DataSourceDto(10L, "t", "ds", null, null, null, null, null, null, null, null,
                    mappingSpec, null, null, null, null);
            lenient().when(dataSourceClient.getDataSource(10L, null)).thenReturn(ds);

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e ->
                    e.code().equals("INVALID_SYNTAX") &&
                    e.message().contains("username"));
        }

        @Test
        @DisplayName("Should not flag system variables as invalid syntax")
        void shouldNotFlagSystemVariablesAsInvalidSyntax() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "Process");
            step.put("id", "tool-1");
            step.put("params", Map.of("field", "{{current_item}}"));

            stubBasicSession(List.of(step));
            lenient().when(toolSchemaFetcher.fetchToolInputSchema("tool-1")).thenReturn(Optional.empty());

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                    e.code().equals("INVALID_SYNTAX"));
        }

        @Test
        @DisplayName("Should not flag prefixed references as invalid syntax")
        void shouldNotFlagPrefixedReferencesAsInvalidSyntax() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "Process");
            step.put("id", "tool-1");
            step.put("params", Map.of("field", "{{trigger:start.username}}"));

            stubBasicSession(List.of(step));
            lenient().when(toolSchemaFetcher.fetchToolInputSchema("tool-1")).thenReturn(Optional.empty());

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                    e.code().equals("INVALID_SYNTAX"));
        }

        @Test
        @DisplayName("Should skip column validation when no trigger schema available")
        void shouldSkipColumnValidationWhenNoTriggerSchema() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "Process");
            step.put("id", "tool-1");
            step.put("params", Map.of("field", "{{trigger:start.non_existent}}"));

            stubBasicSession(List.of(step));
            lenient().when(toolSchemaFetcher.fetchToolInputSchema("tool-1")).thenReturn(Optional.empty());

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            // No trigger = no columns to validate against
            assertThat(result.getWarnings()).noneMatch(w ->
                    w.code().equals("INVALID_COLUMN_REFERENCE"));
        }

        @Test
        @DisplayName("Should not validate non-string param values")
        void shouldNotValidateNonStringParamValues() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "Process");
            step.put("id", "tool-1");
            step.put("params", Map.of("count", 42));

            stubBasicSession(List.of(step));
            lenient().when(toolSchemaFetcher.fetchToolInputSchema("tool-1")).thenReturn(Optional.empty());

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                    e.code().equals("INVALID_SYNTAX") || e.code().equals("INVALID_COLUMN_REFERENCE"));
        }

        @Test
        @DisplayName("Should skip column validation when params are null")
        void shouldSkipColumnValidationWhenParamsNull() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "Process");
            step.put("id", "tool-1");
            step.put("params", null);

            stubBasicSession(List.of(step));
            lenient().when(toolSchemaFetcher.fetchToolInputSchema("tool-1")).thenReturn(Optional.empty());

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                    e.code().equals("INVALID_SYNTAX"));
        }

        @Test
        @DisplayName("Should read form field columns from live trigger params, not stale nodeSchemas")
        void formFieldColumnsReadFromLiveTriggerParams() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "send_sms");
            step.put("id", "tool-1");
            step.put("params", Map.of("AccountSid", "{{trigger:sms_form.output.account_sid}}"));

            Map<String, Object> trigger = new HashMap<>();
            trigger.put("label", "sms_form");
            trigger.put("type", "form");
            trigger.put("params", Map.of("fields", List.of(
                    Map.of("name", "to", "type", "text"),
                    Map.of("name", "from", "type", "text"),
                    Map.of("name", "body", "type", "textarea"),
                    Map.of("name", "account_sid", "type", "text")
            )));

            stubSessionWithTrigger(List.of(step), List.of(trigger));
            lenient().when(toolSchemaFetcher.fetchToolInputSchema("tool-1")).thenReturn(Optional.empty());

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getWarnings()).noneMatch(w ->
                    w.code().equals("INVALID_COLUMN_REFERENCE"));
        }

        @Test
        @DisplayName("Should not validate current_item references against trigger columns")
        void currentItemReferencesNotValidatedAgainstTriggerColumns() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "Process");
            step.put("id", "tool-1");
            step.put("params", Map.of("field", "{{current_item.data.some_field}}"));

            Map<String, Object> trigger = new HashMap<>();
            trigger.put("label", "Start");
            trigger.put("type", "form");
            trigger.put("params", Map.of("fields", List.of(
                    Map.of("name", "username", "type", "text")
            )));

            stubSessionWithTrigger(List.of(step), List.of(trigger));
            lenient().when(toolSchemaFetcher.fetchToolInputSchema("tool-1")).thenReturn(Optional.empty());

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getWarnings()).noneMatch(w ->
                    w.code().equals("INVALID_COLUMN_REFERENCE"));
        }

        @Test
        @DisplayName("Should not warn for valid webhook trigger column references")
        void webhookTriggerColumnsRecognized() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "Process");
            step.put("id", "tool-1");
            step.put("params", Map.of(
                    "body", "{{trigger:hook.output.payload}}",
                    "time", "{{trigger:hook.output.triggered_at}}"
            ));

            Map<String, Object> trigger = new HashMap<>();
            trigger.put("label", "hook");
            trigger.put("type", "webhook");

            stubSessionWithTrigger(List.of(step), List.of(trigger));
            lenient().when(toolSchemaFetcher.fetchToolInputSchema("tool-1")).thenReturn(Optional.empty());

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getWarnings()).noneMatch(w ->
                    w.code().equals("INVALID_COLUMN_REFERENCE"));
        }

        @Test
        @DisplayName("Should not warn for valid chat trigger column references")
        void chatTriggerColumnsRecognized() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "Process");
            step.put("id", "tool-1");
            step.put("params", Map.of(
                    "msg", "{{trigger:chat.output.message}}",
                    "type", "{{trigger:chat.output.match_type}}"
            ));

            Map<String, Object> trigger = new HashMap<>();
            trigger.put("label", "chat");
            trigger.put("type", "chat");

            stubSessionWithTrigger(List.of(step), List.of(trigger));
            lenient().when(toolSchemaFetcher.fetchToolInputSchema("tool-1")).thenReturn(Optional.empty());

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getWarnings()).noneMatch(w ->
                    w.code().equals("INVALID_COLUMN_REFERENCE"));
        }

        @Test
        @DisplayName("Should not validate SpEL function calls as column references")
        void spelFunctionCallsNotValidatedAsColumns() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "Process");
            step.put("id", "tool-1");
            step.put("params", Map.of("field", "{{json(trigger:form.output.data)}}"));

            Map<String, Object> trigger = new HashMap<>();
            trigger.put("label", "form");
            trigger.put("type", "form");
            trigger.put("params", Map.of("fields", List.of(
                    Map.of("name", "data", "type", "text")
            )));

            stubSessionWithTrigger(List.of(step), List.of(trigger));
            lenient().when(toolSchemaFetcher.fetchToolInputSchema("tool-1")).thenReturn(Optional.empty());

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getWarnings()).noneMatch(w ->
                    w.code().equals("INVALID_COLUMN_REFERENCE"));
        }
    }

    @Nested
    @DisplayName("CRUD required params - storage-path fallback")
    class CrudRequiredParamsTests {

        /**
         * Stub session so the table step lives in session.getTables(), which is the
         * only path that triggers validateCrudRequiredParams. Also stub the
         * nodeLibraryService so the schema comes back with a single required field.
         */
        private void stubTableSession(Map<String, Object> tableStep, String docType, Map<String, Object> schemaParams) {
            when(session.getMcps()).thenReturn(List.of());
            when(session.getTables()).thenReturn(List.of(tableStep));
            lenient().when(session.getTriggers()).thenReturn(List.of());
            lenient().when(session.getNodeSchemas()).thenReturn(Map.of());
            lenient().when(session.getTenantId()).thenReturn("t-1");

            // Make the datasource resolve OK so CRUD_MISSING_DATASOURCE doesn't fire.
            DataSourceDto ds = new DataSourceDto(42L, "t-1", "Users", null, null, null,
                    null, null, null, null, null, null, null, null, null, null);
            lenient().when(dataSourceClient.findByIdAndTenantId(42L, "t-1")).thenReturn(ds);

            NodeTypeDocumentationEntity doc = new NodeTypeDocumentationEntity();
            doc.setType(docType);
            doc.setParameters(schemaParams);
            when(nodeLibraryService.findByType(docType)).thenReturn(Optional.of(doc));
        }

        private Map<String, Object> insertRowSchema() {
            // Mirrors V11__seed_node_type_documentation.sql for insert_row
            return Map.of(
                "columns", Map.of("type", "object", "required", true, "description", "{column: value}"),
                "table_id", Map.of("type", "integer", "required", true)
            );
        }

        private Map<String, Object> baseInsertRowStep() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "Save Record");
            step.put("id", "crud/create-row");
            step.put("type", "crud-create-row");
            step.put("dataSourceId", 42L);
            return step;
        }

        @Test
        @DisplayName("Should pass when columns live at step.crud.columns (canonical add_node path)")
        void shouldPassWhenColumnsAtStepCrudDirect() {
            Map<String, Object> step = baseInsertRowStep();
            Map<String, Object> crud = new HashMap<>();
            crud.put("columns", Map.of("name", "foo"));
            step.put("crud", crud);

            stubTableSession(step, "insert_row", insertRowSchema());

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e -> e.code().equals("CRUD_MISSING_PARAM"));
        }

        @Test
        @DisplayName("Should pass when columns live at step.crud.rows[0].columns (WorkflowBuilderTableOperations wrapping)")
        void shouldPassWhenColumnsWrappedInCrudRows() {
            Map<String, Object> step = baseInsertRowStep();
            Map<String, Object> crud = new HashMap<>();
            crud.put("rows", List.of(Map.of("columns", Map.of("name", "foo"))));
            step.put("crud", crud);

            stubTableSession(step, "insert_row", insertRowSchema());

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e -> e.code().equals("CRUD_MISSING_PARAM"));
        }

        @Test
        @DisplayName("Should pass when columns live at step.columns (top-level, post-modify path)")
        void shouldPassWhenColumnsAtStepTopLevel() {
            Map<String, Object> step = baseInsertRowStep();
            step.put("columns", Map.of("name", "foo"));

            stubTableSession(step, "insert_row", insertRowSchema());

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e -> e.code().equals("CRUD_MISSING_PARAM"));
        }

        @Test
        @DisplayName("Should pass when columns live at step.params.columns (legacy / direct agent writes)")
        void shouldPassWhenColumnsAtStepParams() {
            Map<String, Object> step = baseInsertRowStep();
            Map<String, Object> params = new HashMap<>();
            params.put("columns", Map.of("name", "foo"));
            step.put("params", params);

            stubTableSession(step, "insert_row", insertRowSchema());

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e -> e.code().equals("CRUD_MISSING_PARAM"));
        }

        @Test
        @DisplayName("Should fail when columns are absent from ALL storage paths")
        void shouldFailWhenColumnsAbsentEverywhere() {
            Map<String, Object> step = baseInsertRowStep();
            // No columns anywhere - neither top-level, params, crud, nor crud.rows

            stubTableSession(step, "insert_row", insertRowSchema());

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e ->
                    e.code().equals("CRUD_MISSING_PARAM")
                    && e.message().contains("columns"));
        }

        @Test
        @DisplayName("Should pass update_row when set+where live at step.crud.*")
        void shouldPassUpdateRowWithSetAndWhereAtCrud() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "Update User");
            step.put("id", "crud/update-row");
            step.put("type", "crud-update-row");
            step.put("dataSourceId", 42L);
            Map<String, Object> crud = new HashMap<>();
            crud.put("set", Map.of("name", "bar"));
            crud.put("where", Map.of("column", "id", "operator", "==", "value", 1));
            step.put("crud", crud);

            Map<String, Object> schema = Map.of(
                "set", Map.of("type", "object", "required", true),
                "where", Map.of("type", "object", "required", true),
                "table_id", Map.of("type", "integer", "required", true)
            );
            stubTableSession(step, "update_row", schema);

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e -> e.code().equals("CRUD_MISSING_PARAM"));
        }

        @Test
        @DisplayName("Should fail update_row when 'set' missing from all paths even if 'where' present")
        void shouldFailUpdateRowWhenSetMissing() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "Update User");
            step.put("id", "crud/update-row");
            step.put("type", "crud-update-row");
            step.put("dataSourceId", 42L);
            Map<String, Object> crud = new HashMap<>();
            crud.put("where", Map.of("column", "id", "operator", "==", "value", 1));
            // 'set' is missing entirely
            step.put("crud", crud);

            Map<String, Object> schema = Map.of(
                "set", Map.of("type", "object", "required", true),
                "where", Map.of("type", "object", "required", true),
                "table_id", Map.of("type", "integer", "required", true)
            );
            stubTableSession(step, "update_row", schema);

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            long missing = result.getErrors().stream()
                    .filter(e -> e.code().equals("CRUD_MISSING_PARAM") && e.message().contains("set"))
                    .count();
            assertThat(missing).isEqualTo(1);
        }

        @Test
        @DisplayName("Should skip table_id in required-param check (delegated to CRUD_MISSING_DATASOURCE)")
        void shouldSkipTableIdInRequiredParamCheck() {
            Map<String, Object> step = baseInsertRowStep();
            step.remove("dataSourceId"); // Force missing datasource
            step.put("columns", Map.of("name", "foo"));

            stubTableSession(step, "insert_row", insertRowSchema());

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            // No CRUD_MISSING_PARAM for table_id - that's covered by CRUD_MISSING_DATASOURCE
            assertThat(result.getErrors()).noneMatch(e ->
                    e.code().equals("CRUD_MISSING_PARAM") && e.message().contains("table_id"));
            assertThat(result.getErrors()).anyMatch(e -> e.code().equals("CRUD_MISSING_DATASOURCE"));
        }
    }

    @Nested
    @DisplayName("Node ID generation")
    class NodeIdTests {

        @Test
        @DisplayName("Should use mcp prefix for non-agent steps")
        void shouldUseMcpPrefixForNonAgentSteps() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "My Step");
            step.put("id", null);
            step.put("params", null);

            stubBasicSession(List.of(step));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e ->
                    e.nodeId().equals("mcp:my_step"));
        }

        @Test
        @DisplayName("Should use agent prefix for agent steps")
        void shouldUseAgentPrefixForAgentSteps() {
            Map<String, Object> step = new HashMap<>();
            step.put("label", "My Agent");
            step.put("isAgent", true);
            step.put("params", null);

            stubBasicSession(List.of(step));

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getWarnings()).anyMatch(w ->
                    w.nodeId().equals("agent:my_agent"));
        }
    }

    @Test
    @DisplayName("Should handle multiple steps with mixed issues")
    void shouldHandleMultipleStepsWithMixedIssues() {
        Map<String, Object> stepOk = new HashMap<>();
        stepOk.put("label", "Good Step");
        stepOk.put("id", "tool-1");
        stepOk.put("params", Map.of());

        Map<String, Object> stepNoLabel = new HashMap<>();
        stepNoLabel.put("label", null);

        Map<String, Object> stepNoTool = new HashMap<>();
        stepNoTool.put("label", "No Tool Step");
        stepNoTool.put("id", null);
        stepNoTool.put("toolId", null);
        stepNoTool.put("params", null);

        stubBasicSession(List.of(stepOk, stepNoLabel, stepNoTool));
        lenient().when(toolSchemaFetcher.fetchToolInputSchema("tool-1")).thenReturn(Optional.empty());

        ValidationResult result = ValidationResult.builder().build();
        validator.validate(session, result);

        assertThat(result.getErrors()).anyMatch(e -> e.code().equals("MISSING_LABEL"));
        assertThat(result.getErrors()).anyMatch(e -> e.code().equals("MISSING_TOOL_ID"));
    }
}
