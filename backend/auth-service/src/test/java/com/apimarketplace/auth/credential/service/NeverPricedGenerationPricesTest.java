package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.AuthType;
import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.PlatformCredential;
import com.apimarketplace.auth.credential.domain.PlatformCredentialPricingVersion;
import com.apimarketplace.auth.credential.domain.PriceSource;
import com.apimarketplace.auth.credential.domain.PriceSpec;
import com.apimarketplace.auth.credential.domain.PricingVersionEntry;
import com.apimarketplace.auth.credential.repository.PlatformCredentialPricingVersionRepository;
import com.apimarketplace.auth.credential.repository.PlatformCredentialRepository;
import com.apimarketplace.auth.credential.repository.PricingVersionEntryRepository;
import com.apimarketplace.auth.credential.repository.WorkflowRunPricingPinRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The cloud gap: a generation model its platform credential has never priced is
 * refused on the platform key, so the catalog's starting price must be published
 * for it, and for nothing else.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PlatformCredentialPricingService.addNeverPricedPrices - add what was never priced, touch nothing else")
class NeverPricedGenerationPricesTest {

    @Mock private JdbcTemplate jdbc;
    @Mock private PlatformCredentialPricingVersionRepository versionRepo;
    @Mock private PricingVersionEntryRepository entryRepo;
    @Mock private PlatformCredentialRepository credentialRepo;
    @Mock private WorkflowRunPricingPinRepository pinRepo;

    private PlatformCredentialPricingService service;

    private static final Long CRED_ID = 10L;
    private static final Long LIVE_VERSION_ID = 500L;
    private static final Long NEW_VERSION_ID = 501L;
    private static final UUID VIDEO_TOOL = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID AUDIO_TOOL = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    @BeforeEach
    void setUp() {
        service = new PlatformCredentialPricingService(
                jdbc, versionRepo, entryRepo, credentialRepo, pinRepo, new MarkupPolicy());
        when(credentialRepo.findById(CRED_ID)).thenReturn(Optional.of(new PlatformCredential(
                CRED_ID, "elevenlabs", "ElevenLabs", AuthType.API_KEY,
                null, null, null, null, null, null, null, null, null, null, null,
                true, null, BigDecimal.ZERO, 100, null, null, null, null)));
        when(versionRepo.save(any(PlatformCredentialPricingVersion.class))).thenAnswer(inv -> {
            PlatformCredentialPricingVersion v = inv.getArgument(0);
            v.setId(NEW_VERSION_ID);
            return v;
        });
        when(entryRepo.findEverPublishedKeys(CRED_ID)).thenReturn(List.of());
    }

    private static PlatformCredentialPricingVersion liveVersion(String defaultMarkup) {
        PlatformCredentialPricingVersion v = new PlatformCredentialPricingVersion();
        v.setId(LIVE_VERSION_ID);
        v.setPlatformCredentialId(CRED_ID);
        v.setVersion(3);
        v.setDefaultMarkupCredits(defaultMarkup == null ? null : new BigDecimal(defaultMarkup));
        return v;
    }

    private static PricingVersionEntry row(UUID tool, String modelId, String perUnit) {
        PricingVersionEntry e = new PricingVersionEntry();
        e.setPricingVersionId(LIVE_VERSION_ID);
        e.setApiToolId(tool);
        e.setModelId(modelId);
        e.setPriceUnit("character");
        e.setMarkupCredits(BigDecimal.ZERO.setScale(6));
        e.setUnitCredits(new BigDecimal(perUnit).setScale(6));
        e.setSource(PriceSource.ADMIN.wire());
        return e;
    }

    private static PriceSpec catalogPrice(UUID tool, String modelId, String perUnit) {
        return new PriceSpec(tool, modelId, "character", BigDecimal.ZERO, new BigDecimal(perUnit), null, null);
    }

    private void live(String defaultMarkup, PricingVersionEntry... rows) {
        when(versionRepo.findLatest(CRED_ID)).thenReturn(Optional.of(liveVersion(defaultMarkup)));
        when(versionRepo.findMaxVersion(CRED_ID)).thenReturn(3);
        when(entryRepo.findByPricingVersionId(LIVE_VERSION_ID)).thenReturn(List.of(rows));
        List<Object[]> keys = new ArrayList<>();
        for (PricingVersionEntry r : rows) keys.add(new Object[]{r.getApiToolId(), r.getModelId()});
        when(entryRepo.findEverPublishedKeys(CRED_ID)).thenReturn(keys);
    }

    @SuppressWarnings("unchecked")
    private List<PricingVersionEntry> publishedRows() {
        ArgumentCaptor<List<PricingVersionEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(entryRepo).saveAll(captor.capture());
        return captor.getValue();
    }

    private static PricingVersionEntry find(List<PricingVersionEntry> rows, UUID tool, String modelId) {
        return rows.stream()
                .filter(r -> tool.equals(r.getApiToolId()) && Objects.equals(modelId, r.getModelId()))
                .findFirst().orElseThrow(() -> new AssertionError("no row for " + tool + "/" + modelId));
    }

    @Test
    @DisplayName("A model added to an already-priced provider is published, and the live prices ride along unchanged")
    void modelAddedAfterFirstVersionIsPriced() {
        // The reported gap: the importer's bootstrap stops at the first version, so the new
        // model had no price and every platform-key call to it was refused.
        live("0.10", row(AUDIO_TOOL, "eleven_multilingual_v2", "0.3"));

        var result = service.addNeverPricedPrices(CRED_ID, List.of(
                catalogPrice(AUDIO_TOOL, "eleven_multilingual_v2", "9.9"),
                catalogPrice(AUDIO_TOOL, "eleven_v3", "0.5")), "catalog-starting-price");

        assertThat(result.published()).isTrue();
        assertThat(result.applied()).isEqualTo(1);
        assertThat(result.preserved()).isEqualTo(1);
        List<PricingVersionEntry> rows = publishedRows();
        assertThat(rows).hasSize(2);
        assertThat(find(rows, AUDIO_TOOL, "eleven_v3").getUnitCredits()).isEqualByComparingTo("0.5");
        // The live price is the owner's decision: the catalog's 9.9 must not replace it.
        assertThat(find(rows, AUDIO_TOOL, "eleven_multilingual_v2").getUnitCredits()).isEqualByComparingTo("0.3");
        assertThat(find(rows, AUDIO_TOOL, "eleven_v3").origin()).isEqualTo(PriceSource.ADMIN);
    }

    @Test
    @DisplayName("The version-wide default is carried forward, never reset by the gap fill")
    void defaultMarkupIsCarried() {
        live("0.10", row(AUDIO_TOOL, "eleven_multilingual_v2", "0.3"));

        service.addNeverPricedPrices(CRED_ID, List.of(catalogPrice(VIDEO_TOOL, "new-video", "60")), "x");

        ArgumentCaptor<PlatformCredentialPricingVersion> v = ArgumentCaptor.forClass(PlatformCredentialPricingVersion.class);
        verify(versionRepo).save(v.capture());
        assertThat(v.getValue().getDefaultMarkupCredits()).isEqualByComparingTo("0.10");
        assertThat(v.getValue().getVersion()).isEqualTo(4);
    }

    @Test
    @DisplayName("A key created after the import (never priced at all) gets v1 with the catalog's prices")
    void keyCreatedAfterImportGetsV1() {
        when(versionRepo.findLatest(CRED_ID)).thenReturn(Optional.empty());
        when(versionRepo.findMaxVersion(CRED_ID)).thenReturn(null);

        var result = service.addNeverPricedPrices(CRED_ID, List.of(
                catalogPrice(AUDIO_TOOL, "eleven_v3", "0.5"),
                catalogPrice(VIDEO_TOOL, "seedance-2.0", "60")), "catalog-starting-price");

        assertThat(result.published()).isTrue();
        assertThat(result.version()).isEqualTo(1);
        assertThat(publishedRows()).hasSize(2);
    }

    @Test
    @DisplayName("A price an administrator removed stays removed: it existed in an earlier version")
    void removedPriceIsNotResurrected() {
        live("0.10", row(AUDIO_TOOL, "eleven_multilingual_v2", "0.3"));
        // eleven_v3 was priced in v2 and removed in v3: it appears in the history, not in the live rows.
        List<Object[]> history = new ArrayList<>();
        history.add(new Object[]{AUDIO_TOOL, "eleven_multilingual_v2"});
        history.add(new Object[]{AUDIO_TOOL, "eleven_v3"});
        when(entryRepo.findEverPublishedKeys(CRED_ID)).thenReturn(history);

        var result = service.addNeverPricedPrices(CRED_ID,
                List.of(catalogPrice(AUDIO_TOOL, "eleven_v3", "0.5")), "catalog-starting-price");

        assertThat(result.published()).isFalse();
        verify(versionRepo, never()).save(any());
    }

    @Test
    @DisplayName("An endpoint priced as a WHOLE (flat row, no model) is decided for every model, new ones included")
    void endpointWidePriceIsNotOverriddenByModelRows() {
        // A model row takes precedence over the endpoint-wide row, so adding one would silently
        // replace the administrator's flat rate for that model.
        live("0.10", row(VIDEO_TOOL, null, "40"));

        var result = service.addNeverPricedPrices(CRED_ID, List.of(
                catalogPrice(VIDEO_TOOL, "brand-new-video", "60"),
                catalogPrice(AUDIO_TOOL, "eleven_v3", "0.5")), "catalog-starting-price");

        assertThat(result.applied()).isEqualTo(1);
        List<PricingVersionEntry> rows = publishedRows();
        assertThat(rows).extracting(PricingVersionEntry::getModelId)
                .containsExactlyInAnyOrder(null, "eleven_v3");
        assertThat(find(rows, VIDEO_TOOL, null).getUnitCredits()).isEqualByComparingTo("40");
    }

    @Test
    @DisplayName("Nothing missing publishes nothing, so re-offering the catalog every tick mints no version")
    void nothingMissingIsANoOp() {
        live("0.10", row(AUDIO_TOOL, "eleven_v3", "0.5"));

        var result = service.addNeverPricedPrices(CRED_ID,
                List.of(catalogPrice(AUDIO_TOOL, "eleven_v3", "0.5")), "catalog-starting-price");

        assertThat(result.published()).isFalse();
        assertThat(result.preserved()).isEqualTo(1);
        verify(entryRepo, never()).saveAll(any());
    }

    @Test
    @DisplayName("The same model offered twice in one call is published once, never as a duplicate row")
    void duplicateOfferIsPublishedOnce() {
        when(versionRepo.findLatest(CRED_ID)).thenReturn(Optional.empty());
        when(versionRepo.findMaxVersion(CRED_ID)).thenReturn(null);

        service.addNeverPricedPrices(CRED_ID, List.of(
                catalogPrice(AUDIO_TOOL, "eleven_v3", "0.5"),
                catalogPrice(AUDIO_TOOL, "eleven_v3", "0.5")), "x");

        assertThat(publishedRows()).hasSize(1);
    }

    @Test
    @DisplayName("An empty offer does not even take the lock")
    void emptyOfferIsFree() {
        var result = service.addNeverPricedPrices(CRED_ID, List.of(), "x");

        assertThat(result.published()).isFalse();
        verify(entryRepo, never()).findEverPublishedKeys(any());
    }
}
