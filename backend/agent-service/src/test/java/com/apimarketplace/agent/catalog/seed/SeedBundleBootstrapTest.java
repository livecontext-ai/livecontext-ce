package com.apimarketplace.agent.catalog.seed;

import com.apimarketplace.agent.catalog.bundle.CatalogBundleApplier;
import com.apimarketplace.agent.catalog.bundle.CatalogBundleVerifier;
import com.apimarketplace.agent.catalog.bundle.SignedBundle;
import com.apimarketplace.agent.domain.CatalogBundleSyncStatusEntity;
import com.apimarketplace.agent.repository.CatalogBundleSyncStatusRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Boot-time apply of the release-baked model seed bundle (V381). The critical
 * contracts: NOTHING can fail startup, unverified content is never applied,
 * and the version gate makes replays no-ops.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SeedBundleBootstrap")
class SeedBundleBootstrapTest {

    @Mock private CatalogBundleVerifier verifier;
    @Mock private CatalogBundleApplier applier;
    @Mock private CatalogBundleSyncStatusRepository syncStatusRepo;

    @TempDir
    Path tmp;

    private final ObjectMapper mapper = new ObjectMapper();

    private SeedBundleBootstrap bootstrap(String path) {
        return new SeedBundleBootstrap(verifier, applier, syncStatusRepo, mapper, path);
    }

    private Path writeSeed(long version) throws Exception {
        SignedBundle bundle = new SignedBundle(version, 2, "c".repeat(64), "sig", "key-1",
                "cloud", 3, 42, Base64.getEncoder().encodeToString("{\"models\":[]}".getBytes()));
        Path f = tmp.resolve("model-bundle.json");
        Files.write(f, mapper.writeValueAsBytes(bundle));
        return f;
    }

    @Test
    @DisplayName("missing file: silent no-op (normal on cloud and source checkouts)")
    void missingFileIsNoOp() {
        bootstrap(tmp.resolve("absent.json").toString()).applySeedBundle();

        verifyNoInteractions(verifier, applier);
    }

    @Test
    @DisplayName("version gate: a seed not newer than the last applied bundle is skipped")
    void versionGateSkipsOldSeed() throws Exception {
        Path f = writeSeed(100L);
        CatalogBundleSyncStatusEntity status = new CatalogBundleSyncStatusEntity();
        status.setLastAppliedVersion(100L);
        when(syncStatusRepo.findById(CatalogBundleSyncStatusEntity.SINGLETON_ID))
                .thenReturn(Optional.of(status));

        bootstrap(f.toString()).applySeedBundle();

        verifyNoInteractions(verifier, applier);
    }

    @Test
    @DisplayName("verification failure: the seed is IGNORED, never applied")
    void unverifiedSeedNeverApplied() throws Exception {
        Path f = writeSeed(200L);
        when(syncStatusRepo.findById(any())).thenReturn(Optional.empty());
        when(verifier.verify(any())).thenReturn(
                CatalogBundleVerifier.Result.fail(
                        CatalogBundleVerifier.Status.SIGNATURE_INVALID, "bad signature"));

        bootstrap(f.toString()).applySeedBundle();

        verifyNoInteractions(applier);
    }

    @Test
    @DisplayName("happy path: newer verified seed is applied through the standard applier")
    void newerVerifiedSeedApplied() throws Exception {
        Path f = writeSeed(300L);
        CatalogBundleSyncStatusEntity status = new CatalogBundleSyncStatusEntity();
        status.setLastAppliedVersion(100L);
        when(syncStatusRepo.findById(CatalogBundleSyncStatusEntity.SINGLETON_ID))
                .thenReturn(Optional.of(status));
        byte[] payload = "{\"models\":[]}".getBytes();
        when(verifier.verify(any())).thenReturn(CatalogBundleVerifier.Result.success(payload));
        when(applier.apply(any(), eq(payload), any())).thenReturn(
                CatalogBundleApplier.ApplyResult.alreadyApplied(300L));

        bootstrap(f.toString()).applySeedBundle();

        verify(applier).apply(any(SignedBundle.class), eq(payload), eq("seed:model-bundle.json"));
    }

    @Test
    @DisplayName("malformed file: logged and swallowed - startup must never break")
    void malformedFileSwallowed() throws Exception {
        Path f = tmp.resolve("model-bundle.json");
        Files.writeString(f, "not json at all {{{");

        bootstrap(f.toString()).applySeedBundle(); // must not throw

        verifyNoInteractions(applier);
    }
}
