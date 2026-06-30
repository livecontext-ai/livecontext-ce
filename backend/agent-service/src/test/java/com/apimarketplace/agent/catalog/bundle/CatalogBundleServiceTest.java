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
    @DisplayName("Stored checksum mismatch after live edit → refuses to serve")
    void staleBundleRejected() {
        // Build with one model…
        when(modelRepo.findAllByOrderByRankingAsc())
                .thenReturn(List.of(m("openai", "gpt-5", "GPT-5")));
        when(bundleRepo.findTopByOrderByVersionDesc()).thenReturn(Optional.empty());
        when(bundleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        CatalogBundleEntity built = service.buildBundle();

        // …then an admin added a second model (live table drift)
        when(modelRepo.findAllByOrderByRankingAsc()).thenReturn(
                List.of(m("openai", "gpt-5", "GPT-5"),
                        m("anthropic", "claude-sonnet-4-6", "Sonnet")));
        when(bundleRepo.findFirstByActiveTrue()).thenReturn(Optional.of(built));

        assertThatThrownBy(() -> service.getActiveSignedBundle())
                .isInstanceOf(IllegalStateException.class)
                // Wording locks the actionable error contract: the message
                // must point the operator at the rebuild+activate sequence,
                // not just say "invalidated" without recourse.
                .hasMessageContaining("no longer servable")
                .hasMessageContaining("rebuild + activate")
                .hasMessageContaining("V156/V157");
    }
}
