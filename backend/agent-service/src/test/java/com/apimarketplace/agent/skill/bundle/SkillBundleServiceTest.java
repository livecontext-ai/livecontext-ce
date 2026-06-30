package com.apimarketplace.agent.skill.bundle;

import com.apimarketplace.agent.catalog.bundle.CatalogBundleSigner;
import com.apimarketplace.agent.domain.SkillBundleEntity;
import com.apimarketplace.agent.domain.SkillEntity;
import com.apimarketplace.agent.repository.SkillBundleRepository;
import com.apimarketplace.agent.repository.SkillRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Cloud-side publisher contract: refuse to build without a signing key or with no global
 * skills, sign + persist an inactive row on build, flip active on activate, and re-derive +
 * checksum-guard the served bundle.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkillBundleService - build / activate / serve")
class SkillBundleServiceTest {

    @Mock private SkillBundleRepository bundleRepository;
    @Mock private SkillRepository skillRepository;
    @Mock private CatalogBundleSigner signer;

    private SkillBundleService service;

    @BeforeEach
    void setUp() {
        service = new SkillBundleService(bundleRepository, skillRepository, signer);
    }

    private SkillEntity global(String name) {
        SkillEntity s = new SkillEntity("admin", name, "d", "i", "instr", true);
        s.setId(UUID.randomUUID());
        s.setIsGlobal(true);
        s.setIsDefaultActive(true);
        return s;
    }

    @Test
    @DisplayName("buildBundle refuses when no signing key is configured")
    void buildRefusesWithoutKey() {
        when(signer.canSign()).thenReturn(false);

        assertThatThrownBy(() -> service.buildBundle())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    @DisplayName("buildBundle refuses when there are no global skills (would be an empty bundle)")
    void buildRefusesWithoutGlobalSkills() {
        when(signer.canSign()).thenReturn(true);
        when(skillRepository.findByIsGlobalTrueOrderByCreatedAtAsc()).thenReturn(List.of());

        assertThatThrownBy(() -> service.buildBundle())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no global skills");
    }

    @Test
    @DisplayName("buildBundle signs the snapshot and persists an inactive row with skillCount")
    void buildSignsAndPersists() {
        when(signer.canSign()).thenReturn(true);
        when(signer.keyId()).thenReturn("key-id");
        when(signer.issuer()).thenReturn("issuer");
        when(signer.checksum(any())).thenReturn("the-checksum");
        when(signer.sign(any())).thenReturn("the-signature");
        when(skillRepository.findByIsGlobalTrueOrderByCreatedAtAsc())
                .thenReturn(List.of(global("A"), global("B")));
        when(bundleRepository.findTopByOrderByVersionDesc()).thenReturn(Optional.empty());
        when(bundleRepository.save(any(SkillBundleEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        SkillBundleEntity built = service.buildBundle();

        assertThat(built.getSkillCount()).isEqualTo(2);
        assertThat(built.getChecksum()).isEqualTo("the-checksum");
        assertThat(built.getSignature()).isEqualTo("the-signature");
        assertThat(built.isActive()).isFalse();
        assertThat(built.getSigningKeyId()).isEqualTo("key-id");
    }

    private SkillEntity globalWithInstructions(String name, String instructions) {
        SkillEntity s = new SkillEntity("admin", name, "d", "i", instructions, true);
        s.setId(UUID.randomUUID());
        s.setIsGlobal(true);
        s.setIsDefaultActive(true);
        return s;
    }

    @Test
    @DisplayName("buildBundle DROPS a global skill whose instructions is a bare number, ships only the good ones")
    void buildDropsNumericInstructionSkills() {
        when(signer.canSign()).thenReturn(true);
        when(signer.keyId()).thenReturn("key-id");
        when(signer.issuer()).thenReturn("issuer");
        when(signer.checksum(any())).thenReturn("c");
        when(signer.sign(any())).thenReturn("s");
        // "106735" is the data-corruption fingerprint (getString().toString() of a number).
        when(skillRepository.findByIsGlobalTrueOrderByCreatedAtAsc())
                .thenReturn(List.of(global("good"), globalWithInstructions("corrupted", "106735")));
        when(bundleRepository.findTopByOrderByVersionDesc()).thenReturn(Optional.empty());
        when(bundleRepository.save(any(SkillBundleEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        SkillBundleEntity built = service.buildBundle();

        assertThat(built.getSkillCount()).isEqualTo(1); // the numeric-instruction skill never ships
    }

    @Test
    @DisplayName("buildBundle refuses when EVERY global skill has blank/numeric instructions (all corrupted)")
    void buildRefusesWhenAllInstructionsNumeric() {
        when(signer.canSign()).thenReturn(true);
        when(skillRepository.findByIsGlobalTrueOrderByCreatedAtAsc())
                .thenReturn(List.of(globalWithInstructions("a", "542605"), globalWithInstructions("b", "   ")));

        assertThatThrownBy(() -> service.buildBundle())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corrupted");
    }

    @Test
    @DisplayName("build THEN serve a partially-corrupted set stays SERVABLE - serve filters identically to build (no checksum drift)")
    void buildAndServeWithCorruptedSkillStaysServable() {
        when(signer.canSign()).thenReturn(true);
        when(signer.keyId()).thenReturn("key-id");
        when(signer.issuer()).thenReturn("issuer");
        // checksum reflects the canonical payload LENGTH, so a filtered vs unfiltered re-snapshot
        // would diverge. Pre-fix (serve did not filter) this drift made the bundle unservable.
        when(signer.checksum(any())).thenAnswer(inv -> "ck" + ((byte[]) inv.getArgument(0)).length);
        when(signer.sign(any())).thenReturn("s");
        when(skillRepository.findByIsGlobalTrueOrderByCreatedAtAsc())
                .thenReturn(List.of(global("good"), globalWithInstructions("corrupted", "106735")));
        when(bundleRepository.findTopByOrderByVersionDesc()).thenReturn(Optional.empty());
        when(bundleRepository.save(any(SkillBundleEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        SkillBundleEntity built = service.buildBundle();
        when(bundleRepository.findFirstByActiveTrue()).thenReturn(Optional.of(built));

        Optional<SignedSkillBundle> served = service.getActiveSignedBundle();

        assertThat(served).isPresent(); // pre-fix: serve re-snapshot unfiltered -> checksum drift -> throws
        assertThat(served.get().skillCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("buildBundle retries with a bumped version when two pods race the same version (unique-index collision)")
    void buildRetriesOnVersionCollision() {
        when(signer.canSign()).thenReturn(true);
        when(signer.keyId()).thenReturn("key-id");
        when(signer.issuer()).thenReturn("issuer");
        when(signer.checksum(any())).thenReturn("c");
        when(signer.sign(any())).thenReturn("s");
        when(skillRepository.findByIsGlobalTrueOrderByCreatedAtAsc()).thenReturn(List.of(global("A")));
        when(bundleRepository.findTopByOrderByVersionDesc()).thenReturn(Optional.empty());
        // First save loses the version race; second wins.
        when(bundleRepository.save(any(SkillBundleEntity.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("dup version"))
                .thenAnswer(inv -> inv.getArgument(0));

        SkillBundleEntity built = service.buildBundle();

        assertThat(built).isNotNull();
        org.mockito.Mockito.verify(bundleRepository, org.mockito.Mockito.times(2))
                .save(any(SkillBundleEntity.class));
    }

    @Test
    @DisplayName("buildBundle gives up with IllegalStateException after exhausting the version-collision retry budget")
    void buildThrowsAfterMaxAttempts() {
        when(signer.canSign()).thenReturn(true);
        when(signer.keyId()).thenReturn("key-id");
        when(signer.issuer()).thenReturn("issuer");
        when(signer.checksum(any())).thenReturn("c");
        when(signer.sign(any())).thenReturn("s");
        when(skillRepository.findByIsGlobalTrueOrderByCreatedAtAsc()).thenReturn(List.of(global("A")));
        when(bundleRepository.findTopByOrderByVersionDesc()).thenReturn(Optional.empty());
        when(bundleRepository.save(any(SkillBundleEntity.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("dup version"));

        assertThatThrownBy(() -> service.buildBundle())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version-collision retries");
    }

    @Test
    @DisplayName("activateBundle deactivates others and flips the target active")
    void activateFlips() {
        SkillBundleEntity target = new SkillBundleEntity();
        target.setVersion(10L);
        target.setActive(false);
        when(bundleRepository.findById(3L)).thenReturn(Optional.of(target));
        when(bundleRepository.deactivateAll()).thenReturn(1);
        when(bundleRepository.save(any(SkillBundleEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        SkillBundleEntity activated = service.activateBundle(3L);

        assertThat(activated.isActive()).isTrue();
        assertThat(activated.getActivatedAt()).isNotNull();
    }

    @Test
    @DisplayName("getActiveSignedBundle re-derives the payload and serves it when the fresh checksum matches the stored one")
    void serveWhenChecksumMatches() {
        SkillEntity g = global("A");
        SkillBundleEntity active = activeEntity(42L, "matching-checksum");
        when(bundleRepository.findFirstByActiveTrue()).thenReturn(Optional.of(active));
        when(skillRepository.findByIsGlobalTrueOrderByCreatedAtAsc()).thenReturn(List.of(g));
        when(signer.checksum(any())).thenReturn("matching-checksum");

        Optional<SignedSkillBundle> served = service.getActiveSignedBundle();

        assertThat(served).isPresent();
        assertThat(served.get().version()).isEqualTo(42L);
        assertThat(served.get().checksum()).isEqualTo("matching-checksum");
        assertThat(served.get().payloadBase64()).isNotBlank();
    }

    @Test
    @DisplayName("getActiveSignedBundle refuses to serve when the global skills changed since build (fresh checksum diverges)")
    void refuseServeOnChecksumDrift() {
        SkillBundleEntity active = activeEntity(42L, "stored-checksum");
        when(bundleRepository.findFirstByActiveTrue()).thenReturn(Optional.of(active));
        when(skillRepository.findByIsGlobalTrueOrderByCreatedAtAsc()).thenReturn(List.of(global("edited")));
        when(signer.checksum(any())).thenReturn("different-now");

        assertThatThrownBy(() -> service.getActiveSignedBundle())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer servable");
    }

    private SkillBundleEntity activeEntity(long version, String checksum) {
        SkillBundleEntity e = new SkillBundleEntity();
        e.setVersion(version);
        e.setSchemaVersion(1);
        e.setChecksum(checksum);
        e.setSignature("sig");
        e.setSigningKeyId("key-id");
        e.setIssuer("issuer");
        e.setSkillCount(1);
        e.setRawBytesSize(50);
        e.setImportedAt(java.time.Instant.parse("2026-06-29T10:00:00Z"));
        e.setActive(true);
        lenient().when(signer.issuer()).thenReturn("issuer");
        return e;
    }
}
