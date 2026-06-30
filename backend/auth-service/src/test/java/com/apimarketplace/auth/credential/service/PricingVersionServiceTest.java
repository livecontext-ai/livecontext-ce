package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.PlatformCredentialPricingVersion;
import com.apimarketplace.auth.credential.domain.PricingVersionEntry;
import com.apimarketplace.auth.credential.repository.PlatformCredentialPricingVersionRepository;
import com.apimarketplace.auth.credential.repository.PricingVersionEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PricingVersionService Tests")
class PricingVersionServiceTest {

    @Mock PlatformCredentialPricingVersionRepository versionRepo;
    @Mock PricingVersionEntryRepository entryRepo;

    private PricingVersionService service;
    private MarkupPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new MarkupPolicy();
        service = new PricingVersionService(versionRepo, entryRepo, policy);
    }

    @Test
    @DisplayName("resolveMarkup: unknown version → empty (stale pin safety)")
    void resolveMarkupStaleVersion() {
        when(versionRepo.findById(42L)).thenReturn(Optional.empty());

        Optional<BigDecimal> rate = service.resolveMarkup(42L, UUID.randomUUID());

        assertThat(rate).isEmpty();
        verifyNoInteractions(entryRepo);
    }

    @Test
    @DisplayName("resolveMarkup: falls back to version default when no per-tool entry")
    void resolveMarkupFallsBackToDefault() {
        PlatformCredentialPricingVersion v = version(7L, 1, new BigDecimal("0.30"));
        when(versionRepo.findById(7L)).thenReturn(Optional.of(v));
        UUID tool = UUID.randomUUID();
        when(entryRepo.findByPricingVersionIdAndApiToolId(7L, tool)).thenReturn(Optional.empty());

        Optional<BigDecimal> rate = service.resolveMarkup(7L, tool);

        assertThat(rate).hasValueSatisfying(r -> assertThat(r).isEqualByComparingTo("0.30"));
    }

    @Test
    @DisplayName("resolveMarkup: per-tool override wins over version default")
    void resolveMarkupPerToolWins() {
        PlatformCredentialPricingVersion v = version(7L, 1, new BigDecimal("0.30"));
        when(versionRepo.findById(7L)).thenReturn(Optional.of(v));
        UUID tool = UUID.randomUUID();
        PricingVersionEntry entry = new PricingVersionEntry();
        entry.setMarkupCredits(new BigDecimal("1.25"));
        when(entryRepo.findByPricingVersionIdAndApiToolId(7L, tool)).thenReturn(Optional.of(entry));

        Optional<BigDecimal> rate = service.resolveMarkup(7L, tool);

        assertThat(rate).hasValueSatisfying(r -> assertThat(r).isEqualByComparingTo("1.25"));
    }

    @Test
    @DisplayName("resolveFrozenMarkup: returns pricingVersionId, credentialId, version, rate")
    void resolveFrozenMarkupReturnsFullDescriptor() {
        PlatformCredentialPricingVersion v = version(9L, 3, new BigDecimal("0.40"));
        v.setPlatformCredentialId(123L);
        when(versionRepo.findById(9L)).thenReturn(Optional.of(v));
        UUID tool = UUID.randomUUID();
        when(entryRepo.findByPricingVersionIdAndApiToolId(9L, tool)).thenReturn(Optional.empty());

        Optional<PricingVersionService.FrozenMarkup> frozen = service.resolveFrozenMarkup(9L, tool);

        assertThat(frozen).hasValueSatisfying(f -> {
            assertThat(f.pricingVersionId()).isEqualTo(9L);
            assertThat(f.credentialId()).isEqualTo(123L);
            assertThat(f.version()).isEqualTo(3);
            assertThat(f.effectiveMarkup()).isEqualByComparingTo("0.40");
        });
    }

    private static PlatformCredentialPricingVersion version(long id, int versionNo, BigDecimal def) {
        PlatformCredentialPricingVersion v = new PlatformCredentialPricingVersion();
        v.setId(id);
        v.setVersion(versionNo);
        v.setDefaultMarkupCredits(def);
        return v;
    }
}
