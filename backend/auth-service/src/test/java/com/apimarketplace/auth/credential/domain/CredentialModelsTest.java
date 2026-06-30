package com.apimarketplace.auth.credential.domain;

import com.apimarketplace.auth.credential.domain.CredentialModels.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CredentialModels Tests")
class CredentialModelsTest {

    // ========== CredentialType Tests ==========

    @Nested
    @DisplayName("CredentialType.fromString")
    class CredentialTypeFromStringTests {

        @Test
        @DisplayName("should return OAuth2 for 'OAuth2'")
        void fromString_oauth2() {
            assertThat(CredentialType.fromString("OAuth2")).isEqualTo(CredentialType.OAuth2);
        }

        @Test
        @DisplayName("should return API_Key for 'API_Key'")
        void fromString_apiKey() {
            assertThat(CredentialType.fromString("API_Key")).isEqualTo(CredentialType.API_Key);
        }

        @Test
        @DisplayName("should return Basic_Auth for 'Basic_Auth'")
        void fromString_basicAuth() {
            assertThat(CredentialType.fromString("Basic_Auth")).isEqualTo(CredentialType.Basic_Auth);
        }

        @Test
        @DisplayName("should return Webhook for 'Webhook'")
        void fromString_webhook() {
            assertThat(CredentialType.fromString("Webhook")).isEqualTo(CredentialType.Webhook);
        }

        @Test
        @DisplayName("should return OAuth2 for null")
        void fromString_null() {
            assertThat(CredentialType.fromString(null)).isEqualTo(CredentialType.OAuth2);
        }

        @Test
        @DisplayName("should handle space-separated names by replacing spaces with underscores")
        void fromString_withSpaces() {
            assertThat(CredentialType.fromString("API Key")).isEqualTo(CredentialType.API_Key);
            assertThat(CredentialType.fromString("Basic Auth")).isEqualTo(CredentialType.Basic_Auth);
        }

        @Test
        @DisplayName("should match by display name for unknown enum names")
        void fromString_byDisplayName() {
            // Display names are "OAuth2", "API Key", "Basic Auth", "Webhook"
            assertThat(CredentialType.fromString("API Key")).isEqualTo(CredentialType.API_Key);
            assertThat(CredentialType.fromString("Basic Auth")).isEqualTo(CredentialType.Basic_Auth);
        }

        @Test
        @DisplayName("should return OAuth2 as default for completely unknown value")
        void fromString_unknownDefault() {
            assertThat(CredentialType.fromString("something_random")).isEqualTo(CredentialType.OAuth2);
        }

        @Test
        @DisplayName("should return correct display names")
        void getDisplayName() {
            assertThat(CredentialType.OAuth2.getDisplayName()).isEqualTo("OAuth2");
            assertThat(CredentialType.API_Key.getDisplayName()).isEqualTo("API Key");
            assertThat(CredentialType.Basic_Auth.getDisplayName()).isEqualTo("Basic Auth");
            assertThat(CredentialType.Webhook.getDisplayName()).isEqualTo("Webhook");
        }
    }

    // ========== Credential Record Tests ==========

    @Nested
    @DisplayName("Credential record")
    class CredentialRecordTests {

        @Test
        @DisplayName("withId should return new credential with updated id preserving all other fields")
        void withId_returnsNewInstance() {
            Credential original = buildCredential(1L, "user1", "My Cred", true);
            Credential updated = original.withId(42L);

            assertThat(updated.id()).isEqualTo(42L);
            // Verify all other fields are preserved
            assertThat(updated.tenantId()).isEqualTo(original.tenantId());
            assertThat(updated.name()).isEqualTo(original.name());
            assertThat(updated.integration()).isEqualTo(original.integration());
            assertThat(updated.type()).isEqualTo(original.type());
            assertThat(updated.environment()).isEqualTo(original.environment());
            assertThat(updated.status()).isEqualTo(original.status());
            assertThat(updated.description()).isEqualTo(original.description());
            assertThat(updated.credentialData()).isEqualTo(original.credentialData());
            assertThat(updated.scopes()).isEqualTo(original.scopes());
            assertThat(updated.tags()).isEqualTo(original.tags());
            assertThat(updated.owner()).isEqualTo(original.owner());
            assertThat(updated.iconUrl()).isEqualTo(original.iconUrl());
            assertThat(updated.isDefault()).isEqualTo(original.isDefault());
            assertThat(updated.lastUsed()).isEqualTo(original.lastUsed());
            assertThat(updated.createdAt()).isEqualTo(original.createdAt());
            assertThat(updated.updatedAt()).isEqualTo(original.updatedAt());
        }

        @Test
        @DisplayName("withId should not modify original instance (immutability)")
        void withId_immutability() {
            Credential original = buildCredential(1L, "user1", "My Cred", true);
            original.withId(42L);

            assertThat(original.id()).isEqualTo(1L);
        }

        @Test
        @DisplayName("withIsDefault should return new credential with updated isDefault")
        void withIsDefault_returnsNewInstance() {
            Credential original = buildCredential(1L, "user1", "My Cred", true);
            Credential updated = original.withIsDefault(false);

            assertThat(updated.isDefault()).isFalse();
            assertThat(updated.id()).isEqualTo(original.id());
            assertThat(updated.name()).isEqualTo(original.name());
            assertThat(updated.tenantId()).isEqualTo(original.tenantId());
        }

        @Test
        @DisplayName("withIsDefault should not modify original instance (immutability)")
        void withIsDefault_immutability() {
            Credential original = buildCredential(1L, "user1", "My Cred", true);
            original.withIsDefault(false);

            assertThat(original.isDefault()).isTrue();
        }

        @Test
        @DisplayName("withIsDefault should set to true")
        void withIsDefault_setTrue() {
            Credential original = buildCredential(1L, "user1", "My Cred", false);
            Credential updated = original.withIsDefault(true);

            assertThat(updated.isDefault()).isTrue();
        }
    }

    // ========== withoutSecrets allowlist Tests ==========

    @Nested
    @DisplayName("Credential.withoutSecrets - diagnostic allowlist")
    class WithoutSecretsAllowlistTests {

        @Test
        @DisplayName("Strips access_token / refresh_token / oauth_client_secret from credential_data")
        void stripsTokensAndOAuthClientSecret() {
            Credential original = buildCredentialWithData(Map.of(
                    "access_token", "AT-123",
                    "refresh_token", "RT-456",
                    "oauth_client_secret", "shh"
            ));

            Credential safe = original.withoutSecrets();

            assertThat(safe.credentialData()).doesNotContainKeys(
                    "access_token", "refresh_token", "oauth_client_secret");
        }

        @Test
        @DisplayName("Retains byok_revoke_reason and byok_revoked_at after BYOK cascade - frontend needs them to explain Reconnect")
        void retainsByokCascadeDiagnostics() {
            Credential original = buildCredentialWithData(Map.of(
                    "access_token", "AT-123",
                    "byok_revoke_reason", "platform_credential_deleted",
                    "byok_revoked_at", "2026-05-06T12:00:00Z"
            ));

            Credential safe = original.withoutSecrets();

            assertThat(safe.credentialData())
                    .containsEntry("byok_revoke_reason", "platform_credential_deleted")
                    .containsEntry("byok_revoked_at", "2026-05-06T12:00:00Z")
                    .doesNotContainKey("access_token");
        }

        @Test
        @DisplayName("Retains refresh_error_* diagnostics written by OAuth2Service.releaseTerminal")
        void retainsRefreshErrorDiagnostics() {
            Credential original = buildCredentialWithData(Map.of(
                    "access_token", "AT-123",
                    "refresh_error_reason", "terminal_user",
                    "refresh_error_provider_code", "invalid_grant",
                    "refresh_error_http_status", 400,
                    "refresh_error_at", "2026-05-06T12:00:00Z"
            ));

            Credential safe = original.withoutSecrets();

            assertThat(safe.credentialData())
                    .containsEntry("refresh_error_reason", "terminal_user")
                    .containsEntry("refresh_error_provider_code", "invalid_grant")
                    .containsEntry("refresh_error_http_status", 400)
                    .containsEntry("refresh_error_at", "2026-05-06T12:00:00Z")
                    .doesNotContainKey("access_token");
        }

        @Test
        @DisplayName("Drops every key not on PUBLIC_DIAGNOSTIC_KEYS (default-deny posture)")
        void defaultDenyForUnknownKeys() {
            Credential original = buildCredentialWithData(Map.of(
                    "access_token", "AT-123",
                    "internal_audit_id", "audit-xyz",
                    "experimental_field", "something"
            ));

            Credential safe = original.withoutSecrets();

            assertThat(safe.credentialData())
                    .doesNotContainKeys("access_token", "internal_audit_id", "experimental_field");
        }

        @Test
        @DisplayName("Returns Collections.emptyMap() when input data is null")
        void emptyMapWhenInputNull() {
            Credential original = buildCredentialWithData(null);

            Credential safe = original.withoutSecrets();

            assertThat(safe.credentialData()).isEmpty();
        }

        @Test
        @DisplayName("Returns empty map when input has only sensitive keys (no diagnostic keys present)")
        void emptyMapWhenOnlySecrets() {
            Credential original = buildCredentialWithData(Map.of(
                    "access_token", "AT-123",
                    "refresh_token", "RT-456"
            ));

            Credential safe = original.withoutSecrets();

            assertThat(safe.credentialData()).isEmpty();
        }

        @Test
        @DisplayName("Skips null-valued diagnostic keys so emptied diagnostics don't appear as null fields in API JSON")
        void skipsNullValuedDiagnostics() {
            java.util.HashMap<String, Object> data = new java.util.HashMap<>();
            data.put("byok_revoke_reason", null);
            data.put("refresh_error_reason", "terminal_user");

            Credential original = buildCredentialWithData(data);

            Credential safe = original.withoutSecrets();

            assertThat(safe.credentialData())
                    .doesNotContainKey("byok_revoke_reason")
                    .containsEntry("refresh_error_reason", "terminal_user");
        }

        @Test
        @DisplayName("PUBLIC_DIAGNOSTIC_KEYS does not include any token / secret / api_key - guard against accidental leakage")
        void allowlistContainsNoSecrets() {
            assertThat(Credential.PUBLIC_DIAGNOSTIC_KEYS)
                    .noneMatch(k -> k.contains("token"))
                    .noneMatch(k -> k.contains("secret"))
                    .noneMatch(k -> k.toLowerCase().contains("password"))
                    .noneMatch(k -> k.toLowerCase().contains("api_key"));
        }
    }

    // ========== CredentialEnvironment Tests ==========

    @Nested
    @DisplayName("CredentialEnvironment")
    class CredentialEnvironmentTests {

        @Test
        @DisplayName("should have Production and Sandbox values")
        void enumValues() {
            assertThat(CredentialEnvironment.values()).containsExactly(
                    CredentialEnvironment.Production,
                    CredentialEnvironment.Sandbox
            );
        }
    }

    // ========== CredentialStatus Tests ==========

    @Nested
    @DisplayName("CredentialStatus")
    class CredentialStatusTests {

        @Test
        @DisplayName("should expose active, expiring, error, needs_reauth")
        void enumValues() {
            assertThat(CredentialStatus.values()).containsExactly(
                    CredentialStatus.active,
                    CredentialStatus.expiring,
                    CredentialStatus.error,
                    CredentialStatus.needs_reauth
            );
        }
    }

    // ========== PaginatedCredentialsResponse Tests ==========

    @Nested
    @DisplayName("PaginatedCredentialsResponse")
    class PaginatedCredentialsResponseTests {

        @Test
        @DisplayName("should correctly store pagination metadata")
        void paginationFields() {
            List<Credential> creds = List.of(
                    buildCredential(1L, "user1", "Cred1", true),
                    buildCredential(2L, "user1", "Cred2", false)
            );

            PaginatedCredentialsResponse response = new PaginatedCredentialsResponse(
                    creds, 1, 10, 25, 3, true, false
            );

            assertThat(response.credentials()).hasSize(2);
            assertThat(response.page()).isEqualTo(1);
            assertThat(response.pageSize()).isEqualTo(10);
            assertThat(response.totalItems()).isEqualTo(25);
            assertThat(response.totalPages()).isEqualTo(3);
            assertThat(response.hasNext()).isTrue();
            assertThat(response.hasPrevious()).isFalse();
        }
    }

    // ========== Helpers ==========

    private Credential buildCredentialWithData(Map<String, Object> data) {
        Instant now = Instant.now();
        return new Credential(
                1L, "user1", "My Cred", "gmail",
                CredentialType.OAuth2,
                CredentialEnvironment.Production,
                CredentialStatus.needs_reauth,
                "Test credential",
                data,
                List.of("email"),
                List.of("oauth2"),
                "user1",
                "https://icon.url",
                false,
                now, now, now
        );
    }

    private Credential buildCredential(Long id, String tenantId, String name, boolean isDefault) {
        Instant now = Instant.now();
        return new Credential(
                id, tenantId, name, "gmail",
                CredentialType.OAuth2,
                CredentialEnvironment.Production,
                CredentialStatus.active,
                "Test credential",
                Map.of("access_token", "tok123"),
                List.of("email", "profile"),
                List.of("oauth2"),
                tenantId,
                "https://icon.url",
                isDefault,
                now, now, now
        );
    }
}
