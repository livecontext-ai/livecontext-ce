package com.apimarketplace.agent.service;

import com.apimarketplace.agent.credential.LlmCredentialRepository;
import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.ModelCategory;
import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.provider.LLMProvider;
import com.apimarketplace.agent.provider.TypeSafeDecisionProvider;
import com.apimarketplace.agent.repository.ModelCategorySettingsRepository;
import com.apimarketplace.agent.repository.ModelConfigOverrideRepository;
import com.apimarketplace.agent.streaming.StreamingCallback;
import com.apimarketplace.agent.streaming.StreamingEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * A decision model must never be offered where a conversation is expected.
 *
 * <p><b>Why this is tested here and not on {@code ModelCategory}.</b> Asserting
 * {@code acceptsMode("chat", "decision") == false} proves a rule, not the behaviour: the
 * catalog is assembled from database rows AND from the YAML-declared models the provider
 * factory emits, and those YAML rows used to carry no mode at all. The eligibility rule was
 * therefore asked about {@code null} whatever the database said, answered "chat-eligible",
 * and the model stayed in the picker - while a unit test of the rule passed. So these tests
 * drive the REAL {@code LLMProviderFactory} with a REAL {@code TypeSafeDecisionProvider} and
 * assert on what the catalog actually returns.
 *
 * <p>Four of the six fail on the pre-fix code, where {@code LLMProviderFactory} wrote no
 * {@code mode} onto a YAML model row: the three listing tests and the factory stamp.
 * Measured, not assumed. The other two pass either way and are here to pin intent rather
 * than to catch that regression: the classification listing worked already (the DATABASE
 * row carried the mode), and the default-model pick was saved by display order alone
 * until {@code LLMProviderFactory.canHoldAConversation} made it a rule.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModelCatalogService - decision models never reach a conversational surface")
class ModelCatalogServiceDecisionModeTest {

    @Mock private ModelConfigOverrideRepository repository;
    @Mock private ModelCategorySettingsRepository categoryRepository;
    @Mock private LlmCredentialRepository credentialRepository;
    @Mock private CachedModelRateLimitProvider cachedRateLimitProvider;
    @Mock private AuthPricingSyncClient authPricingSyncClient;

    private ModelCatalogService service;

    /** A configured decision provider, exactly as the YAML declares it. */
    private static final TypeSafeDecisionProvider JEV =
            new TypeSafeDecisionProvider(true, "ts-key", "jev-latest", 20);

    @BeforeEach
    void setUp() {
        LLMProviderFactory factory = new LLMProviderFactory(List.of(new FakeChatProvider(), JEV));
        service = new ModelCatalogService(repository, categoryRepository, factory,
                credentialRepository, cachedRateLimitProvider, "", authPricingSyncClient);
        lenient().when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of(decisionRow()));
        lenient().when(categoryRepository.findByCategory(any())).thenReturn(List.of());
        lenient().when(credentialRepository.hasDbKey(any())).thenReturn(true);
    }

    /** The catalog row V504 writes: mode='decision', no explicit enabled decision. */
    private static ModelConfigOverrideEntity decisionRow() {
        ModelConfigOverrideEntity row = new ModelConfigOverrideEntity();
        row.setId(1L);
        row.setProvider(TypeSafeDecisionProvider.PROVIDER_NAME);
        row.setModelId("jev-latest");
        row.setMode(ModelCategory.DECISION_MODE);
        row.setEnabled(null);
        return row;
    }

    @SuppressWarnings("unchecked")
    private List<String> modelIdsOf(Map<String, Object> catalog, String providerName) {
        List<Map<String, Object>> providers = (List<Map<String, Object>>) catalog.get("providers");
        if (providers == null) return List.of();
        return providers.stream()
                .filter(p -> providerName.equals(p.get("name")))
                .flatMap(p -> {
                    List<Map<String, Object>> models = (List<Map<String, Object>>) p.get("models");
                    return models == null ? java.util.stream.Stream.<Map<String, Object>>empty() : models.stream();
                })
                .map(m -> (String) m.get("id"))
                .toList();
    }

    @Test
    @DisplayName("the chat picker, which asks with no category, is offered no decision model")
    void globalPathOffersNoDecisionModel() {
        // category=null backs the main chat picker, the flat LLM catalog and the
        // default-model pick. It is the path that leaked.
        Map<String, Object> catalog = service.getModelsForCategory(null);

        assertThat(modelIdsOf(catalog, TypeSafeDecisionProvider.PROVIDER_NAME)).isEmpty();
        assertThat(modelIdsOf(catalog, "fake-chat")).contains("fake-chat-1");
    }

    @Test
    @DisplayName("the chat category is offered no decision model")
    void chatCategoryOffersNoDecisionModel() {
        Map<String, Object> catalog = service.getModelsForCategory("chat");

        assertThat(modelIdsOf(catalog, TypeSafeDecisionProvider.PROVIDER_NAME)).isEmpty();
    }

    @Test
    @DisplayName("the browser agent, which drives a real browser with prose, is offered no decision model")
    void browserAgentOffersNoDecisionModel() {
        Map<String, Object> catalog = service.getModelsForCategory("browser_agent");

        assertThat(modelIdsOf(catalog, TypeSafeDecisionProvider.PROVIDER_NAME)).isEmpty();
    }

    @Test
    @DisplayName("a decision model can never become the platform default model")
    void neverBecomesTheDefaultModel() {
        // Not merely unlikely: the default is picked from the same category-less list, so
        // a decision model surviving that filter is one display-order change away from
        // becoming every new agent's model.
        Map<String, Object> catalog = service.getModelsForCategory(null);

        assertThat((String) catalog.get("defaultProvider"))
                .isNotEqualTo(TypeSafeDecisionProvider.PROVIDER_NAME);
        assertThat((String) catalog.get("defaultModel")).isNotEqualTo("jev-latest");
    }

    @Test
    @DisplayName("the classification category offers the decision model, and only that")
    void classificationOffersTheDecisionModelAlone() {
        Map<String, Object> catalog = service.getModelsForCategory("classification");

        assertThat(modelIdsOf(catalog, TypeSafeDecisionProvider.PROVIDER_NAME))
                .containsExactly("jev-latest");
        // The mirror image of the leak: a chat model ranked in the classification tab
        // would be ranked against a model it cannot be compared with.
        assertThat(modelIdsOf(catalog, "fake-chat")).isEmpty();
    }

    @Test
    @DisplayName("the provider factory stamps the provider's mode onto every model row it emits")
    void factoryStampsTheProviderMode() {
        // The root cause, pinned directly: the filter above reads this key, and nothing
        // wrote it. A chat provider keeps reporting null, which is the legacy value.
        LLMProviderFactory factory = new LLMProviderFactory(List.of(new FakeChatProvider(), JEV));

        assertThat(modeOf(factory.getAllModelsInfo(), "typesafe"))
                .isEqualTo(ModelCategory.DECISION_MODE);
        assertThat(modeOf(factory.getAllModelsInfoAdmin(), "typesafe"))
                .isEqualTo(ModelCategory.DECISION_MODE);
        assertThat(modeOf(factory.getAllModelsInfo(), "fake-chat")).isNull();
        assertThat(modeOf(factory.getAllModelsInfoAdmin(), "fake-chat")).isNull();
    }

    /**
     * The mode stamped on the provider's first model row. Collected into a list rather than
     * read with {@code findFirst()}, which throws on a null element: a chat provider's mode
     * IS null, and that is the case this assertion exists to check.
     */
    @SuppressWarnings("unchecked")
    private String modeOf(Map<String, Object> info, String providerName) {
        List<Map<String, Object>> providers = (List<Map<String, Object>>) info.get("providers");
        List<Map<String, Object>> models = providers.stream()
                .filter(p -> providerName.equals(p.get("name")))
                .flatMap(p -> ((List<Map<String, Object>>) p.get("models")).stream())
                .toList();
        assertThat(models).as("provider %s must expose at least one model", providerName).isNotEmpty();
        assertThat(models.get(0)).as("every model row carries a mode key, even when null")
                .containsKey("mode");
        return (String) models.get(0).get("mode");
    }

    /** A minimal chat provider: the control case that must keep behaving exactly as before. */
    private static class FakeChatProvider implements LLMProvider {
        @Override public String getProviderName() { return "fake-chat"; }
        @Override public String getDefaultModel() { return "fake-chat-1"; }
        @Override public List<String> getSupportedModels() { return List.of("fake-chat-1"); }
        @Override public boolean isConfigured() { return true; }
        @Override public boolean supportsStreaming() { return true; }
        @Override public boolean supportsToolCalling() { return true; }
        @Override public int getDisplayOrder() { return 1; }
        @Override public CompletionResponse complete(CompletionRequest request) { return null; }
        @Override public void completeStreaming(CompletionRequest request, StreamingCallback callback) { }
        @Override public Flux<StreamingEvent> streamReactive(CompletionRequest request) { return Flux.empty(); }
    }
}
