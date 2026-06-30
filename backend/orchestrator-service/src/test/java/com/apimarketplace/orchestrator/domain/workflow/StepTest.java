package com.apimarketplace.orchestrator.domain.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Step record.
 *
 * Step represents an MCP tool call or CRUD operation in the workflow.
 */
@DisplayName("Step")
class StepTest {

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorValidationTests {

        @Test
        @DisplayName("Should create step with all fields")
        void shouldCreateStepWithAllFields() {
            Map<String, Object> params = Map.of("url", "https://api.example.com");
            Step step = new Step("s1", "mcp", "Fetch Data", null, params, null, null, null);

            assertEquals("s1", step.id());
            assertEquals("mcp", step.type());
            assertEquals("Fetch Data", step.label());
            assertEquals(params, step.params());
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        @DisplayName("Should throw for null or blank label")
        void shouldThrowForNullOrBlankLabel(String label) {
            assertThrows(IllegalArgumentException.class,
                () -> new Step("s1", "mcp", label, null, Map.of(), null, null, null));
        }

        @Test
        @DisplayName("Should default type to 'mcp'")
        void shouldDefaultTypeToMcp() {
            Step step = new Step("s1", null, "Label", null, Map.of(), null, null, null);

            assertEquals("mcp", step.type());
        }

        @Test
        @DisplayName("Should default type to 'mcp' for blank type")
        void shouldDefaultTypeToMcpForBlank() {
            Step step = new Step("s1", "   ", "Label", null, Map.of(), null, null, null);

            assertEquals("mcp", step.type());
        }

        @Test
        @DisplayName("Should normalize type to lowercase")
        void shouldNormalizeTypeToLowercase() {
            Step step = new Step("s1", "MCP", "Label", null, Map.of(), null, null, null);

            assertEquals("mcp", step.type());
        }

        @Test
        @DisplayName("Should throw for invalid type")
        void shouldThrowForInvalidType() {
            assertThrows(IllegalArgumentException.class,
                () -> new Step("s1", "invalid", "Label", null, Map.of(), null, null, null));
        }

        @Test
        @DisplayName("Should normalize id to lowercase")
        void shouldNormalizeIdToLowercase() {
            Step step = new Step("STEP_1", "mcp", "Label", null, Map.of(), null, null, null);

            assertEquals("step_1", step.id());
        }

        @Test
        @DisplayName("Should make params map immutable")
        void shouldMakeParamsMapImmutable() {
            Step step = new Step("s1", "mcp", "Label", null, Map.of("key", "value"), null, null, null);

            assertThrows(UnsupportedOperationException.class,
                () -> step.params().put("new", "value"));
        }
    }

    @Nested
    @DisplayName("Step types")
    class StepTypesTests {

        @ParameterizedTest
        @ValueSource(strings = {"mcp", "crud-create-row", "crud-read-row", "crud-update-row", "crud-delete-row", "crud-create-column"})
        @DisplayName("Should accept valid step types")
        void shouldAcceptValidStepTypes(String type) {
            Step step = new Step("s1", type, "Label", null, Map.of(), null, null, null);

            assertEquals(type, step.type());
        }
    }

    @Nested
    @DisplayName("isCrudStep()")
    class IsCrudStepTests {

        @Test
        @DisplayName("Should return true for CRUD types")
        void shouldReturnTrueForCrudTypes() {
            assertTrue(new Step("s1", "crud-create-row", "Label", null, Map.of(), null, null, null).isCrudStep());
            assertTrue(new Step("s1", "crud-read-row", "Label", null, Map.of(), null, null, null).isCrudStep());
            assertTrue(new Step("s1", "crud-update-row", "Label", null, Map.of(), null, null, null).isCrudStep());
            assertTrue(new Step("s1", "crud-delete-row", "Label", null, Map.of(), null, null, null).isCrudStep());
            assertTrue(new Step("s1", "crud-create-column", "Label", null, Map.of(), null, null, null).isCrudStep());
        }

        @Test
        @DisplayName("Should return false for mcp type")
        void shouldReturnFalseForMcpType() {
            Step step = new Step("s1", "mcp", "Label", null, Map.of(), null, null, null);

            assertFalse(step.isCrudStep());
        }
    }

    @Nested
    @DisplayName("isFindStep()")
    class IsFindStepTests {

        @Test
        @DisplayName("Should return true for crud-find")
        void shouldReturnTrueForCrudFind() {
            Step step = new Step("s1", "crud-find", "Label", null, Map.of(), null, null, null);
            assertTrue(step.isFindStep());
        }

        @Test
        @DisplayName("Should return true for crud-read-row")
        void shouldReturnTrueForCrudReadRow() {
            Step step = new Step("s1", "crud-read-row", "Label", null, Map.of(), null, null, null);
            assertTrue(step.isFindStep());
        }

        @Test
        @DisplayName("Should return false for mcp type")
        void shouldReturnFalseForMcpType() {
            Step step = new Step("s1", "mcp", "Label", null, Map.of(), null, null, null);
            assertFalse(step.isFindStep());
        }

        @Test
        @DisplayName("Should return false for other CRUD types")
        void shouldReturnFalseForOtherCrudTypes() {
            assertFalse(new Step("s1", "crud-create-row", "Label", null, Map.of(), null, null, null).isFindStep());
            assertFalse(new Step("s1", "crud-update-row", "Label", null, Map.of(), null, null, null).isFindStep());
            assertFalse(new Step("s1", "crud-delete-row", "Label", null, Map.of(), null, null, null).isFindStep());
        }
    }

    @Nested
    @DisplayName("isMcpStep()")
    class IsMcpStepTests {

        @Test
        @DisplayName("Should return true for mcp type")
        void shouldReturnTrueForMcpType() {
            Step step = new Step("s1", "mcp", "Label", null, Map.of(), null, null, null);

            assertTrue(step.isMcpStep());
        }

        @Test
        @DisplayName("Should return false for CRUD types")
        void shouldReturnFalseForCrudTypes() {
            Step step = new Step("s1", "crud-create-row", "Label", null, Map.of(), null, null, null);

            assertFalse(step.isMcpStep());
        }
    }

    @Nested
    @DisplayName("getCrudOperation()")
    class GetCrudOperationTests {

        @ParameterizedTest
        @CsvSource({
            "crud-create-row, create-row",
            "crud-read-row, read-row",
            "crud-update-row, update-row",
            "crud-delete-row, delete-row",
            "crud-create-column, create-column"
        })
        @DisplayName("Should return CRUD operation without prefix")
        void shouldReturnCrudOperationWithoutPrefix(String type, String expectedOperation) {
            Step step = new Step("s1", type, "Label", null, Map.of(), null, null, null);

            assertEquals(expectedOperation, step.getCrudOperation());
        }

        @Test
        @DisplayName("Should return null for non-CRUD step")
        void shouldReturnNullForNonCrudStep() {
            Step step = new Step("s1", "mcp", "Label", null, Map.of(), null, null, null);

            assertNull(step.getCrudOperation());
        }
    }

    @Nested
    @DisplayName("normalizedLabel()")
    class NormalizedLabelTests {

        @ParameterizedTest
        @CsvSource({
            "Fetch Data, fetch_data",
            "API Call, api_call",
            "Process-Items, process_items"
        })
        @DisplayName("Should normalize label correctly")
        void shouldNormalizeLabelCorrectly(String label, String expected) {
            Step step = new Step("s1", "mcp", label, null, Map.of(), null, null, null);

            assertEquals(expected, step.normalizedLabel());
        }
    }

    @Nested
    @DisplayName("getNormalizedKey()")
    class GetNormalizedKeyTests {

        @ParameterizedTest
        @CsvSource({
            "Fetch Data, mcp:fetch_data",
            "API Call, mcp:api_call",
            "Send Email, mcp:send_email"
        })
        @DisplayName("Should return key with mcp: prefix for MCP steps")
        void shouldReturnKeyWithMcpPrefix(String label, String expectedKey) {
            Step step = new Step("s1", "mcp", label, null, Map.of(), null, null, null);

            assertEquals(expectedKey, step.getNormalizedKey());
        }

        @ParameterizedTest
        @CsvSource({
            "crud-create-row, Insert Users, table:insert_users",
            "crud-read-row, Get Users, table:get_users",
            "crud-update-row, Update Orders, table:update_orders",
            "crud-delete-row, Delete Items, table:delete_items",
            "crud-create-column, Add Column, table:add_column",
            "crud-find, Find Products, table:find_products"
        })
        @DisplayName("Should return key with table: prefix for CRUD steps")
        void shouldReturnKeyWithTablePrefix(String type, String label, String expectedKey) {
            Step step = new Step("s1", type, label, null, Map.of(), null, null, null);

            assertEquals(expectedKey, step.getNormalizedKey());
        }
    }

    @Nested
    @DisplayName("withParams()")
    class WithParamsTests {

        @Test
        @DisplayName("Should create new step with different params")
        void shouldCreateNewStepWithDifferentParams() {
            Step original = new Step("s1", "mcp", "Label", null, Map.of("key1", "value1"), null, null, null);
            Map<String, Object> newParams = Map.of("key2", "value2");

            Step modified = original.withParams(newParams);

            assertNotSame(original, modified);
            assertEquals(Map.of("key1", "value1"), original.params());
            assertEquals(Map.of("key2", "value2"), modified.params());
            assertEquals(original.id(), modified.id());
            assertEquals(original.label(), modified.label());
        }
    }

    @Nested
    @DisplayName("CrudConfig")
    class CrudConfigTests {

        @Test
        @DisplayName("Should create CrudConfig with all fields")
        void shouldCreateCrudConfigWithAllFields() {
            Step.CrudConfig.WhereCondition where = new Step.CrudConfig.WhereCondition("id", "=", 1);
            Step.CrudConfig config = new Step.CrudConfig(where, null, Map.of("name", "value"), null, null, 10, null);

            assertNotNull(config.where());
            assertEquals("id", config.where().column());
            assertEquals("=", config.where().operator());
            assertEquals(1, config.where().value());
            assertEquals(10, config.limit());
        }

        @Test
        @DisplayName("Should default null lists to empty")
        void shouldDefaultNullListsToEmpty() {
            Step.CrudConfig config = new Step.CrudConfig(null, null, null, null, null, null, null);

            assertNotNull(config.set());
            assertTrue(config.set().isEmpty());
            assertNotNull(config.rows());
            assertTrue(config.rows().isEmpty());
            assertNotNull(config.columns());
            assertTrue(config.columns().isEmpty());
        }
    }

    @Nested
    @DisplayName("credentialSource / platformCredentialId")
    class CredentialSourceTests {

        @Test
        @DisplayName("Back-compat 8-arg constructor defaults to USER credential source")
        void backCompatConstructorDefaultsToUser() {
            Step step = new Step("s1", "mcp", "L", null, Map.of(), null, null, null);
            assertEquals(CredentialSource.USER, step.credentialSource());
            assertNull(step.selectedCredentialId());
            assertNull(step.platformCredentialId());
            assertFalse(step.usesPlatformCredential());
        }

        @Test
        @DisplayName("Full constructor preserves workflow-selected user credential id")
        void fullConstructorPreservesSelectedUserCredentialId() {
            Step step = new Step("s1", "mcp", "L", null, Map.of(), null, null, null,
                    99L, CredentialSource.USER, null);
            assertEquals(CredentialSource.USER, step.credentialSource());
            assertEquals(99L, step.selectedCredentialId());
            assertNull(step.platformCredentialId());
        }

        @Test
        @DisplayName("Full constructor with PLATFORM source requires platformCredentialId")
        void platformSourceRequiresCredentialId() {
            Step step = new Step("s1", "mcp", "L", null, Map.of(), null, null, null,
                    CredentialSource.PLATFORM, 42L);
            assertEquals(CredentialSource.PLATFORM, step.credentialSource());
            assertEquals(42L, step.platformCredentialId());
            assertTrue(step.usesPlatformCredential());
        }

        @Test
        @DisplayName("PLATFORM source without platformCredentialId is rejected")
        void platformSourceWithoutIdRejected() {
            assertThrows(IllegalArgumentException.class, () ->
                    new Step("s1", "mcp", "L", null, Map.of(), null, null, null,
                            CredentialSource.PLATFORM, null));
        }

        @Test
        @DisplayName("Null credentialSource normalizes to USER")
        void nullSourceDefaultsToUser() {
            Step step = new Step("s1", "mcp", "L", null, Map.of(), null, null, null,
                    null, null);
            assertEquals(CredentialSource.USER, step.credentialSource());
        }

        @Test
        @DisplayName("withParams preserves selectedCredentialId, credentialSource and platformCredentialId")
        void withParamsPreservesCredentialFields() {
            Step original = new Step("s1", "mcp", "L", null, Map.of("a", "1"), null, null, null,
                    99L, CredentialSource.PLATFORM, 7L);
            Step updated = original.withParams(Map.of("b", "2"));
            assertEquals(99L, updated.selectedCredentialId());
            assertEquals(CredentialSource.PLATFORM, updated.credentialSource());
            assertEquals(7L, updated.platformCredentialId());
        }
    }
}
