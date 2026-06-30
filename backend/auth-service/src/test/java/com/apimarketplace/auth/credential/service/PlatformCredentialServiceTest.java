package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.*;
import com.apimarketplace.auth.credential.repository.PlatformCredentialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PlatformCredentialService Tests")
class PlatformCredentialServiceTest {

    @Mock
    private PlatformCredentialRepository repository;

    private PlatformCredentialService service;

    @BeforeEach
    void setUp() {
        service = new PlatformCredentialService(repository);
    }

    // ========== getOAuth2Credentials ==========

    @Nested
    @DisplayName("getOAuth2Credentials")
    class GetOAuth2CredentialsTests {

        @Test
        @DisplayName("should return credentials when found, enabled, and has OAuth2")
        void getOAuth2Credentials_found() {
            PlatformCredential cred = buildCredential("testint", "client-id", "client-secret", null, true);
            when(repository.findOAuth2ByIntegrationName("testint")).thenReturn(Optional.of(cred));

            Optional<OAuth2Credentials> result = service.getOAuth2Credentials("testint");

            assertThat(result).isPresent();
            assertThat(result.get().clientId()).isEqualTo("client-id");
            assertThat(result.get().clientSecret()).isEqualTo("client-secret");
        }

        @Test
        @DisplayName("should return empty when not found")
        void getOAuth2Credentials_notFound() {
            when(repository.findOAuth2ByIntegrationName("nonexistent")).thenReturn(Optional.empty());

            Optional<OAuth2Credentials> result = service.getOAuth2Credentials("nonexistent");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty when credential is disabled")
        void getOAuth2Credentials_disabled() {
            PlatformCredential cred = buildCredential("testint", "client-id", "client-secret", null, false);
            when(repository.findOAuth2ByIntegrationName("testint")).thenReturn(Optional.of(cred));

            Optional<OAuth2Credentials> result = service.getOAuth2Credentials("testint");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty when no OAuth2 credentials (no clientId/Secret)")
        void getOAuth2Credentials_noOAuth2() {
            PlatformCredential cred = buildCredential("testint", null, null, "api-key", true);
            when(repository.findOAuth2ByIntegrationName("testint")).thenReturn(Optional.of(cred));

            Optional<OAuth2Credentials> result = service.getOAuth2Credentials("testint");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should normalize integration name before lookup")
        void getOAuth2Credentials_normalizesName() {
            PlatformCredential cred = buildCredential("gmailcredential", "cid", "csec", null, true);
            // "Gmail Credential" normalizes to "gmail" (lowercase, strip " credential", strip spaces)
            when(repository.findOAuth2ByIntegrationName("gmail")).thenReturn(Optional.of(cred));

            Optional<OAuth2Credentials> result = service.getOAuth2Credentials("Gmail Credential");

            assertThat(result).isPresent();
        }
    }

    // ========== hasOAuth2Credentials ==========

    @Nested
    @DisplayName("hasOAuth2Credentials")
    class HasOAuth2CredentialsTests {

        @Test
        @DisplayName("should return true when OAuth2 credentials exist")
        void hasOAuth2Credentials_true() {
            PlatformCredential cred = buildCredential("test", "cid", "csec", null, true);
            when(repository.findOAuth2ByIntegrationName("test")).thenReturn(Optional.of(cred));

            assertThat(service.hasOAuth2Credentials("test")).isTrue();
        }

        @Test
        @DisplayName("should return false when no credentials")
        void hasOAuth2Credentials_false() {
            when(repository.findOAuth2ByIntegrationName("test")).thenReturn(Optional.empty());

            assertThat(service.hasOAuth2Credentials("test")).isFalse();
        }
    }

    // ========== getPlatformCredentialsAvailability ==========

    @Nested
    @DisplayName("getPlatformCredentialsAvailability")
    class GetPlatformCredentialsAvailabilityTests {

        @Test
        @DisplayName("showUnverifiedAppWarning=false suppresses the OAuth warning while keeping platform OAuth available")
        void availability_suppressesWarningWhenCredentialFlagDisabled() {
            PlatformCredential cred = buildCredential("gmail", "cid", "csec", null, true, false);
            when(repository.findOAuth2ByIntegrationName("gmail")).thenReturn(Optional.of(cred));

            PlatformCredentialsAvailability result = service.getPlatformCredentialsAvailability("Gmail");

            assertThat(result.available()).isTrue();
            assertThat(result.showUnverifiedAppWarning()).isFalse();
        }

        @Test
        @DisplayName("showUnverifiedAppWarning=true surfaces the OAuth warning when platform OAuth is available")
        void availability_showsWarningWhenCredentialFlagEnabled() {
            PlatformCredential cred = buildCredential("gmail", "cid", "csec", null, true, true);
            when(repository.findOAuth2ByIntegrationName("gmail")).thenReturn(Optional.of(cred));

            PlatformCredentialsAvailability result = service.getPlatformCredentialsAvailability("gmail");

            assertThat(result.available()).isTrue();
            assertThat(result.showUnverifiedAppWarning()).isTrue();
        }

        @Test
        @DisplayName("unavailable platform OAuth never asks the frontend to show the warning")
        void availability_unavailableSuppressesWarning() {
            PlatformCredential cred = buildCredential("gmail", "cid", "csec", null, false, true);
            when(repository.findOAuth2ByIntegrationName("gmail")).thenReturn(Optional.of(cred));

            PlatformCredentialsAvailability result = service.getPlatformCredentialsAvailability("gmail");

            assertThat(result.available()).isFalse();
            assertThat(result.showUnverifiedAppWarning()).isFalse();
        }

        @Test
        @DisplayName("uses the OAuth2-specific lookup so non-OAuth sibling variants cannot drive the warning")
        void availability_usesOAuth2SpecificLookup() {
            PlatformCredential cred = buildCredential("airtable", "cid", "csec", null, true, false);
            when(repository.findOAuth2ByIntegrationName("airtable")).thenReturn(Optional.of(cred));

            PlatformCredentialsAvailability result = service.getPlatformCredentialsAvailability("Airtable");

            assertThat(result.available()).isTrue();
            assertThat(result.showUnverifiedAppWarning()).isFalse();
            verify(repository).findOAuth2ByIntegrationName("airtable");
            verify(repository, never()).findByIntegrationName("airtable");
        }
    }

    // ========== hasDbCredentials ==========

    @Nested
    @DisplayName("hasDbCredentials")
    class HasDbCredentialsTests {

        @Test
        @DisplayName("should return true when DB credential is present, enabled, and has OAuth2")
        void hasDbCredentials_true() {
            PlatformCredential cred = buildCredential("test", "cid", "csec", null, true);
            when(repository.findOAuth2ByIntegrationName("test")).thenReturn(Optional.of(cred));

            assertThat(service.hasDbCredentials("test")).isTrue();
        }

        @Test
        @DisplayName("should return false when DB credential is disabled")
        void hasDbCredentials_falseDisabled() {
            PlatformCredential cred = buildCredential("test", "cid", "csec", null, false);
            when(repository.findOAuth2ByIntegrationName("test")).thenReturn(Optional.of(cred));

            assertThat(service.hasDbCredentials("test")).isFalse();
        }

        @Test
        @DisplayName("should return false when DB credential has no OAuth2")
        void hasDbCredentials_falseNoOAuth2() {
            PlatformCredential cred = buildCredential("test", null, null, "apikey", true);
            when(repository.findOAuth2ByIntegrationName("test")).thenReturn(Optional.of(cred));

            assertThat(service.hasDbCredentials("test")).isFalse();
        }
    }

    // ========== saveCredential ==========

    @Nested
    @DisplayName("saveCredential")
    class SaveCredentialTests {

        @Test
        @DisplayName("should create new credential when integration does not exist")
        void saveCredential_createsNew() {
            when(repository.findByIntegrationName("newintegration")).thenReturn(Optional.empty());
            when(repository.save(any())).thenAnswer(inv -> {
                PlatformCredential c = inv.getArgument(0);
                return c.withId(1L);
            });

            CreatePlatformCredentialRequest request = new CreatePlatformCredentialRequest(
                    "new_integration", "New Integration", "oauth2",
                    "client-id", "client-secret", null, null, null,
                    "https://auth.url", "https://token.url", "scope",
                    "icon", "category", "desc", null, null, null
            );

            PlatformCredentialResponse result = service.saveCredential(request);

            ArgumentCaptor<PlatformCredential> captor = ArgumentCaptor.forClass(PlatformCredential.class);
            verify(repository).save(captor.capture());

            PlatformCredential saved = captor.getValue();
            assertThat(saved.id()).isNull(); // New credential
            assertThat(saved.isEnabled()).isTrue(); // Default enabled
            assertThat(saved.integrationName()).isEqualTo("newintegration"); // Normalized
        }

        @Test
        @DisplayName("creates a credential with showUnverifiedAppWarning=false when the admin disables the OAuth warning")
        void saveCredential_createPersistsSuppressedUnverifiedWarningFlag() {
            when(repository.findByIntegrationName("newintegration")).thenReturn(Optional.empty());
            when(repository.save(any())).thenAnswer(inv -> {
                PlatformCredential c = inv.getArgument(0);
                return c.withId(1L);
            });

            CreatePlatformCredentialRequest request = new CreatePlatformCredentialRequest(
                    "new_integration", "New Integration", "oauth2",
                    "client-id", "client-secret", null, null, null,
                    "https://auth.url", "https://token.url", "scope",
                    "icon", "category", "desc", null, null, null,
                    false, null
            );

            service.saveCredential(request);

            ArgumentCaptor<PlatformCredential> captor = ArgumentCaptor.forClass(PlatformCredential.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().showUnverifiedAppWarning()).isFalse();
        }

        @Test
        @DisplayName("should update existing credential and merge custom fields")
        void saveCredential_updatesExisting() {
            PlatformCredential existing = new PlatformCredential(
                    10L, "existing", "Existing", AuthType.OAUTH2,
                    "old-cid", "old-csec", null, null, null,
                    "https://old.auth", "https://old.token", "old-scope",
                    "old-icon", "old-category", "old-desc", true,
                    Map.of("key1", "val1"),
                    java.math.BigDecimal.ZERO,
                    500,
                    Instant.now(), Instant.now(), "admin", null
            );
            when(repository.findByIntegrationName("existing")).thenReturn(Optional.of(existing));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(repository.findEndpointsByCredentialId(10L)).thenReturn(List.of());

            CreatePlatformCredentialRequest request = new CreatePlatformCredentialRequest(
                    "Existing", "Updated Name", "api_key",
                    "new-cid", "new-csec", null, null, null,
                    "https://new.auth", "https://new.token", "new-scope",
                    null, null, "new-desc", Map.of("key2", "val2"), null, null
            );

            service.saveCredential(request);

            ArgumentCaptor<PlatformCredential> captor = ArgumentCaptor.forClass(PlatformCredential.class);
            verify(repository).save(captor.capture());

            PlatformCredential saved = captor.getValue();
            assertThat(saved.id()).isEqualTo(10L);
            assertThat(saved.displayName()).isEqualTo("Updated Name");
            assertThat(saved.clientId()).isEqualTo("new-cid");
            assertThat(saved.isEnabled()).isTrue(); // Preserves existing enabled state
            assertThat(saved.customFields()).containsEntry("key1", "val1"); // Old field preserved
            assertThat(saved.customFields()).containsEntry("key2", "val2"); // New field merged
        }

        @Test
        @DisplayName("update response is built from the persisted row so omitted secrets still report as configured")
        void saveCredential_updateResponseReloadsPersistedSecrets() {
            Instant now = Instant.now();
            PlatformCredential existing = new PlatformCredential(
                    10L, "existingapikey", "Existing API Key", AuthType.API_KEY,
                    null, null, "old-api-key", null, null,
                    null, null, null, "old-icon", "old-category", "old-desc",
                    true, Map.of(), java.math.BigDecimal.ZERO, 25,
                    now, now, "admin", null, "api_key"
            );
            PlatformCredential reloaded = new PlatformCredential(
                    10L, "existingapikey", "Updated API Key", AuthType.API_KEY,
                    null, null, "old-api-key", null, null,
                    null, null, null, "new-icon", "new-category", "new-desc",
                    true, Map.of(), java.math.BigDecimal.ZERO, 30,
                    now, now, "admin", null, "api_key"
            );
            when(repository.findByIntegrationNameAndVariant("existingapikey", "api_key"))
                    .thenReturn(Optional.of(existing));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(repository.findById(10L)).thenReturn(Optional.of(reloaded));
            when(repository.findEndpointsByCredentialId(10L)).thenReturn(List.of());

            CreatePlatformCredentialRequest request = new CreatePlatformCredentialRequest(
                    "existing_api_key", "Updated API Key", "api_key",
                    null, null, null, null, null,
                    null, null, null, "new-icon", "new-category", "new-desc",
                    null, null, 30, "api_key"
            );

            PlatformCredentialResponse result = service.saveCredential(request);

            ArgumentCaptor<PlatformCredential> captor = ArgumentCaptor.forClass(PlatformCredential.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().apiKey()).isNull();
            assertThat(result.hasApiKey()).isTrue();
            assertThat(result.displayName()).isEqualTo("Updated API Key");
            assertThat(result.maxCallsPerRun()).isEqualTo(30);
        }

        @Test
        @DisplayName("updates an existing credential with showUnverifiedAppWarning=false when the admin disables the OAuth warning")
        void saveCredential_updatePersistsSuppressedUnverifiedWarningFlag() {
            PlatformCredential existing = buildCredential("existing", "old-cid", "old-csec", null, true, true)
                    .withId(10L);
            when(repository.findByIntegrationName("existing")).thenReturn(Optional.of(existing));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(repository.findEndpointsByCredentialId(10L)).thenReturn(List.of());

            CreatePlatformCredentialRequest request = new CreatePlatformCredentialRequest(
                    "Existing", "Existing", "oauth2",
                    "new-cid", "new-csec", null, null, null,
                    "https://auth.url", "https://token.url", "scope",
                    "icon", "category", "desc", null, null, null,
                    false, null
            );

            service.saveCredential(request);

            ArgumentCaptor<PlatformCredential> captor = ArgumentCaptor.forClass(PlatformCredential.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().showUnverifiedAppWarning()).isFalse();
        }

        @Test
        @DisplayName("should use integrationName as displayName when displayName is null on create")
        void saveCredential_defaultDisplayName() {
            when(repository.findByIntegrationName("myint")).thenReturn(Optional.empty());
            when(repository.save(any())).thenAnswer(inv -> {
                PlatformCredential c = inv.getArgument(0);
                return c.withId(1L);
            });

            CreatePlatformCredentialRequest request = new CreatePlatformCredentialRequest(
                    "my_int", null, "oauth2",
                    "cid", "csec", null, null, null,
                    null, null, null, null, null, null, null, null, null
            );

            service.saveCredential(request);

            ArgumentCaptor<PlatformCredential> captor = ArgumentCaptor.forClass(PlatformCredential.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().displayName()).isEqualTo("my_int");
        }

        @Test
        @DisplayName("Routes to (integration, variant) lookup when request.variant is set - legacy lookup must not fire, so the oauth2 row is not clobbered when the admin edits bearer_token")
        void saveCredential_variantRoutesToVariantLookup() {
            PlatformCredential existingBearer = new PlatformCredential(
                    42L, "airtable", "Airtable", AuthType.BEARER,
                    null, null, null, null, null,
                    null, null, null, "airtable", null, null, true,
                    Map.of(), java.math.BigDecimal.ZERO, 500,
                    Instant.now(), Instant.now(), null, null, "bearer_token"
            );
            when(repository.findByIntegrationNameAndVariant("airtable", "bearer_token"))
                    .thenReturn(Optional.of(existingBearer));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(repository.findEndpointsByCredentialId(42L)).thenReturn(List.of());

            CreatePlatformCredentialRequest request = new CreatePlatformCredentialRequest(
                    "airtable", "Airtable", "bearer_token",
                    null, null, "new-bearer-token", null, null,
                    null, null, null, null, null, null, null, null, null,
                    "bearer_token"
            );

            service.saveCredential(request);

            // Critical: legacy lookup must NOT fire - otherwise we'd update
            // the first row returned (potentially oauth2) and silently lose
            // the sibling variant the admin wanted to preserve.
            verify(repository, never()).findByIntegrationName(anyString());
            verify(repository).findByIntegrationNameAndVariant("airtable", "bearer_token");

            ArgumentCaptor<PlatformCredential> captor = ArgumentCaptor.forClass(PlatformCredential.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().id()).isEqualTo(42L);
            assertThat(captor.getValue().variant()).isEqualTo("bearer_token");
            assertThat(captor.getValue().apiKey()).isEqualTo("new-bearer-token");
        }

        @Test
        @DisplayName("Stores the requested variant on a brand-new row so the (integration, variant) UNIQUE key is satisfied instead of collapsing to DEFAULT_VARIANT")
        void saveCredential_variantStoredOnCreate() {
            when(repository.findByIntegrationNameAndVariant("airtable", "oauth2"))
                    .thenReturn(Optional.empty());
            when(repository.save(any())).thenAnswer(inv -> {
                PlatformCredential c = inv.getArgument(0);
                return c.withId(7L);
            });

            CreatePlatformCredentialRequest request = new CreatePlatformCredentialRequest(
                    "airtable", "Airtable", "oauth2",
                    "oa-cid", "oa-csec", null, null, null,
                    null, null, null, null, null, null, null, null, null,
                    "oauth2"
            );

            service.saveCredential(request);

            ArgumentCaptor<PlatformCredential> captor = ArgumentCaptor.forClass(PlatformCredential.class);
            verify(repository).save(captor.capture());
            PlatformCredential saved = captor.getValue();
            assertThat(saved.id()).isNull();
            assertThat(saved.variant()).isEqualTo("oauth2");
            assertThat(saved.variant()).isNotEqualTo(PlatformCredential.DEFAULT_VARIANT);
            assertThat(saved.clientId()).isEqualTo("oa-cid");
        }

        @Test
        @DisplayName("Update preserves current.variant() - the admin's request.variant selected the row, but the stored column must stay identical so a refactor that copied requestedVariant onto saved rows can't silently drift the UNIQUE key")
        void saveCredential_updatePreservesCurrentVariant() {
            PlatformCredential existingBearer = new PlatformCredential(
                    99L, "airtable", "Airtable", AuthType.BEARER,
                    null, null, null, null, null,
                    null, null, null, "airtable", null, null, true,
                    Map.of(), java.math.BigDecimal.ZERO, 500,
                    Instant.now(), Instant.now(), null, null, "bearer_token"
            );
            when(repository.findByIntegrationNameAndVariant("airtable", "bearer_token"))
                    .thenReturn(Optional.of(existingBearer));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(repository.findEndpointsByCredentialId(99L)).thenReturn(List.of());

            CreatePlatformCredentialRequest request = new CreatePlatformCredentialRequest(
                    "airtable", "Airtable Renamed", "bearer_token",
                    null, null, "rotated-token", null, null,
                    null, null, null, null, null, null, null, null, null,
                    "bearer_token"
            );

            service.saveCredential(request);

            ArgumentCaptor<PlatformCredential> captor = ArgumentCaptor.forClass(PlatformCredential.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().variant()).isEqualTo("bearer_token");
            assertThat(captor.getValue().variant()).isEqualTo(existingBearer.variant());
        }

        @Test
        @DisplayName("Tenant-scoped save ignores request.variant - tenant rows are single-row-per-integration by design, so the admin's per-variant concept must not leak into the tenant branch and collide with a platform-wide bearer_token row")
        void saveCredential_tenantScopedIgnoresRequestVariant() {
            PlatformCredential tenantCred = new PlatformCredential(
                    15L, "airtable", "Airtable", AuthType.OAUTH2,
                    "t-cid", "t-csec", null, null, null,
                    null, null, null, "airtable", null, null, true,
                    Map.of(), java.math.BigDecimal.ZERO, 500,
                    Instant.now(), Instant.now(), null, "tenant-123", "primary"
            );
            // V362: tenant saves resolve the exact (tenant, workspace) row via
            // findOwnedRow; the 2-arg saveCredential defaults the workspace to
            // null (personal scope).
            when(repository.findOwnedRow("airtable", "tenant-123", null))
                    .thenReturn(Optional.of(tenantCred));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(repository.findEndpointsByCredentialId(15L)).thenReturn(List.of());

            CreatePlatformCredentialRequest request = new CreatePlatformCredentialRequest(
                    "airtable", "Airtable", "oauth2",
                    "new-cid", "new-csec", null, null, null,
                    null, null, null, null, null, null, null, null, null,
                    "bearer_token" // variant on a tenant save is intentionally ignored
            );

            service.saveCredential(request, "tenant-123");

            // Critical invariant: tenant branch uses the scoped owned-row lookup
            // only; if the variant leaked through, we'd chase a platform-wide
            // bearer_token row and either 404 or clobber it.
            verify(repository, never()).findByIntegrationNameAndVariant(anyString(), anyString());
            verify(repository).findOwnedRow("airtable", "tenant-123", null);

            ArgumentCaptor<PlatformCredential> captor = ArgumentCaptor.forClass(PlatformCredential.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().variant()).isEqualTo("primary");
        }

        @Test
        @DisplayName("Tenant-scoped create ignores request.variant and stores primary")
        void saveCredential_tenantScopedCreateIgnoresRequestVariant() {
            when(repository.findOwnedRow("airtable", "tenant-123", null))
                    .thenReturn(Optional.empty());
            when(repository.countByTenantIdAndOrganizationId("tenant-123", null)).thenReturn(0);
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(repository.findEndpointsByCredentialId(isNull())).thenReturn(List.of());

            CreatePlatformCredentialRequest request = new CreatePlatformCredentialRequest(
                    "airtable", "Airtable", "oauth2",
                    "new-cid", "new-csec", null, null, null,
                    null, null, null, null, null, null, null, null, null,
                    "bearer_token"
            );

            service.saveCredential(request, "tenant-123");

            verify(repository, never()).findByIntegrationNameAndVariant(anyString(), anyString());
            ArgumentCaptor<PlatformCredential> captor = ArgumentCaptor.forClass(PlatformCredential.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().variant()).isEqualTo("primary");
            assertThat(captor.getValue().tenantId()).isEqualTo("tenant-123");
        }

        @Test
        @DisplayName("Null variant falls back to legacy findByIntegrationName path so pre-Phase-2d callers (tenant-scoped saves, older tests) behave unchanged")
        void saveCredential_nullVariantFallsBackToLegacyLookup() {
            when(repository.findByIntegrationName("legacyint")).thenReturn(Optional.empty());
            when(repository.save(any())).thenAnswer(inv -> {
                PlatformCredential c = inv.getArgument(0);
                return c.withId(1L);
            });

            // Use the 17-arg legacy constructor - variant defaults to null.
            CreatePlatformCredentialRequest request = new CreatePlatformCredentialRequest(
                    "legacy_int", "Legacy", "oauth2",
                    "cid", "csec", null, null, null,
                    null, null, null, null, null, null, null, null, null
            );

            service.saveCredential(request);

            verify(repository).findByIntegrationName("legacyint");
            verify(repository, never()).findByIntegrationNameAndVariant(anyString(), anyString());

            ArgumentCaptor<PlatformCredential> captor = ArgumentCaptor.forClass(PlatformCredential.class);
            verify(repository).save(captor.capture());
            // With no variant on the request and no existing row, the created
            // credential falls through to DEFAULT_VARIANT via the record's
            // 23-arg ctor - kept for back-compat with pre-V103 rows.
            assertThat(captor.getValue().variant()).isEqualTo(PlatformCredential.DEFAULT_VARIANT);
        }
    }

    // ========== updateCredential ==========

    @Nested
    @DisplayName("updateCredential")
    class UpdateCredentialTests {

        @Test
        @DisplayName("metadata-only updates return persisted secret presence flags")
        void updateCredential_metadataOnlyReloadsPersistedSecretsForResponse() {
            Instant now = Instant.now();
            PlatformCredential existing = new PlatformCredential(
                    10L, "existingapikey", "Existing API Key", AuthType.API_KEY,
                    null, null, "old-api-key", null, null,
                    null, null, null, "old-icon", "old-category", "old-desc",
                    true, Map.of(), java.math.BigDecimal.ZERO, 25,
                    now, now, "admin", null, "api_key"
            );
            PlatformCredential reloaded = new PlatformCredential(
                    10L, "existingapikey", "Updated API Key", AuthType.API_KEY,
                    null, null, "old-api-key", null, null,
                    null, null, null, "new-icon", "new-category", "new-desc",
                    true, Map.of(), java.math.BigDecimal.ZERO, 30,
                    now, now, "admin", null, "api_key"
            );
            when(repository.findByIntegrationName("existingapikey")).thenReturn(Optional.of(existing));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(repository.findById(10L)).thenReturn(Optional.of(reloaded));
            when(repository.findEndpointsByCredentialId(10L)).thenReturn(List.of());

            UpdatePlatformCredentialRequest request = new UpdatePlatformCredentialRequest(
                    "Updated API Key",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "new-icon",
                    "new-category",
                    "new-desc",
                    null,
                    null,
                    null,
                    30
            );

            Optional<PlatformCredentialResponse> result = service.updateCredential("existing_api_key", request);

            assertThat(result).isPresent();
            ArgumentCaptor<PlatformCredential> captor = ArgumentCaptor.forClass(PlatformCredential.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().apiKey()).isNull();
            assertThat(result.get().hasApiKey()).isTrue();
            assertThat(result.get().displayName()).isEqualTo("Updated API Key");
            assertThat(result.get().maxCallsPerRun()).isEqualTo(30);
        }
    }

    // ========== deleteCredential ==========

    @Nested
    @DisplayName("deleteCredential")
    class DeleteCredentialTests {

        @Test
        @DisplayName("should delegate to repository and return true on success")
        void deleteCredential_success() {
            when(repository.deleteByIntegrationName("test")).thenReturn(true);

            boolean result = service.deleteCredential("test");

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should return false when credential not found")
        void deleteCredential_notFound() {
            when(repository.deleteByIntegrationName("nonexistent")).thenReturn(false);

            boolean result = service.deleteCredential("nonexistent");

            assertThat(result).isFalse();
        }
    }

    // ========== normalizeIntegrationName ==========

    @Nested
    @DisplayName("normalizeIntegrationName")
    class NormalizeIntegrationNameTests {

        @Test
        @DisplayName("should lowercase the name")
        void normalize_lowercase() {
            assertThat(PlatformCredentialService.normalizeIntegrationName("Gmail")).isEqualTo("gmail");
        }

        @Test
        @DisplayName("should strip ' credential' suffix")
        void normalize_stripCredential() {
            assertThat(PlatformCredentialService.normalizeIntegrationName("Gmail Credential"))
                    .isEqualTo("gmail");
        }

        @Test
        @DisplayName("should remove spaces")
        void normalize_removeSpaces() {
            assertThat(PlatformCredentialService.normalizeIntegrationName("My Service"))
                    .isEqualTo("myservice");
        }

        @Test
        @DisplayName("should remove hyphens")
        void normalize_removeHyphens() {
            assertThat(PlatformCredentialService.normalizeIntegrationName("my-service"))
                    .isEqualTo("myservice");
        }

        @Test
        @DisplayName("should remove underscores")
        void normalize_removeUnderscores() {
            assertThat(PlatformCredentialService.normalizeIntegrationName("my_service"))
                    .isEqualTo("myservice");
        }

        @Test
        @DisplayName("should return null for null input")
        void normalize_null() {
            assertThat(PlatformCredentialService.normalizeIntegrationName(null)).isNull();
        }

        @Test
        @DisplayName("should handle combined transformations")
        void normalize_combined() {
            assertThat(PlatformCredentialService.normalizeIntegrationName("Google_Drive Credential"))
                    .isEqualTo("googledrive");
        }

        @Test
        @DisplayName("should strip punctuation - regression for Google Pub/Sub and *.io providers")
        void normalize_stripsPunctuation() {
            // These match what IconSlugNormalizer.normalizeForKey() writes to
            // auth.platform_credentials.integration_name in catalog-service-import.
            assertThat(PlatformCredentialService.normalizeIntegrationName("Google Pub/Sub"))
                    .isEqualTo("googlepubsub");
            assertThat(PlatformCredentialService.normalizeIntegrationName("Apollo.io"))
                    .isEqualTo("apolloio");
            assertThat(PlatformCredentialService.normalizeIntegrationName("Cal.com"))
                    .isEqualTo("calcom");
            assertThat(PlatformCredentialService.normalizeIntegrationName("Last.fm"))
                    .isEqualTo("lastfm");
            assertThat(PlatformCredentialService.normalizeIntegrationName("Judge.me"))
                    .isEqualTo("judgeme");
        }

        @Test
        @DisplayName("should strip accents (NFD normalization)")
        void normalize_stripsAccents() {
            assertThat(PlatformCredentialService.normalizeIntegrationName("Société Générale"))
                    .isEqualTo("societegenerale");
        }

        @Test
        @DisplayName("should trim surrounding whitespace")
        void normalize_trimsWhitespace() {
            assertThat(PlatformCredentialService.normalizeIntegrationName("  Gmail  "))
                    .isEqualTo("gmail");
        }

        @Test
        @DisplayName("all-punctuation input → empty string")
        void normalize_allPunctuation() {
            assertThat(PlatformCredentialService.normalizeIntegrationName("!!!"))
                    .isEqualTo("");
        }

        @Test
        @DisplayName("collapses multiple consecutive punctuation characters")
        void normalize_collapsesConsecutivePunctuation() {
            assertThat(PlatformCredentialService.normalizeIntegrationName("A..B"))
                    .isEqualTo("ab");
        }

        @Test
        @DisplayName("unicode emoji are stripped")
        void normalize_stripsEmoji() {
            assertThat(PlatformCredentialService.normalizeIntegrationName("Gmail \uD83D\uDC8C"))
                    .isEqualTo("gmail");
        }

        @Test
        @DisplayName("non-latin letters are stripped (matches IconSlugNormalizer)")
        void normalize_nonLatinStripped() {
            // "日本" has no ASCII fallback so it gets stripped entirely.
            // Both IconSlugNormalizer.normalizeForKey and this method share this behavior.
            assertThat(PlatformCredentialService.normalizeIntegrationName("GitHub 日本"))
                    .isEqualTo("github");
        }

        @Test
        @DisplayName("'_credential' suffix without space is NOT stripped (suffix match requires space)")
        void normalize_underscoreCredentialSuffixNotStripped() {
            // The " credential" (with leading space) suffix is a legacy convention for
            // inputs like "Gmail Credential". Inputs like "Gmail_credential" don't have
            // the space, so the suffix check fails - after punctuation strip the whole
            // thing collapses to "gmailcredential". This is intentional: the suffix check
            // exists for the UI-provided form ("<Display> Credential"), not for arbitrary
            // naming schemes.
            assertThat(PlatformCredentialService.normalizeIntegrationName("Gmail_credential"))
                    .isEqualTo("gmailcredential");
        }

        @Test
        @DisplayName("'-Credential' suffix with hyphen is NOT stripped either")
        void normalize_hyphenCredentialSuffixNotStripped() {
            assertThat(PlatformCredentialService.normalizeIntegrationName("Gmail-Credential"))
                    .isEqualTo("gmailcredential");
        }

        @Test
        @DisplayName("empty string → empty string")
        void normalize_emptyString() {
            assertThat(PlatformCredentialService.normalizeIntegrationName(""))
                    .isEqualTo("");
        }
    }

    // ========== getRawCredential ==========

    @Nested
    @DisplayName("getRawCredential")
    class GetRawCredentialTests {

        @Test
        @DisplayName("should return credential when found and enabled")
        void getRawCredential_found() {
            PlatformCredential cred = buildCredential("test", "cid", "csec", null, true);
            when(repository.findByIntegrationName("test")).thenReturn(Optional.of(cred));

            Optional<PlatformCredential> result = service.getRawCredential("test");

            assertThat(result).isPresent();
        }

        @Test
        @DisplayName("should return empty when disabled")
        void getRawCredential_disabled() {
            PlatformCredential cred = buildCredential("test", "cid", "csec", null, false);
            when(repository.findByIntegrationName("test")).thenReturn(Optional.of(cred));

            Optional<PlatformCredential> result = service.getRawCredential("test");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty when not found")
        void getRawCredential_notFound() {
            when(repository.findByIntegrationName("test")).thenReturn(Optional.empty());

            Optional<PlatformCredential> result = service.getRawCredential("test");

            assertThat(result).isEmpty();
        }
    }

    // ========== setVariantEnabled (Phase 2d - admin per-variant toggle) ==========

    @Nested
    @DisplayName("setVariantEnabled")
    class SetVariantEnabledTests {

        @Test
        @DisplayName("platform-wide: normalizes name and delegates to repository.setEnabledForVariant(name, variant, enabled)")
        void setVariantEnabled_platformWide_delegates() {
            when(repository.setEnabledForVariant("gmail", "oauth2", false)).thenReturn(true);

            boolean result = service.setVariantEnabled("Gmail Credential", "oauth2", false);

            assertThat(result).isTrue();
            verify(repository).setEnabledForVariant("gmail", "oauth2", false);
            verify(repository, never()).setEnabledForVariant(anyString(), anyString(), anyBoolean(), anyString());
        }

        @Test
        @DisplayName("platform-wide: returns false when repository reports no row affected")
        void setVariantEnabled_platformWide_notFound() {
            when(repository.setEnabledForVariant("gmail", "api_key", true)).thenReturn(false);

            boolean result = service.setVariantEnabled("gmail", "api_key", true);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("tenant-scoped: routes to tenant-aware repository method when tenantId present")
        void setVariantEnabled_tenantScoped_routesToTenantQuery() {
            when(repository.setEnabledForVariant("gmail", "oauth2", false, "tenant-A")).thenReturn(true);

            boolean result = service.setVariantEnabled("Gmail", "oauth2", false, "tenant-A");

            assertThat(result).isTrue();
            verify(repository).setEnabledForVariant("gmail", "oauth2", false, "tenant-A");
            verify(repository, never()).setEnabledForVariant(anyString(), anyString(), anyBoolean());
        }

        @Test
        @DisplayName("tenant-scoped: null tenantId falls back to platform-wide query")
        void setVariantEnabled_nullTenant_fallsBackToPlatformWide() {
            when(repository.setEnabledForVariant("slack", "bearer_token", true)).thenReturn(true);

            boolean result = service.setVariantEnabled("slack", "bearer_token", true, null);

            assertThat(result).isTrue();
            verify(repository).setEnabledForVariant("slack", "bearer_token", true);
            verify(repository, never()).setEnabledForVariant(anyString(), anyString(), anyBoolean(), anyString());
        }

        @Test
        @DisplayName("normalizes integration name the same way as all other credential operations")
        void setVariantEnabled_normalizesName() {
            when(repository.setEnabledForVariant("googledrive", "oauth2", false)).thenReturn(true);

            service.setVariantEnabled("Google_Drive", "oauth2", false);

            verify(repository).setEnabledForVariant("googledrive", "oauth2", false);
        }
    }

    // ========== findOwnedByTenant ==========

    @Nested
    @DisplayName("findOwnedByTenant")
    class FindOwnedByTenantTests {

        @Test
        @DisplayName("delegates to repository.findOwnedByTenant when tenant id is non-blank")
        void findOwnedByTenant_delegatesWhenTenantPresent() {
            PlatformCredential row = buildCredential("gmail", "cid", "csec", null, true);
            when(repository.findOwnedByTenant("tenant-123")).thenReturn(List.of(row));

            List<PlatformCredential> result = service.findOwnedByTenant("tenant-123");

            assertThat(result).containsExactly(row);
            verify(repository).findOwnedByTenant("tenant-123");
        }

        @Test
        @DisplayName("returns empty list (and never queries) when tenant id is null - defense in depth against missing X-User-ID")
        void findOwnedByTenant_nullTenantReturnsEmpty() {
            List<PlatformCredential> result = service.findOwnedByTenant(null);

            assertThat(result).isEmpty();
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("returns empty list (and never queries) when tenant id is blank")
        void findOwnedByTenant_blankTenantReturnsEmpty() {
            List<PlatformCredential> result = service.findOwnedByTenant("   ");

            assertThat(result).isEmpty();
            verifyNoInteractions(repository);
        }
    }

    // ========== MAX_BYOK_PER_TENANT cap on saveCredential(req, tenantId) ==========

    @Nested
    @DisplayName("MAX_BYOK_PER_TENANT cap (Part 2D)")
    class ByokCapTests {

        @Test
        @DisplayName("INSERT path throws TooManyByokAppsException when tenant is at the cap")
        void saveCredential_atCapThrows() {
            when(repository.findOwnedRow("newapi", "tenant-x", null)).thenReturn(Optional.empty());
            when(repository.countByTenantIdAndOrganizationId("tenant-x", null))
                    .thenReturn(PlatformCredentialService.MAX_BYOK_PER_TENANT);

            CreatePlatformCredentialRequest request = new CreatePlatformCredentialRequest(
                    "newapi", "New API", "oauth2",
                    "cid", "csec", null, null, null,
                    null, null, null, null, null, null, null, null, null);

            org.junit.jupiter.api.Assertions.assertThrows(TooManyByokAppsException.class,
                    () -> service.saveCredential(request, "tenant-x"));

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("INSERT path succeeds when tenant is one below the cap (boundary)")
        void saveCredential_belowCapInserts() {
            when(repository.findOwnedRow("newapi", "tenant-x", null)).thenReturn(Optional.empty());
            when(repository.countByTenantIdAndOrganizationId("tenant-x", null))
                    .thenReturn(PlatformCredentialService.MAX_BYOK_PER_TENANT - 1);
            when(repository.save(any())).thenAnswer(inv -> ((PlatformCredential) inv.getArgument(0)).withId(1L));
            when(repository.findEndpointsByCredentialId(1L)).thenReturn(List.of());

            CreatePlatformCredentialRequest request = new CreatePlatformCredentialRequest(
                    "newapi", "New API", "oauth2",
                    "cid", "csec", null, null, null,
                    null, null, null, null, null, null, null, null, null);

            service.saveCredential(request, "tenant-x");

            verify(repository).save(any());
        }

        @Test
        @DisplayName("UPDATE path bypasses cap - updating an existing row never grows the count")
        void saveCredential_updatePathBypassesCap() {
            PlatformCredential existing = buildCredential("gmail", "cid", "csec", null, true)
                    .withTenantId("tenant-x");
            when(repository.findOwnedRow("gmail", "tenant-x", null))
                    .thenReturn(Optional.of(existing));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(repository.findEndpointsByCredentialId(any())).thenReturn(List.of());

            CreatePlatformCredentialRequest request = new CreatePlatformCredentialRequest(
                    "gmail", "Gmail", "oauth2",
                    "newcid", "newcsec", null, null, null,
                    null, null, null, null, null, null, null, null, null);

            // Cap is at the limit - but it's an update, so countByTenantId must
            // never be queried. If the cap leaked into the update path, this
            // would throw.
            service.saveCredential(request, "tenant-x");

            verify(repository, never()).countByTenantIdAndOrganizationId(anyString(), any());
            verify(repository).save(any());
        }

        @Test
        @DisplayName("Admin platform-wide save (tenantId == null) bypasses the per-tenant cap")
        void saveCredential_platformWideBypassesCap() {
            when(repository.findByIntegrationName("newapi")).thenReturn(Optional.empty());
            when(repository.save(any())).thenAnswer(inv -> ((PlatformCredential) inv.getArgument(0)).withId(1L));
            when(repository.findEndpointsByCredentialId(1L)).thenReturn(List.of());

            CreatePlatformCredentialRequest request = new CreatePlatformCredentialRequest(
                    "newapi", "New API", "oauth2",
                    "cid", "csec", null, null, null,
                    null, null, null, null, null, null, null, null, null);

            service.saveCredential(request);

            verify(repository, never()).countByTenantIdAndOrganizationId(anyString(), any());
            verify(repository).save(any());
        }
    }

    // ========== V362 org scoping ==========

    @Nested
    @DisplayName("org scoping (V362)")
    class OrgScopingTests {

        @Test
        @DisplayName("tenant create tags the new row with the active workspace (org)")
        void saveCredential_tagsActiveWorkspace() {
            when(repository.findOwnedRow("airtable", "tenant-123", "org-9")).thenReturn(Optional.empty());
            when(repository.countByTenantIdAndOrganizationId("tenant-123", "org-9")).thenReturn(0);
            when(repository.save(any())).thenAnswer(inv -> ((PlatformCredential) inv.getArgument(0)).withId(7L));
            when(repository.findEndpointsByCredentialId(7L)).thenReturn(List.of());

            CreatePlatformCredentialRequest request = new CreatePlatformCredentialRequest(
                    "airtable", "Airtable", "oauth2",
                    "cid", "csec", null, null, null,
                    null, null, null, null, null, null, null, null, null);

            service.saveCredential(request, "tenant-123", "org-9");

            ArgumentCaptor<PlatformCredential> captor = ArgumentCaptor.forClass(PlatformCredential.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().organizationId()).isEqualTo("org-9");
            assertThat(captor.getValue().tenantId()).isEqualTo("tenant-123");
        }

        @Test
        @DisplayName("getRawOAuth2Credential(name, tenant, org) resolves via the org-aware finder")
        void getRawOAuth2Credential_orgAwareDelegation() {
            PlatformCredential row = buildCredential("airtable", "cid", "csec", null, true)
                    .withTenantId("tenant-1").withOrganizationId("org-1");
            when(repository.findOAuth2ByIntegrationName("airtable", "tenant-1", "org-1"))
                    .thenReturn(Optional.of(row));

            Optional<PlatformCredential> result = service.getRawOAuth2Credential("airtable", "tenant-1", "org-1");

            assertThat(result).isPresent();
            assertThat(result.get().organizationId()).isEqualTo("org-1");
            verify(repository).findOAuth2ByIntegrationName("airtable", "tenant-1", "org-1");
        }

        @Test
        @DisplayName("findOwnedByTenant(tenant, org) delegates to the org-aware repository finder")
        void findOwnedByTenant_orgAwareDelegation() {
            when(repository.findOwnedByTenant("tenant-1", "org-1")).thenReturn(List.of());

            service.findOwnedByTenant("tenant-1", "org-1");

            verify(repository).findOwnedByTenant("tenant-1", "org-1");
            verify(repository, never()).findOwnedByTenant(anyString());
        }

        @Test
        @DisplayName("deleteCredential(name, tenant, org) delegates to the scoped repository delete")
        void deleteCredential_orgAwareDelegation() {
            when(repository.deleteByIntegrationName("airtable", "tenant-1", "org-1")).thenReturn(true);

            boolean deleted = service.deleteCredential("airtable", "tenant-1", "org-1");

            assertThat(deleted).isTrue();
            verify(repository).deleteByIntegrationName("airtable", "tenant-1", "org-1");
        }
    }

    // ========== Helpers ==========

    private PlatformCredential buildCredential(
            String integrationName, String clientId, String clientSecret,
            String apiKey, boolean isEnabled
    ) {
        return buildCredential(integrationName, clientId, clientSecret, apiKey, isEnabled, true);
    }

    private PlatformCredential buildCredential(
            String integrationName, String clientId, String clientSecret,
            String apiKey, boolean isEnabled, boolean showUnverifiedAppWarning
    ) {
        return new PlatformCredential(
                1L, integrationName, "Display " + integrationName, AuthType.OAUTH2,
                clientId, clientSecret, apiKey, null, null,
                "https://auth.url", "https://token.url", "scope",
                "icon", "category", "Description", showUnverifiedAppWarning, isEnabled,
                Map.of(),
                java.math.BigDecimal.ZERO,
                500,
                Instant.now(), Instant.now(), "admin", null, PlatformCredential.DEFAULT_VARIANT
        );
    }
}
