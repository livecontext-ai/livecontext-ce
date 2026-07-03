package com.apimarketplace.agent.catalog.bundle;

import com.apimarketplace.agent.domain.CatalogBundleEntity;
import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import com.apimarketplace.agent.repository.CatalogBundleRepository;
import com.apimarketplace.agent.repository.ModelConfigOverrideRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit contract for {@link CatalogBundleService}: build → activate →
 * serve. The tests use a real {@link CatalogBundleSigner} with an ephemeral
 * Ed25519 keypair so the checksum re-verification path (which compares the
 * stored checksum against a fresh SHA-256 of the re-serialised catalog) is
 * exercised for real.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogBundleService - build/activate/serve")
class CatalogBundleServiceTest {

    @Mock private CatalogBundleRepository bundleRepo;
    @Mock private ModelConfigOverrideRepository modelRepo;
    @Mock private com.apimarketplace.agent.repository.ModelCategorySettingsRepository categoryRepo;

    private CatalogBundleSigner signer;
    private CatalogBundleService service;

    @BeforeEach
    void setUp() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String priv = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
        String pub  = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());

        signer = new CatalogBundleSigner(priv, pub, "test-key", "test-cloud");
        service = new CatalogBundleService(bundleRepo, modelRepo, categoryRepo, signer);
        // V156 sidecar empty by default - tests opt in by stubbing categoryRepo.findAll().
        org.mockito.Mockito.lenient().when(categoryRepo.findAll())
                .thenReturn(java.util.List.of());
    }

    private ModelConfigOverrideEntity m(String provider, String modelId, String name) {
        ModelConfigOverrideEntity e = new ModelConfigOverrideEntity();
        e.setProvider(provider);
        e.setModelId(modelId);
        e.setDisplayName(name);
        e.setPriceInput(new BigDecimal("1.25"));
        e.setPriceOutput(new BigDecimal("10.00"));
        return e;
    }

    @Test
    @DisplayName("buildBundle persists a signed inactive row with correct metadata")
    void buildBundlePersistsSigned() {
        when(modelRepo.findAllByOrderByRankingAsc())
                .thenReturn(List.of(m("openai", "gpt-5", "GPT-5"), m("anthropic", "claude-sonnet-4-6", "Sonnet")));
        when(bundleRepo.findTopByOrderByVersionDesc()).thenReturn(Optional.empty());
        when(bundleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CatalogBundleEntity saved = service.buildBundle();

        assertThat(saved.isActive()).isFalse();
        assertThat(saved.getModelCount()).isEqualTo(2);
        assertThat(saved.getChecksum()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(saved.getSignature()).isNotBlank();
        assertThat(saved.getSigningKeyId()).isEqualTo("test-key");
        assertThat(saved.getIssuer()).isEqualTo("test-cloud");
        assertThat(saved.getVersion()).isPositive();
        assertThat(saved.getRawBytesSize()).isPositive();
    }

    @Test
    @DisplayName("buildBundle rejects empty catalog - refuses to publish")
    void buildBundleRejectsEmptyCatalog() {
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(List.of());

        assertThatThrownBy(() -> service.buildBundle())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("buildBundle throws if signing key not configured")
    void buildBundleRequiresKey() {
        CatalogBundleSigner noKey = new CatalogBundleSigner("", "", "k", "i");
        CatalogBundleService svc = new CatalogBundleService(bundleRepo, modelRepo, categoryRepo, noKey);

        assertThatThrownBy(svc::buildBundle)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CATALOG_BUNDLE_SIGNING_KEY_PEM");
    }

    @Test
    @DisplayName("Version monotonically increases even if clock goes backwards")
    void versionMonotonic() {
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(List.of(m("openai", "gpt-5", "x")));
        CatalogBundleEntity prior = new CatalogBundleEntity();
        // Set a version far in the future so clock can't beat it
        prior.setVersion(System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000);
        when(bundleRepo.findTopByOrderByVersionDesc()).thenReturn(Optional.of(prior));
        when(bundleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CatalogBundleEntity saved = service.buildBundle();

        assertThat(saved.getVersion()).isGreaterThan(prior.getVersion());
    }

    @Test
    @DisplayName("activateBundle deactivates others, flips this one active")
    void activateDeactivatesOthers() {
        CatalogBundleEntity target = new CatalogBundleEntity();
        target.setId(7L);
        target.setActive(false);
        when(bundleRepo.findById(7L)).thenReturn(Optional.of(target));
        when(bundleRepo.deactivateAll()).thenReturn(1);
        when(bundleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CatalogBundleEntity activated = service.activateBundle(7L);

        verify(bundleRepo).deactivateAll();
        assertThat(activated.isActive()).isTrue();
        assertThat(activated.getActivatedAt()).isNotNull();
    }

    @Test
    @DisplayName("activateBundle on already-active is a no-op (no deactivate call)")
    void activateOnAlreadyActive() {
        CatalogBundleEntity bundle = new CatalogBundleEntity();
        bundle.setId(3L);
        bundle.setActive(true);
        when(bundleRepo.findById(3L)).thenReturn(Optional.of(bundle));

        CatalogBundleEntity out = service.activateBundle(3L);

        verify(bundleRepo, never()).deactivateAll();
        verify(bundleRepo, never()).save(any());
        assertThat(out.isActive()).isTrue();
    }

    @Test
    @DisplayName("activateBundle with unknown id → IllegalArgumentException")
    void activateUnknown() {
        when(bundleRepo.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.activateBundle(99L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("getActiveSignedBundle re-signs and returns base64 payload matching stored checksum")
    void serveActiveBundle() {
        // Build first to get a consistent checksum
        when(modelRepo.findAllByOrderByRankingAsc())
                .thenReturn(List.of(m("openai", "gpt-5", "GPT-5")));
        when(bundleRepo.findTopByOrderByVersionDesc()).thenReturn(Optional.empty());
        ArgumentCaptor<CatalogBundleEntity> saveCaptor = ArgumentCaptor.forClass(CatalogBundleEntity.class);
        when(bundleRepo.save(saveCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        CatalogBundleEntity built = service.buildBundle();

        // Now serve it
        when(bundleRepo.findFirstByActiveTrue()).thenReturn(Optional.of(built));
        Optional<SignedBundle> served = service.getActiveSignedBundle();

        assertThat(served).isPresent();
        SignedBundle sb = served.get();
        assertThat(sb.version()).isEqualTo(built.getVersion());
        assertThat(sb.checksum()).isEqualTo(built.getChecksum());
        assertThat(sb.signature()).isEqualTo(built.getSignature());
        assertThat(sb.payloadBase64()).isNotBlank();

        byte[] decoded = Base64.getDecoder().decode(sb.payloadBase64());
        assertThat(signer.verify(decoded, sb.signature())).isTrue();
        assertThat(signer.checksum(decoded)).isEqualTo(sb.checksum());
    }

    @Test
    @DisplayName("getActiveSignedBundle returns empty when no active bundle")
    void noActiveBundle() {
        when(bundleRepo.findFirstByActiveTrue()).thenReturn(Optional.empty());
        assertThat(service.getActiveSignedBundle()).isEmpty();
    }

    @Test
    @DisplayName("Version collision (horizontal-scaling race) retries with bumped version and eventually succeeds")
    void buildBundleRetriesOnVersionCollision() {
        when(modelRepo.findAllByOrderByRankingAsc())
                .thenReturn(List.of(m("openai", "gpt-5", "GPT-5")));
        when(bundleRepo.findTopByOrderByVersionDesc()).thenReturn(Optional.empty());
        // First save collides on UNIQUE(version), second succeeds. This is
        // what happens when two pods pick the same millisecond as their
        // version.
        when(bundleRepo.save(any()))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"catalog_bundles_version_key\""))
                .thenAnswer(inv -> inv.getArgument(0));

        CatalogBundleEntity saved = service.buildBundle();

        // Saved exactly twice - one rejected, one succeeded.
        verify(bundleRepo, times(2)).save(any());
        assertThat(saved).isNotNull();
        assertThat(saved.getVersion()).isPositive();
    }

    @Test
    @DisplayName("Version collision retries are bounded - persistent collisions surface as IllegalStateException")
    void buildBundleGivesUpAfterMaxRetries() {
        when(modelRepo.findAllByOrderByRankingAsc())
                .thenReturn(List.of(m("openai", "gpt-5", "GPT-5")));
        when(bundleRepo.findTopByOrderByVersionDesc()).thenReturn(Optional.empty());
        // Every save collides - stuck forever would be worse than failing loud.
        when(bundleRepo.save(any()))
                .thenThrow(new DataIntegrityViolationException("unique violation"));

        assertThatThrownBy(service::buildBundle)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retries");
        verify(bundleRepo, times(CatalogBundleService.BUILD_MAX_ATTEMPTS)).save(any());
    }

    @Test
    @DisplayName("importedAt is truncated to microseconds (Postgres TIMESTAMPTZ precision)")
    void importedAtTruncatedToMicros() {
        when(modelRepo.findAllByOrderByRankingAsc())
                .thenReturn(List.of(m("openai", "gpt-5", "GPT-5")));
        when(bundleRepo.findTopByOrderByVersionDesc()).thenReturn(Optional.empty());
        when(bundleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CatalogBundleEntity saved = service.buildBundle();

        // Nanos-of-second must be a whole number of microseconds - otherwise a
        // round-trip through Postgres TIMESTAMPTZ silently drops precision and
        // the fresh checksum on re-serve won't match the stored one.
        assertThat(saved.getImportedAt().getNano() % 1_000).isZero();
    }

    @Test
    @DisplayName("V381: a live edit no longer unserves the bundle - it flags it STALE for auto-rebuild")
    void liveEditMakesBundleStaleNotUnservable() {
        // Pre-V381 this scenario THREW at serve time (every download 409'd until a
        // manual rebuild). The contract flipped: serving keeps working from the
        // stored payload, and the drift is reported through isActiveBundleStale()
        // so the auto-rebuild scheduler republishes.
        when(modelRepo.findAllByOrderByRankingAsc())
                .thenReturn(List.of(m("openai", "gpt-5", "GPT-5")));
        when(bundleRepo.findTopByOrderByVersionDesc()).thenReturn(Optional.empty());
        when(bundleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        CatalogBundleEntity built = service.buildBundle();
        built.setActive(true);

        // …then an admin added a second model (live table drift)
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(
                List.of(m("openai", "gpt-5", "GPT-5"),
                        m("anthropic", "claude-sonnet-4-6", "Sonnet")));
        when(bundleRepo.findFirstByActiveTrue()).thenReturn(Optional.of(built));

        assertThat(service.getActiveSignedBundle())
                .as("serving must keep working from the persisted payload")
                .isPresent();
        assertThat(service.isActiveBundleStale())
                .as("the drift is surfaced to the auto-rebuild scheduler instead")
                .isTrue();
    }

    // ==================== V381: payload persistence ====================

    @Test
    @DisplayName("V381: buildBundle persists the exact signed payload on the row")
    void buildBundlePersistsPayload() {
        when(modelRepo.findAllByOrderByRankingAsc())
                .thenReturn(List.of(m("openai", "gpt-5", "GPT-5")));
        when(bundleRepo.findTopByOrderByVersionDesc()).thenReturn(Optional.empty());
        when(bundleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CatalogBundleEntity saved = service.buildBundle();

        assertThat(saved.getPayload()).isNotBlank();
        byte[] bytes = saved.getPayload().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(signer.checksum(bytes))
                .as("the stored payload must hash to the signed checksum")
                .isEqualTo(saved.getChecksum());
        assertThat(bytes.length).isEqualTo(saved.getRawBytesSize());
    }

    @Test
    @DisplayName("V381 regression: a catalog edit AFTER build no longer unserves the bundle")
    void serveSurvivesCatalogEditAfterBuild() {
        // Build against catalog state A...
        when(modelRepo.findAllByOrderByRankingAsc())
                .thenReturn(List.of(m("openai", "gpt-5", "GPT-5")));
        when(bundleRepo.findTopByOrderByVersionDesc()).thenReturn(Optional.empty());
        when(bundleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        CatalogBundleEntity built = service.buildBundle();
        built.setActive(true);

        // ...then the catalog is EDITED (the pre-fix code re-derived the payload
        // from this new state at read time -> checksum mismatch -> 409 for every
        // download, historical versions included). lenient(): the WHOLE POINT is
        // that the serve path never consults the live table any more.
        org.mockito.Mockito.lenient().when(modelRepo.findAllByOrderByRankingAsc())
                .thenReturn(List.of(m("openai", "gpt-5", "GPT-5 RENAMED"), m("anthropic", "claude-fable-5", "Fable")));
        when(bundleRepo.findFirstByActiveTrue()).thenReturn(Optional.of(built));

        Optional<SignedBundle> served = service.getActiveSignedBundle();

        assertThat(served).as("the bundle must keep serving from its stored payload").isPresent();
        assertThat(new String(Base64.getDecoder().decode(served.get().payloadBase64()),
                java.nio.charset.StandardCharsets.UTF_8))
                .as("served bytes are the BUILD-TIME snapshot, not the edited table")
                .isEqualTo(built.getPayload())
                .doesNotContain("RENAMED");
    }

    @Test
    @DisplayName("V381: legacy row (null payload) refuses with the CE-facing republishing message")
    void legacyNullPayloadGivesCeFacingMessage() {
        CatalogBundleEntity legacy = new CatalogBundleEntity();
        legacy.setVersion(123L);
        legacy.setChecksum("0".repeat(64));
        legacy.setActive(true);
        when(bundleRepo.findFirstByActiveTrue()).thenReturn(Optional.of(legacy));

        assertThatThrownBy(() -> service.getActiveSignedBundle())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("republishing")
                .hasMessageContaining("no action needed")
                // The old operator text must be gone from the CE-visible message.
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("POST /api/model-config"));
    }

    @Test
    @DisplayName("V381: corrupted stored payload (checksum mismatch) is refused")
    void corruptedStoredPayloadRefused() {
        CatalogBundleEntity row = new CatalogBundleEntity();
        row.setVersion(124L);
        row.setPayload("{tampered}");
        row.setChecksum("0".repeat(64));
        row.setActive(true);
        when(bundleRepo.findFirstByActiveTrue()).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.getActiveSignedBundle())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("republishing");
    }

    // ==================== V381: staleness probe ====================

    @Test
    @DisplayName("isActiveBundleStale: fresh build over unchanged catalog is NOT stale; an edit makes it stale")
    void stalenessTracksCatalogEdits() {
        when(modelRepo.findAllByOrderByRankingAsc())
                .thenReturn(List.of(m("openai", "gpt-5", "GPT-5")));
        when(bundleRepo.findTopByOrderByVersionDesc()).thenReturn(Optional.empty());
        when(bundleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        CatalogBundleEntity built = service.buildBundle();
        built.setActive(true);
        when(bundleRepo.findFirstByActiveTrue()).thenReturn(Optional.of(built));

        assertThat(service.isActiveBundleStale()).isFalse();

        when(modelRepo.findAllByOrderByRankingAsc())
                .thenReturn(List.of(m("openai", "gpt-5", "GPT-5 EDITED")));
        assertThat(service.isActiveBundleStale()).isTrue();
    }

    @Test
    @DisplayName("isActiveBundleStale: legacy null-payload active row is stale; no active bundle is not")
    void stalenessLegacyAndNoActive() {
        when(bundleRepo.findFirstByActiveTrue()).thenReturn(Optional.empty());
        assertThat(service.isActiveBundleStale())
                .as("first activation is an admin decision, never automated")
                .isFalse();

        CatalogBundleEntity legacy = new CatalogBundleEntity();
        legacy.setVersion(1L);
        legacy.setActive(true);
        when(bundleRepo.findFirstByActiveTrue()).thenReturn(Optional.of(legacy));
        assertThat(service.isActiveBundleStale())
                .as("a pre-V381 active row is unservable and must be replaced")
                .isTrue();
    }

    // ==================== V381: delete + retention ====================

    @Test
    @DisplayName("deleteBundle: refuses the active bundle, 404s unknown ids, deletes inactive rows")
    void deleteBundleGuards() {
        CatalogBundleEntity active = new CatalogBundleEntity();
        active.setVersion(9L);
        active.setActive(true);
        when(bundleRepo.findById(1L)).thenReturn(Optional.of(active));
        assertThatThrownBy(() -> service.deleteBundle(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACTIVE");

        when(bundleRepo.findById(2L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deleteBundle(2L))
                .isInstanceOf(IllegalArgumentException.class);

        CatalogBundleEntity inactive = new CatalogBundleEntity();
        inactive.setVersion(8L);
        when(bundleRepo.findById(3L)).thenReturn(Optional.of(inactive));
        service.deleteBundle(3L);
        org.mockito.Mockito.verify(bundleRepo).delete(inactive);
    }

    @Test
    @DisplayName("pruneInactiveBundles keeps the N most recent and deletes the rest")
    void pruneKeepsMostRecent() {
        List<CatalogBundleEntity> inactive = new java.util.ArrayList<>();
        for (int i = 25; i >= 1; i--) { // newest-first, as the repo method returns
            CatalogBundleEntity e = new CatalogBundleEntity();
            e.setVersion((long) i);
            inactive.add(e);
        }
        when(bundleRepo.findByActiveFalseOrderByVersionDesc()).thenReturn(inactive);

        int pruned = service.pruneInactiveBundles(20);

        assertThat(pruned).isEqualTo(5);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<CatalogBundleEntity>> captor =
                org.mockito.ArgumentCaptor.forClass((Class) List.class);
        org.mockito.Mockito.verify(bundleRepo).deleteAll(captor.capture());
        assertThat(captor.getValue()).hasSize(5);
        assertThat(captor.getValue()).extracting(CatalogBundleEntity::getVersion)
                .as("the OLDEST versions are pruned")
                .containsExactly(5L, 4L, 3L, 2L, 1L);
    }

    @Test
    @DisplayName("pruneInactiveBundles is a no-op at or under the retention limit")
    void pruneNoOpUnderLimit() {
        when(bundleRepo.findByActiveFalseOrderByVersionDesc())
                .thenReturn(List.of(new CatalogBundleEntity()));
        assertThat(service.pruneInactiveBundles(20)).isZero();
        org.mockito.Mockito.verify(bundleRepo, org.mockito.Mockito.never()).deleteAll(any());
    }
}
