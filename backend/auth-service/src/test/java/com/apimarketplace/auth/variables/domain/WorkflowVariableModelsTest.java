package com.apimarketplace.auth.variables.domain;

import com.apimarketplace.auth.variables.domain.WorkflowVariableModels.ValueType;
import com.apimarketplace.auth.variables.domain.WorkflowVariableModels.VariableResponse;
import com.apimarketplace.auth.variables.domain.WorkflowVariableModels.WorkflowVariable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure domain-model behavior: the {@link ValueType#fromValue} parsing contract
 * (null/blank default, case-insensitive match, null for garbage) and the
 * {@link VariableResponse#from} mapping, in particular the scope string the
 * frontend switches on ("workspace" vs "personal").
 */
@DisplayName("WorkflowVariableModels")
class WorkflowVariableModelsTest {

    @Nested
    @DisplayName("ValueType.fromValue")
    class ValueTypeFromValueTests {

        @Test
        @DisplayName("null defaults to STRING - an omitted 'type' in the upsert payload means plain text")
        void nullDefaultsToString() {
            assertThat(ValueType.fromValue(null)).isEqualTo(ValueType.STRING);
        }

        @ParameterizedTest(name = "blank input \"{0}\" defaults to STRING")
        @ValueSource(strings = {"", "   ", "\t"})
        @DisplayName("blank defaults to STRING")
        void blankDefaultsToString(String blank) {
            assertThat(ValueType.fromValue(blank)).isEqualTo(ValueType.STRING);
        }

        @Test
        @DisplayName("matches each enum constant case-insensitively")
        void matchesCaseInsensitively() {
            assertThat(ValueType.fromValue("string")).isEqualTo(ValueType.STRING);
            assertThat(ValueType.fromValue("Number")).isEqualTo(ValueType.NUMBER);
            assertThat(ValueType.fromValue("BOOLEAN")).isEqualTo(ValueType.BOOLEAN);
            assertThat(ValueType.fromValue("jSoN")).isEqualTo(ValueType.JSON);
        }

        @Test
        @DisplayName("returns null for an unknown type token so the caller can reject it explicitly")
        void unknownReturnsNull() {
            assertThat(ValueType.fromValue("INTEGER")).isNull();
            assertThat(ValueType.fromValue("bool")).isNull();
            assertThat(ValueType.fromValue("STRING ")).as("no trimming - a padded token is unknown").isNull();
        }
    }

    @Nested
    @DisplayName("VariableResponse.from")
    class VariableResponseFromTests {

        private final Instant created = Instant.parse("2026-07-01T10:00:00Z");
        private final Instant updated = Instant.parse("2026-07-02T11:30:00Z");

        private WorkflowVariable variable(String organizationId) {
            return new WorkflowVariable(
                    42L, "tenant-a", organizationId, "api_url", "https://api.example.com",
                    ValueType.STRING, false, "Base API URL", "tenant-a", created, updated);
        }

        @Test
        @DisplayName("maps an org-tagged variable to scope 'workspace'")
        void orgRowMapsToWorkspaceScope() {
            VariableResponse response = VariableResponse.from(variable("org-1"));

            assertThat(response.scope()).isEqualTo("workspace");
        }

        @Test
        @DisplayName("maps a personal variable (org null) to scope 'personal'")
        void personalRowMapsToPersonalScope() {
            VariableResponse response = VariableResponse.from(variable(null));

            assertThat(response.scope()).isEqualTo("personal");
        }

        @Test
        @DisplayName("copies id, name, value, type name, description and timestamps verbatim")
        void copiesFieldsVerbatim() {
            WorkflowVariable source = new WorkflowVariable(
                    7L, "tenant-a", null, "retries", "3",
                    ValueType.NUMBER, false, "Retry count", "tenant-a", created, updated);

            VariableResponse response = VariableResponse.from(source);

            assertThat(response.id()).isEqualTo(7L);
            assertThat(response.name()).isEqualTo("retries");
            assertThat(response.value()).isEqualTo("3");
            assertThat(response.type()).isEqualTo("NUMBER");
            assertThat(response.description()).isEqualTo("Retry count");
            assertThat(response.createdAt()).isEqualTo(created);
            assertThat(response.updatedAt()).isEqualTo(updated);
        }

        @Test
        @DisplayName("does not expose tenantId or organizationId - the response record is an explicit allowlist")
        void responseShapeHasNoScopeInternals() {
            // The record component list IS the wire contract: verify it stays the
            // explicit allowlist (id, name, value, type, description, scope, secret, timestamps).
            assertThat(VariableResponse.class.getRecordComponents())
                    .extracting(java.lang.reflect.RecordComponent::getName)
                    .containsExactly("id", "name", "value", "type", "description",
                            "scope", "secret", "createdAt", "updatedAt");
        }

        @Test
        @DisplayName("masks the value to null and reports secret=true for a secret variable")
        void secretVariableMasksValue() {
            WorkflowVariable source = new WorkflowVariable(
                    9L, "tenant-a", null, "api_key", "sk-live-SUPERSECRET",
                    ValueType.STRING, true, "prod key", "tenant-a", created, updated);

            VariableResponse response = VariableResponse.from(source);

            assertThat(response.secret()).isTrue();
            assertThat(response.value())
                    .as("a secret value must never leave the server through a listing")
                    .isNull();
            // The rest of the metadata stays readable.
            assertThat(response.name()).isEqualTo("api_key");
            assertThat(response.type()).isEqualTo("STRING");
            assertThat(response.description()).isEqualTo("prod key");
        }

        @Test
        @DisplayName("passes the value through verbatim and reports secret=false for a plain variable")
        void plainVariablePassesValueThrough() {
            VariableResponse response = VariableResponse.from(variable(null));

            assertThat(response.secret()).isFalse();
            assertThat(response.value()).isEqualTo("https://api.example.com");
        }
    }
}
