package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.AuthType;
import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.PlatformCredential;
import com.apimarketplace.auth.credential.domain.PlatformCredentialPricingVersion;
import com.apimarketplace.auth.credential.domain.PricingVersionEntry;
import com.apimarketplace.auth.credential.domain.WorkflowRunPricingPin;
import com.apimarketplace.auth.credential.repository.PlatformCredentialPricingVersionRepository;
import com.apimarketplace.auth.credential.repository.PlatformCredentialRepository;
import com.apimarketplace.auth.credential.repository.PricingVersionEntryRepository;
import com.apimarketplace.auth.credential.repository.WorkflowRunPricingPinRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PlatformCredentialPricingService - publishing, pinning, and cancelling markup pricing versions")
class PlatformCredentialPricingServiceTest {

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private PlatformCredentialPricingVersionRepository versionRepo;
    @Mock
    private PricingVersionEntryRepository entryRepo;
    @Mock
    private PlatformCredentialRepository credentialRepo;
    @Mock
    private WorkflowRunPricingPinRepository pinRepo;

    private PlatformCredentialPricingService service;

    private static final Long CRED_ID = 10L;
    private static final Long PRICING_VERSION_ID = 777L;
    private static final String CREATED_BY = "admin@example.com";

    @BeforeEach
    void setUp() {
        // Use a real MarkupPolicy - it's a pure component with no collaborators.
        MarkupPolicy policy = new MarkupPolicy();
        service = new PlatformCredentialPricingService(
                jdbc, versionRepo, entryRepo, credentialRepo, pinRepo, policy);
    }

    // ========== Helpers ==========

    /**
     * Build a PlatformCredential test fixture. Fields in the record order:
     * id, integrationName, displayName, authType, clientId, clientSecret,
     * apiKey, username, password, authUrl, tokenUrl, defaultScopes, iconSlug,
     * category, description, isEnabled, customFields, defaultMarkupCredits,
     * maxCallsPerRun, createdAt, updatedAt, createdBy.
     */
    private PlatformCredential credential(AuthType authType, Integer maxCalls) {
        return new PlatformCredential(
                CRED_ID,
                "test-integration",
                "Test Integration",
                authType,
                null, null, null, null, null,
                null, null, null,
                null, null, null,
                true,
                null,
                BigDecimal.ZERO,
                maxCalls,
                null, null, null, null
        );
    }

    private PlatformCredentialPricingVersion savedVersion(int version) {
        PlatformCredentialPricingVersion v = new PlatformCredentialPricingVersion();
        v.setId(PRICING_VERSION_ID);
        v.setPlatformCredentialId(CRED_ID);
        v.setVersion(version);
        v.setDefaultMarkupCredits(new BigDecimal("0.10"));
        return v;
    }

    private WorkflowRunPricingPin livePin(String runId, Long userId) {
        WorkflowRunPricingPin p = new WorkflowRunPricingPin();
        p.setRunId(runId);
        p.setUserId(userId);
        p.setPlatformCredentialId(CRED_ID);
        p.setPricingVersionId(PRICING_VERSION_ID);
        p.setCancelled(false);
        return p;
    }

    // ========== publishNextVersion ==========

    @Nested
    @DisplayName("publishNextVersion")
    class PublishNextVersion {

        @Test
        @DisplayName("first version starts at 1 when no prior versions exist")
        void firstVersionStartsAtOne() {
            // Arrange
            PlatformCredential cred = credential(AuthType.API_KEY, 100);
            when(credentialRepo.findById(CRED_ID)).thenReturn(Optional.of(cred));
            when(versionRepo.findMaxVersion(CRED_ID)).thenReturn(null);
            when(versionRepo.save(any(PlatformCredentialPricingVersion.class)))
                    .thenAnswer(inv -> {
                        PlatformCredentialPricingVersion v = inv.getArgument(0);
                        v.setId(PRICING_VERSION_ID);
                        return v;
                    });

            // Act
            PlatformCredentialPricingVersion result = service.publishNextVersion(
                    CRED_ID, new BigDecimal("0.25"), Collections.emptyMap(), CREATED_BY);

            // Assert
            assertThat(result.getVersion()).isEqualTo(1);
            verify(jdbc).execute(contains("pg_advisory_xact_lock"));
            ArgumentCaptor<PlatformCredentialPricingVersion> captor =
                    ArgumentCaptor.forClass(PlatformCredentialPricingVersion.class);
            verify(versionRepo, times(1)).save(captor.capture());
            assertThat(captor.getValue().getVersion()).isEqualTo(1);
            assertThat(captor.getValue().getPlatformCredentialId()).isEqualTo(CRED_ID);
            assertThat(captor.getValue().getDefaultMarkupCredits())
                    .isEqualByComparingTo(new BigDecimal("0.25"));
            assertThat(captor.getValue().getCreatedBy()).isEqualTo(CREATED_BY);
        }

        @Test
        @DisplayName("increments version from the current maximum")
        void incrementsFromMax() {
            // Arrange
            PlatformCredential cred = credential(AuthType.API_KEY, 100);
            when(credentialRepo.findById(CRED_ID)).thenReturn(Optional.of(cred));
            when(versionRepo.findMaxVersion(CRED_ID)).thenReturn(3);
            when(versionRepo.save(any(PlatformCredentialPricingVersion.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act
            PlatformCredentialPricingVersion result = service.publishNextVersion(
                    CRED_ID, new BigDecimal("0.10"), null, CREATED_BY);

            // Assert
            assertThat(result.getVersion()).isEqualTo(4);
            ArgumentCaptor<PlatformCredentialPricingVersion> captor =
                    ArgumentCaptor.forClass(PlatformCredentialPricingVersion.class);
            verify(versionRepo).save(captor.capture());
            assertThat(captor.getValue().getVersion()).isEqualTo(4);
        }

        @Test
        @DisplayName("persists per-tool override entries bound to the new pricing-version id")
        void savesPerToolOverrides() {
            // Arrange
            PlatformCredential cred = credential(AuthType.API_KEY, 100);
            when(credentialRepo.findById(CRED_ID)).thenReturn(Optional.of(cred));
            when(versionRepo.findMaxVersion(CRED_ID)).thenReturn(0);
            when(versionRepo.save(any(PlatformCredentialPricingVersion.class)))
                    .thenAnswer(inv -> {
                        PlatformCredentialPricingVersion v = inv.getArgument(0);
                        v.setId(PRICING_VERSION_ID);
                        return v;
                    });

            UUID toolA = UUID.randomUUID();
            UUID toolB = UUID.randomUUID();
            Map<UUID, BigDecimal> overrides = new HashMap<>();
            overrides.put(toolA, new BigDecimal("0.01"));
            overrides.put(toolB, new BigDecimal("0.02"));

            // Act
            service.publishNextVersion(CRED_ID, new BigDecimal("0.10"), overrides, CREATED_BY);

            // Assert
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<PricingVersionEntry>> captor = ArgumentCaptor.forClass(List.class);
            verify(entryRepo).saveAll(captor.capture());
            List<PricingVersionEntry> saved = captor.getValue();
            assertThat(saved).hasSize(2);
            assertThat(saved).allSatisfy(entry ->
                    assertThat(entry.getPricingVersionId()).isEqualTo(PRICING_VERSION_ID));
            assertThat(saved).extracting(PricingVersionEntry::getApiToolId)
                    .containsExactlyInAnyOrder(toolA, toolB);
        }

        @Test
        @DisplayName("rejects OAuth2 credential with non-zero markup and never saves a version")
        void rejectsOAuth2WithMarkup() {
            // Arrange
            PlatformCredential cred = credential(AuthType.OAUTH2, 0);
            when(credentialRepo.findById(CRED_ID)).thenReturn(Optional.of(cred));

            // Act + Assert
            assertThatThrownBy(() -> service.publishNextVersion(
                    CRED_ID, new BigDecimal("0.50"), null, CREATED_BY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("OAuth2");

            verify(versionRepo, never()).save(any());
            verify(entryRepo, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("rejects unknown credentialId and never saves a version")
        void rejectsUnknownCredential() {
            // Arrange
            when(credentialRepo.findById(CRED_ID)).thenReturn(Optional.empty());

            // Act + Assert
            assertThatThrownBy(() -> service.publishNextVersion(
                    CRED_ID, new BigDecimal("0.10"), null, CREATED_BY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("credential not found");

            verify(versionRepo, never()).save(any());
            verify(entryRepo, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("rejects a negative per-tool override and never saves a version")
        void rejectsNegativePerToolOverride() {
            // Arrange
            PlatformCredential cred = credential(AuthType.API_KEY, 100);
            when(credentialRepo.findById(CRED_ID)).thenReturn(Optional.of(cred));

            UUID toolId = UUID.randomUUID();
            Map<UUID, BigDecimal> overrides = new HashMap<>();
            overrides.put(toolId, new BigDecimal("-0.01"));

            // Act + Assert
            assertThatThrownBy(() -> service.publishNextVersion(
                    CRED_ID, new BigDecimal("0.10"), overrides, CREATED_BY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("per-tool markup must be >= 0");

            verify(versionRepo, never()).save(any());
            verify(entryRepo, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("publishes with null default when at least one per-tool override is supplied")
        void publishesWithNullDefaultAndOverride() {
            // Arrange
            PlatformCredential cred = credential(AuthType.API_KEY, 100);
            when(credentialRepo.findById(CRED_ID)).thenReturn(Optional.of(cred));
            when(versionRepo.findMaxVersion(CRED_ID)).thenReturn(null);
            when(versionRepo.save(any(PlatformCredentialPricingVersion.class)))
                    .thenAnswer(inv -> {
                        PlatformCredentialPricingVersion v = inv.getArgument(0);
                        v.setId(PRICING_VERSION_ID);
                        return v;
                    });

            UUID toolId = UUID.randomUUID();
            Map<UUID, BigDecimal> overrides = new HashMap<>();
            overrides.put(toolId, new BigDecimal("0.25"));

            // Act - null default is legal so long as an override carries the pricing.
            PlatformCredentialPricingVersion result = service.publishNextVersion(
                    CRED_ID, null, overrides, CREATED_BY);

            // Assert: the persisted version has no default; the override was saved.
            ArgumentCaptor<PlatformCredentialPricingVersion> versionCaptor =
                    ArgumentCaptor.forClass(PlatformCredentialPricingVersion.class);
            verify(versionRepo).save(versionCaptor.capture());
            assertThat(versionCaptor.getValue().getDefaultMarkupCredits()).isNull();
            assertThat(result.getDefaultMarkupCredits()).isNull();
            verify(entryRepo).saveAll(anyList());
        }

        @Test
        @DisplayName("rejects a version that has neither a default nor any overrides")
        void rejectsNullDefaultWithEmptyOverrides() {
            // A version that bills zero for every tool is indistinguishable
            // from "not priced" - refuse it so the inspector toggle stays honest.
            PlatformCredential cred = credential(AuthType.API_KEY, 100);
            when(credentialRepo.findById(CRED_ID)).thenReturn(Optional.of(cred));

            assertThatThrownBy(() -> service.publishNextVersion(
                    CRED_ID, null, Collections.emptyMap(), CREATED_BY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("default markup or at least one per-tool override");

            verify(versionRepo, never()).save(any());
            verify(entryRepo, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("rejects a version with null default and null overrides map")
        void rejectsNullDefaultWithNullOverrides() {
            PlatformCredential cred = credential(AuthType.API_KEY, 100);
            when(credentialRepo.findById(CRED_ID)).thenReturn(Optional.of(cred));

            assertThatThrownBy(() -> service.publishNextVersion(
                    CRED_ID, null, null, CREATED_BY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("default markup or at least one per-tool override");
        }
    }

    // ========== resolveLatestMarkupForTool / hasAnyNonZeroMarkup ==========

    @Nested
    @DisplayName("resolveLatestMarkupForTool")
    class ResolveLatestMarkupForTool {

        @Test
        @DisplayName("returns empty when the credential has no published version")
        void returnsEmptyWhenNoVersion() {
            when(versionRepo.findLatest(CRED_ID)).thenReturn(Optional.empty());

            Optional<BigDecimal> resolved = service.resolveLatestMarkupForTool(
                    CRED_ID, UUID.randomUUID());

            assertThat(resolved).isEmpty();
            verifyNoInteractions(entryRepo);
        }

        @Test
        @DisplayName("returns the per-tool override when one exists on the latest version")
        void returnsOverrideWhenPresent() {
            UUID toolId = UUID.randomUUID();
            PlatformCredentialPricingVersion latest = new PlatformCredentialPricingVersion();
            latest.setId(PRICING_VERSION_ID);
            latest.setDefaultMarkupCredits(new BigDecimal("0.05"));
            when(versionRepo.findLatest(CRED_ID)).thenReturn(Optional.of(latest));

            PricingVersionEntry entry = new PricingVersionEntry();
            entry.setMarkupCredits(new BigDecimal("0.42"));
            when(entryRepo.findByPricingVersionIdAndApiToolId(PRICING_VERSION_ID, toolId))
                    .thenReturn(Optional.of(entry));

            Optional<BigDecimal> resolved = service.resolveLatestMarkupForTool(CRED_ID, toolId);

            assertThat(resolved).isPresent();
            assertThat(resolved.get()).isEqualByComparingTo("0.42");
        }

        @Test
        @DisplayName("falls back to the version default when there is no per-tool override")
        void fallsBackToDefault() {
            UUID toolId = UUID.randomUUID();
            PlatformCredentialPricingVersion latest = new PlatformCredentialPricingVersion();
            latest.setId(PRICING_VERSION_ID);
            latest.setDefaultMarkupCredits(new BigDecimal("0.05"));
            when(versionRepo.findLatest(CRED_ID)).thenReturn(Optional.of(latest));
            when(entryRepo.findByPricingVersionIdAndApiToolId(PRICING_VERSION_ID, toolId))
                    .thenReturn(Optional.empty());

            Optional<BigDecimal> resolved = service.resolveLatestMarkupForTool(CRED_ID, toolId);

            assertThat(resolved).isPresent();
            assertThat(resolved.get()).isEqualByComparingTo("0.05");
        }

        @Test
        @DisplayName("returns zero when the version has a null default and no override for this tool")
        void returnsZeroWhenNullDefaultAndNoOverride() {
            UUID toolId = UUID.randomUUID();
            PlatformCredentialPricingVersion latest = new PlatformCredentialPricingVersion();
            latest.setId(PRICING_VERSION_ID);
            latest.setDefaultMarkupCredits(null);
            when(versionRepo.findLatest(CRED_ID)).thenReturn(Optional.of(latest));
            when(entryRepo.findByPricingVersionIdAndApiToolId(PRICING_VERSION_ID, toolId))
                    .thenReturn(Optional.empty());

            Optional<BigDecimal> resolved = service.resolveLatestMarkupForTool(CRED_ID, toolId);

            assertThat(resolved).isPresent();
            assertThat(resolved.get()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("hasAnyNonZeroMarkup")
    class HasAnyNonZeroMarkup {

        @Test
        @DisplayName("returns false when no pricing version has been published")
        void falseWhenNoVersion() {
            when(versionRepo.findLatest(CRED_ID)).thenReturn(Optional.empty());

            assertThat(service.hasAnyNonZeroMarkup(CRED_ID)).isFalse();
        }

        @Test
        @DisplayName("returns true when the default markup is positive")
        void trueWhenDefaultIsPositive() {
            PlatformCredentialPricingVersion latest = new PlatformCredentialPricingVersion();
            latest.setId(PRICING_VERSION_ID);
            latest.setDefaultMarkupCredits(new BigDecimal("0.10"));
            when(versionRepo.findLatest(CRED_ID)).thenReturn(Optional.of(latest));

            assertThat(service.hasAnyNonZeroMarkup(CRED_ID)).isTrue();
        }

        @Test
        @DisplayName("returns true when only one per-tool override is positive")
        void trueWhenAnyOverridePositive() {
            PlatformCredentialPricingVersion latest = new PlatformCredentialPricingVersion();
            latest.setId(PRICING_VERSION_ID);
            latest.setDefaultMarkupCredits(null);
            when(versionRepo.findLatest(CRED_ID)).thenReturn(Optional.of(latest));

            PricingVersionEntry zero = new PricingVersionEntry();
            zero.setMarkupCredits(BigDecimal.ZERO);
            PricingVersionEntry positive = new PricingVersionEntry();
            positive.setMarkupCredits(new BigDecimal("0.05"));
            when(entryRepo.findByPricingVersionId(PRICING_VERSION_ID))
                    .thenReturn(List.of(zero, positive));

            assertThat(service.hasAnyNonZeroMarkup(CRED_ID)).isTrue();
        }

        @Test
        @DisplayName("returns false when default is null/zero and every override is zero")
        void falseWhenAllRatesAreZero() {
            PlatformCredentialPricingVersion latest = new PlatformCredentialPricingVersion();
            latest.setId(PRICING_VERSION_ID);
            latest.setDefaultMarkupCredits(BigDecimal.ZERO);
            when(versionRepo.findLatest(CRED_ID)).thenReturn(Optional.of(latest));

            PricingVersionEntry zero = new PricingVersionEntry();
            zero.setMarkupCredits(BigDecimal.ZERO);
            when(entryRepo.findByPricingVersionId(PRICING_VERSION_ID))
                    .thenReturn(List.of(zero));

            assertThat(service.hasAnyNonZeroMarkup(CRED_ID)).isFalse();
        }
    }

    // ========== cancelActivePinsForUser ==========

    @Nested
    @DisplayName("cancelActivePinsForUser")
    class CancelActivePinsForUser {

        @Test
        @DisplayName("cancels every distinct run for the user exactly once and returns the total")
        void cancelsAllDistinctRuns() {
            // Arrange - 3 live pins across 2 distinct runIds.
            Long userId = 42L;
            WorkflowRunPricingPin p1 = livePin("run-A", userId);
            WorkflowRunPricingPin p2 = livePin("run-A", userId);
            WorkflowRunPricingPin p3 = livePin("run-B", userId);
            when(pinRepo.findByUserIdAndCancelledFalse(userId))
                    .thenReturn(List.of(p1, p2, p3));
            when(pinRepo.cancelByRunId(eq("run-A"), any(Instant.class))).thenReturn(2);
            when(pinRepo.cancelByRunId(eq("run-B"), any(Instant.class))).thenReturn(1);

            // Act
            int total = service.cancelActivePinsForUser(userId);

            // Assert
            assertThat(total).isEqualTo(3);
            verify(pinRepo, times(1)).cancelByRunId(eq("run-A"), any(Instant.class));
            verify(pinRepo, times(1)).cancelByRunId(eq("run-B"), any(Instant.class));
        }

        @Test
        @DisplayName("returns 0 and issues no cancel calls when the user has no live pins")
        void returnsZeroWhenNoLivePins() {
            // Arrange
            Long userId = 42L;
            when(pinRepo.findByUserIdAndCancelledFalse(userId)).thenReturn(List.of());

            // Act
            int total = service.cancelActivePinsForUser(userId);

            // Assert
            assertThat(total).isZero();
            verify(pinRepo, never()).cancelByRunId(anyString(), any(Instant.class));
        }
    }

    // ========== savePin ==========

    @Nested
    @DisplayName("savePin")
    class SavePin {

        @Test
        @DisplayName("returns the existing pin unchanged when one already exists (idempotent)")
        void returnsExistingPin() {
            // Arrange
            WorkflowRunPricingPin existing = livePin("run-1", 1L);
            existing.setId(99L);
            when(pinRepo.findByRunIdAndPlatformCredentialId("run-1", CRED_ID))
                    .thenReturn(Optional.of(existing));

            // Act
            WorkflowRunPricingPin result = service.savePin(
                    "run-1", 1L, CRED_ID, PRICING_VERSION_ID);

            // Assert
            assertThat(result).isSameAs(existing);
            verify(pinRepo, never()).save(any(WorkflowRunPricingPin.class));
        }

        @Test
        @DisplayName("creates a new pin with the supplied run/user/credential/pricing version when none exists")
        void createsNewPin() {
            // Arrange
            when(pinRepo.findByRunIdAndPlatformCredentialId("run-2", CRED_ID))
                    .thenReturn(Optional.empty());
            when(pinRepo.save(any(WorkflowRunPricingPin.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.savePin("run-2", 5L, CRED_ID, PRICING_VERSION_ID);

            // Assert
            ArgumentCaptor<WorkflowRunPricingPin> captor =
                    ArgumentCaptor.forClass(WorkflowRunPricingPin.class);
            verify(pinRepo).save(captor.capture());
            WorkflowRunPricingPin saved = captor.getValue();
            assertThat(saved.getRunId()).isEqualTo("run-2");
            assertThat(saved.getUserId()).isEqualTo(5L);
            assertThat(saved.getPlatformCredentialId()).isEqualTo(CRED_ID);
            assertThat(saved.getPricingVersionId()).isEqualTo(PRICING_VERSION_ID);
        }

        @Test
        @DisplayName("race: concurrent inserter wins - second caller's save throws unique-constraint, we re-read and return the winning row (no lost pin)")
        void concurrentRaceReReadsWinner() {
            // Arrange - first findBy returns empty (both threads see empty),
            // our save loses the race and the unique constraint throws,
            // then the recovery findBy returns the winner's row.
            WorkflowRunPricingPin winner = livePin("run-race", 1L);
            winner.setId(101L);
            when(pinRepo.findByRunIdAndPlatformCredentialId("run-race", CRED_ID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(winner));
            when(pinRepo.save(any(WorkflowRunPricingPin.class)))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                            "uk_wrpp_run_cred"));

            // Act
            WorkflowRunPricingPin result = service.savePin(
                    "run-race", 1L, CRED_ID, PRICING_VERSION_ID);

            // Assert
            assertThat(result).isSameAs(winner);
            verify(pinRepo, times(2)).findByRunIdAndPlatformCredentialId("run-race", CRED_ID);
            verify(pinRepo).save(any(WorkflowRunPricingPin.class));
        }

        @Test
        @DisplayName("race recovery fails - if the winning row is gone on re-read, surface the integrity exception instead of silently losing the pin")
        void concurrentRaceSurfacesExceptionWhenReReadEmpty() {
            // Arrange - both findBy calls return empty, save throws.
            // This should not happen in practice (unique constraint implies
            // a winning row) but we must not return null or a phantom pin.
            when(pinRepo.findByRunIdAndPlatformCredentialId("run-race-2", CRED_ID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.empty());
            when(pinRepo.save(any(WorkflowRunPricingPin.class)))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException("boom"));

            // Act + Assert
            assertThatThrownBy(() -> service.savePin(
                    "run-race-2", 1L, CRED_ID, PRICING_VERSION_ID))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                    .hasMessageContaining("boom");
        }
    }

    // ========== cancelPinsForRun ==========

    @Nested
    @DisplayName("cancelPinsForRun")
    class CancelPinsForRun {

        @Test
        @DisplayName("delegates to repo.cancelByRunId with the current instant and returns its result")
        void delegatesToRepo() {
            // Arrange
            when(pinRepo.cancelByRunId(eq("run-9"), any(Instant.class))).thenReturn(4);

            // Act
            int result = service.cancelPinsForRun("run-9");

            // Assert
            assertThat(result).isEqualTo(4);
            verify(pinRepo).cancelByRunId(eq("run-9"), any(Instant.class));
        }
    }

    // ========== findAllVersions / findOverrides (admin-history read path) ==========

    @Nested
    @DisplayName("findAllVersions")
    class FindAllVersions {

        @Test
        @DisplayName("returns every version for the credential, newest-first, via repo ordering")
        void delegatesToRepoDesc() {
            PlatformCredentialPricingVersion v3 = savedVersion(3);
            PlatformCredentialPricingVersion v1 = savedVersion(1);
            when(versionRepo.findByPlatformCredentialIdOrderByVersionDesc(CRED_ID))
                    .thenReturn(List.of(v3, v1));

            List<PlatformCredentialPricingVersion> result = service.findAllVersions(CRED_ID);

            assertThat(result).extracting(PlatformCredentialPricingVersion::getVersion)
                    .containsExactly(3, 1);
        }
    }

    @Nested
    @DisplayName("findOverrides")
    class FindOverrides {

        @Test
        @DisplayName("returns an empty map when a version has no per-tool overrides")
        void emptyWhenNoEntries() {
            when(entryRepo.findByPricingVersionId(PRICING_VERSION_ID))
                    .thenReturn(Collections.emptyList());

            Map<UUID, BigDecimal> result = service.findOverrides(PRICING_VERSION_ID);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("maps each entry to {apiToolId -> markupCredits} preserving every row")
        void mapsEachEntry() {
            UUID toolA = UUID.randomUUID();
            UUID toolB = UUID.randomUUID();
            PricingVersionEntry a = new PricingVersionEntry();
            a.setPricingVersionId(PRICING_VERSION_ID);
            a.setApiToolId(toolA);
            a.setMarkupCredits(new BigDecimal("0.25"));
            PricingVersionEntry b = new PricingVersionEntry();
            b.setPricingVersionId(PRICING_VERSION_ID);
            b.setApiToolId(toolB);
            b.setMarkupCredits(new BigDecimal("0.05"));
            when(entryRepo.findByPricingVersionId(PRICING_VERSION_ID)).thenReturn(List.of(a, b));

            Map<UUID, BigDecimal> result = service.findOverrides(PRICING_VERSION_ID);

            assertThat(result).hasSize(2);
            assertThat(result.get(toolA)).isEqualByComparingTo("0.25");
            assertThat(result.get(toolB)).isEqualByComparingTo("0.05");
        }
    }
}
