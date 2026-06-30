package com.apimarketplace.catalog.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for WorkflowToolCredentialDTO record.
 *
 * WorkflowToolCredentialDTO is optimized for workflow inspector credential display.
 */
@DisplayName("WorkflowToolCredentialDTO")
class WorkflowToolCredentialDTOTest {

    // ========================================================================
    // Record construction tests
    // ========================================================================

    @Nested
    @DisplayName("Record construction")
    class RecordConstructionTests {

        @Test
        @DisplayName("should create record with all fields")
        void shouldCreateRecordWithAllFields() {
            WorkflowToolCredentialDTO dto = new WorkflowToolCredentialDTO(
                    "github-api-key",
                    true,
                    "authentication",
                    "always",
                    "{\"scope\": \"repo\"}",
                    "GitHub API Key",
                    "API key for GitHub access",
                    "api_key",
                    "bearer",
                    "/api/test",
                    "https://docs.github.com",
                    "https://github.githubassets.com/favicon.ico",
                    "{\"key\": \"value\"}",
                    "base-credential"
            );

            assertEquals("github-api-key", dto.credentialName());
            assertTrue(dto.isRequired());
            assertEquals("authentication", dto.usage());
            assertEquals("always", dto.condition());
            assertEquals("{\"scope\": \"repo\"}", dto.metadata());
            assertEquals("GitHub API Key", dto.displayName());
            assertEquals("API key for GitHub access", dto.description());
            assertEquals("api_key", dto.credentialType());
            assertEquals("bearer", dto.authType());
            assertEquals("/api/test", dto.testEndpoint());
            assertEquals("https://docs.github.com", dto.documentationUrl());
            assertEquals("https://github.githubassets.com/favicon.ico", dto.iconUrl());
            assertEquals("{\"key\": \"value\"}", dto.properties());
            assertEquals("base-credential", dto.extends_());
        }

        @Test
        @DisplayName("should create minimal credential")
        void shouldCreateMinimalCredential() {
            WorkflowToolCredentialDTO dto = new WorkflowToolCredentialDTO(
                    "simple-key",
                    false,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );

            assertEquals("simple-key", dto.credentialName());
            assertFalse(dto.isRequired());
            assertNull(dto.description());
        }

        @Test
        @DisplayName("should allow all null fields")
        void shouldAllowAllNullFields() {
            WorkflowToolCredentialDTO dto = new WorkflowToolCredentialDTO(
                    null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null
            );

            assertNull(dto.credentialName());
            assertNull(dto.isRequired());
        }
    }

    // ========================================================================
    // Credential type tests
    // ========================================================================

    @Nested
    @DisplayName("Credential types")
    class CredentialTypeTests {

        @Test
        @DisplayName("should support API key type")
        void shouldSupportApiKeyType() {
            WorkflowToolCredentialDTO dto = new WorkflowToolCredentialDTO(
                    "api-key", true, "auth", null, null,
                    "API Key", null, "api_key", "header",
                    null, null, null, null, null
            );

            assertEquals("api_key", dto.credentialType());
        }

        @Test
        @DisplayName("should support OAuth type")
        void shouldSupportOAuthType() {
            WorkflowToolCredentialDTO dto = new WorkflowToolCredentialDTO(
                    "oauth-token", true, "auth", null, null,
                    "OAuth Token", null, "oauth2", "bearer",
                    null, null, null, null, null
            );

            assertEquals("oauth2", dto.credentialType());
            assertEquals("bearer", dto.authType());
        }

        @Test
        @DisplayName("should support basic auth type")
        void shouldSupportBasicAuthType() {
            WorkflowToolCredentialDTO dto = new WorkflowToolCredentialDTO(
                    "basic-auth", true, "auth", null, null,
                    "Basic Auth", null, "basic", "basic",
                    null, null, null, null, null
            );

            assertEquals("basic", dto.credentialType());
        }
    }

    // ========================================================================
    // Equality tests
    // ========================================================================

    @Nested
    @DisplayName("Equality")
    class EqualityTests {

        @Test
        @DisplayName("should be equal for same values")
        void shouldBeEqualForSameValues() {
            WorkflowToolCredentialDTO dto1 = new WorkflowToolCredentialDTO(
                    "key", true, "auth", null, null, "Key", null,
                    "api_key", "header", null, null, null, null, null
            );
            WorkflowToolCredentialDTO dto2 = new WorkflowToolCredentialDTO(
                    "key", true, "auth", null, null, "Key", null,
                    "api_key", "header", null, null, null, null, null
            );

            assertEquals(dto1, dto2);
            assertEquals(dto1.hashCode(), dto2.hashCode());
        }

        @Test
        @DisplayName("should not be equal for different values")
        void shouldNotBeEqualForDifferentValues() {
            WorkflowToolCredentialDTO dto1 = new WorkflowToolCredentialDTO(
                    "key1", true, "auth", null, null, null, null,
                    null, null, null, null, null, null, null
            );
            WorkflowToolCredentialDTO dto2 = new WorkflowToolCredentialDTO(
                    "key2", true, "auth", null, null, null, null,
                    null, null, null, null, null, null, null
            );

            assertNotEquals(dto1, dto2);
        }
    }
}
