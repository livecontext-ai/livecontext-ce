package com.apimarketplace.catalog.service.generation;

import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.BundleGenerationPriceDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("NeverPricedGenerationModelPublisher - every generation model is offered its starting price, whatever its kind")
class NeverPricedGenerationModelPublisherTest {

    private final GenerationRegistry registry = mock(GenerationRegistry.class);
    private final CredentialClient credentialClient = mock(CredentialClient.class);
    private final NeverPricedGenerationModelPublisher publisher =
            new NeverPricedGenerationModelPublisher(registry, credentialClient);

    private static GenerationRegistry.GenerationModel model(String id, String kind, String credential,
                                                            GenerationSpec.Price price) {
        GenerationSpec.Model m = new GenerationSpec.Model(id, id, id, Set.of(), Set.of(), Map.of(), price);
        return new GenerationRegistry.GenerationModel(id, kind, m, null, UUID.randomUUID(),
                "api/tool", "api", "Api", "icon", credential, "sync");
    }

    private static GenerationSpec.Price perUnit(String unit, String rate) {
        return new GenerationSpec.Price(unit, BigDecimal.ZERO, new BigDecimal(rate), null, new BigDecimal("5000"));
    }

    @Test
    @DisplayName("Video, audio, voice, music and image models are all offered, keyed by their platform credential")
    void everyKindIsOffered() {
        // The gap was not kind-specific: a model of ANY kind added after the first price version
        // had no price. So the offer must not filter by kind either.
        when(registry.list(null)).thenReturn(List.of(
                model("seedance-2.0", "video", "seedance", perUnit("second", "60")),
                model("eleven_v3", "voice", "elevenlabs", perUnit("character", "0.5")),
                model("music-v1", "music", "elevenlabs", perUnit("second", "2")),
                model("sfx-v1", "audio", "elevenlabs", perUnit("call", "10")),
                model("flux-pro", "image", "flux", perUnit("image", "8"))));
        when(credentialClient.addNeverPricedGenerationPrices(anyList(), any()))
                .thenReturn(Optional.of(Map.of("publishedCredentials", 1)));

        publisher.publishOnce();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BundleGenerationPriceDto>> rows = ArgumentCaptor.forClass(List.class);
        verify(credentialClient).addNeverPricedGenerationPrices(rows.capture(),
                eq(NeverPricedGenerationModelPublisher.ORIGIN));
        assertThat(rows.getValue()).extracting(BundleGenerationPriceDto::modelId)
                .containsExactlyInAnyOrder("seedance-2.0", "eleven_v3", "music-v1", "sfx-v1", "flux-pro");
        BundleGenerationPriceDto voice = rows.getValue().stream()
                .filter(r -> r.modelId().equals("eleven_v3")).findFirst().orElseThrow();
        assertThat(voice.integrationName()).isEqualTo("elevenlabs");
        assertThat(voice.priceUnit()).isEqualTo("character");
        assertThat(voice.unitCredits()).isEqualByComparingTo("0.5");
        assertThat(voice.maxCredits()).isEqualByComparingTo("5000");
    }

    @Test
    @DisplayName("A model with no platform credential, or no declared price, is never offered (and never priced free)")
    void unsellableModelsAreSkipped() {
        when(registry.list(null)).thenReturn(List.of(
                model("byok-only", "video", null, perUnit("second", "60")),
                model("no-price", "audio", "elevenlabs", null)));

        publisher.publishOnce();

        verify(credentialClient, never()).addNeverPricedGenerationPrices(anyList(), any());
    }

    @Test
    @DisplayName("An unreachable auth-service is a missed tick, never an exception out of the scheduler")
    void authDownIsSwallowed() {
        when(registry.list(null)).thenReturn(List.of(model("seedance-2.0", "video", "seedance", perUnit("second", "60"))));
        when(credentialClient.addNeverPricedGenerationPrices(anyList(), any())).thenReturn(Optional.empty());

        assertThat(publisher.publishOnce()).isEmpty();

        when(registry.list(null)).thenThrow(new IllegalStateException("db down"));
        publisher.tick();
    }
}
