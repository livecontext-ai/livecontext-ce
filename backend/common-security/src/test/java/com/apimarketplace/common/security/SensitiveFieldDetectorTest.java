package com.apimarketplace.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the two properties the detector must keep: it is a superset of the legacy allow-list
 * (or rows already encrypted stop being decrypted), and it never flags a key the SQL layer
 * compares (or the refresh scheduler goes blind).
 */
@DisplayName("SensitiveFieldDetector")
class SensitiveFieldDetectorTest {

    @Nested
    @DisplayName("superset of the legacy allow-list")
    class LegacySuperset {

        @Test
        @DisplayName("every one of the 15 names the old implementation encrypted is still sensitive")
        void legacyNamesStaySensitive() {
            for (String legacy : SensitiveFieldDetector.LEGACY_EXACT_NAMES) {
                assertThat(SensitiveFieldDetector.isSensitive(legacy))
                        .as("legacy name '%s' must stay sensitive or its ciphertext is never decrypted", legacy)
                        .isTrue();
            }
            assertThat(SensitiveFieldDetector.LEGACY_EXACT_NAMES).hasSize(15);
        }
    }

    @Nested
    @DisplayName("the catalogue's custom-auth fields (in clear until 2026-09-17)")
    class CatalogueCustomAuthFields {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                // AWS (24 APIs), GCP / Box / DocuSign / Coinbase (16), and the long tail
                "secret_access_key", "private_key", "api_secret", "secret_key", "session_token",
                "sessionToken", "app_key", "database_secret", "token_secret", "auth_token",
                "transaction_key", "sas_token", "private_key_passphrase", "service_key",
                "api_key_secret", "flinks_auth_key", "x_api_key", "content_api_key", "admin_api_key",
                "developer_token", "mocean_api_secret", "nitro_pass", "service_token", "passphrase",
                "user_token", "teamsecret", "access_token_secret", "app_token", "consumer_secret",
                // apiKeyConfig.keyName spellings
                "api_token", "personal_access_token", "apikey", "private_token", "bot_token",
                "vault_token", "nomad_token", "subscription_key", "deploy_key", "server_token",
                "webhook_key", "Authorization", "x-api-key", "xc-token", "entra_bearer_token",
                "Personal Access Token", "personal_access_token_or_service_account_key",
                // camel-case / squashed
                "clientSecret", "accessToken", "refreshToken", "googleIdToken", "apiSecret",
                "privateKey", "secretAccessKey",
                // a URL / connection string whose value IS the credential
                "database_url", "DATABASE_URL", "db_url", "connection_string", "webhook_url", "redis_uri",
                "mongodb_uri", "amqp_url", "dsn", "postgres_connection_string", "bootstrap_servers_url",
                "slack_hook_url", "hooks_url"
        })
        @DisplayName("is sensitive")
        void catalogueSecretIsSensitive(String key) {
            assertThat(SensitiveFieldDetector.isSensitive(key)).isTrue();
        }
    }

    @Nested
    @DisplayName("keys that must NEVER be encrypted")
    class NeverEncrypted {

        /**
         * Every key present in production credential_data on 2026-09-17 that is not a secret,
         * plus the three the refresh scheduler compares in SQL. Encrypting any of these silently
         * breaks a query or hides a display value.
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                // read in SQL by CredentialRepository (refresh eligibility)
                "expires_at", "refresh_mode", "refresh_cooldown_until",
                // production metadata keys
                "credential_template_key", "credential_template_id", "credential_template_icon_slug",
                "credential_template_variant", "token_type", "client_id", "oauth_client_id",
                "client_secret_masked", "scope", "refresh_token_issued_at", "username", "host", "port",
                "your_coolify_host", "grafana_url", "from_name", "from_email", "use_ssl",
                // catalogue identifiers that share a word with a secret
                "access_key_id", "key_id", "public_key", "public_key_id", "private_key_id",
                "public_api_key", "token_id", "token_name", "service_token_id", "api_login_id",
                "store_hash", "application_id", "applicationId", "credential_id", "credentialId",
                "signatureType", "region", "project_id", "client_email", "account_id", "domain",
                "instance_host", "endpoint_url", "admin_domain", "api_key_hint", "byok_revoke_reason",
                "auth_url", "token_url", "callback_url", "redirect_uri", "base_url", "grafana_url",
                "refresh_error_reason", "refresh_attempts_before_terminal"
        })
        @DisplayName("is not sensitive")
        void metadataIsNotSensitive(String key) {
            assertThat(SensitiveFieldDetector.isSensitive(key)).isFalse();
        }

        @Test
        @DisplayName("null and empty keys are not sensitive")
        void nullAndEmpty() {
            assertThat(SensitiveFieldDetector.isSensitive(null)).isFalse();
            assertThat(SensitiveFieldDetector.isSensitive("")).isFalse();
        }
    }

    @Nested
    @DisplayName("through CredentialEncryptionService")
    class ThroughService {

        private final CredentialEncryptionService service =
                new CredentialEncryptionService("test-password-123", "0123456789abcdef");

        @Test
        @DisplayName("an AWS credential map round-trips with its secret encrypted and its id in clear")
        void awsMapRoundTrip() {
            Map<String, Object> raw = Map.of(
                    "access_key_id", "AKIAEXAMPLE",
                    "secret_access_key", "wJalrXUtnFEMI/K7MDENG",
                    "region", "eu-west-3");

            Map<String, Object> stored = service.encryptSensitiveFields(raw);

            assertThat(stored.get("access_key_id")).isEqualTo("AKIAEXAMPLE");
            assertThat(stored.get("region")).isEqualTo("eu-west-3");
            assertThat((String) stored.get("secret_access_key")).startsWith("ENC:");
            assertThat(service.decryptSensitiveFields(stored)).isEqualTo(raw);
        }

        @Test
        @DisplayName("the sweep pages on a BIGINT id: a non-numeric id is reported, never silently skipped as clean")
        void nonNumericIdIsReported() {
            org.springframework.jdbc.core.JdbcTemplate jdbc = org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
            org.mockito.Mockito.when(jdbc.queryForList(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(Object[].class)))
                    .thenReturn(List.of(Map.of("id", "not-a-number", "js", "{\"api_secret\":\"s\"}")));
            SensitiveJsonbBackfill sweep = new SensitiveJsonbBackfill(jdbc, new com.fasterxml.jackson.databind.ObjectMapper(), service);
            SensitiveJsonbBackfill.TableSpec spec = new SensitiveJsonbBackfill.TableSpec("auth.x", "id", "credential_data");

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> sweep.migrate(spec))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("BIGINT");
            // migrateAll turns it into a WARN so one bad spec never stops the others
            assertThat(sweep.migrateAll(List.of(spec))).isZero();
        }

        @Test
        @DisplayName("the JSONB sweep refuses to touch the database on ephemeral material")
        void sweepRefusesEphemeralMaterial() {
            CredentialEncryptionService ephemeral = new CredentialEncryptionService("", "");
            assertThat(ephemeral.isUsingEphemeralMaterial()).isTrue();
            assertThat(service.isUsingEphemeralMaterial()).isFalse();
            org.springframework.jdbc.core.JdbcTemplate jdbc = org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
            SensitiveJsonbBackfill sweep = new SensitiveJsonbBackfill(jdbc, new com.fasterxml.jackson.databind.ObjectMapper(), ephemeral);

            assertThat(sweep.migrateAll(List.of(new SensitiveJsonbBackfill.TableSpec("auth.credentials", "id", "credential_data")))).isZero();

            org.mockito.Mockito.verifyNoInteractions(jdbc);
        }

        @Test
        @DisplayName("hasPlaintextSensitiveField is true only while a secret is in clear")
        void plaintextProbe() {
            Map<String, Object> plaintext = Map.of("private_key", "-----BEGIN", "client_email", "x@y");
            assertThat(service.hasPlaintextSensitiveField(plaintext)).isTrue();
            assertThat(service.hasPlaintextSensitiveField(service.encryptSensitiveFields(plaintext))).isFalse();
            assertThat(service.hasPlaintextSensitiveField(Map.of("client_email", "x@y"))).isFalse();
            assertThat(service.hasPlaintextSensitiveField(Map.of("private_key", ""))).isFalse();
            assertThat(service.hasPlaintextSensitiveField(Map.of("private_key", List.of("not", "a", "string")))).isFalse();
            assertThat(service.hasPlaintextSensitiveField(null)).isFalse();
        }
    }
}
