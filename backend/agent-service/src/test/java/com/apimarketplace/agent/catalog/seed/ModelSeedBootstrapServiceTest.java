package com.apimarketplace.agent.catalog.seed;

import com.apimarketplace.agent.catalog.bundle.CatalogMergeService;
import com.apimarketplace.agent.catalog.bundle.MergeOptions;
import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import com.apimarketplace.agent.repository.ModelConfigOverrideRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The boot-time model-catalog seed is INSERT-ONLY (additive): it inserts models
 * absent from {@code model_config_overrides} and never touches existing rows
 * (so it can't clobber bundle/admin enrichment), and it never crash-loops the
 * app on a bad/missing seed.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModelSeedBootstrapService - insert-only additive seed")
class ModelSeedBootstrapServiceTest {

    @Mock private CatalogMergeService merge;
    @Mock private ModelConfigOverrideRepository repo;
    @Mock private PlatformTransactionManager txManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String TWO_MODELS = """
        {"version":1,"models":[
          {"provider":"openai","modelId":"gpt-5.4","displayName":"gpt-5.4","enabled":true,"priceInput":"2.50","priceOutput":"15.00"},
          {"provider":"anthropic","modelId":"claude-x","displayName":"claude-x","enabled":true}
        ]}""";

    private ModelSeedBootstrapService service(Resource res) {
        return new ModelSeedBootstrapService(merge, repo, objectMapper, txManager, res);
    }

    private static Resource json(String body) {
        return new ByteArrayResource(body.getBytes(StandardCharsets.UTF_8));
    }

    private void stubTx() {
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    private static ModelConfigOverrideEntity existing(String provider, String modelId) {
        ModelConfigOverrideEntity e = new ModelConfigOverrideEntity();
        e.setProvider(provider);
        e.setModelId(modelId);
        return e;
    }

    @Test
    @DisplayName("Inserts ONLY models absent from the DB; an already-present model is skipped")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void insertsOnlyAbsentModels() throws Exception {
        when(repo.findAllByOrderByRankingAsc()).thenReturn(List.of(existing("openai", "gpt-5.4")));
        stubTx();
        when(merge.merge(anyList(), any())).thenReturn(new CatalogMergeService.MergeResult(1, 0, 0, 0, 0, 0));

        int inserted = service(json(TWO_MODELS)).seedNow();

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(merge).merge(captor.capture(), any(MergeOptions.class));
        List<Map<String, Object>> applied = captor.getValue();
        assertThat(applied).hasSize(1);
        assertThat(applied.get(0).get("modelId"))
                .as("only the absent model (claude-x) is applied; the present gpt-5.4 is filtered out")
                .isEqualTo("claude-x");
        assertThat(inserted).isEqualTo(1);
    }

    @Test
    @DisplayName("When every seed model is already present, merge is NEVER called (no writes, no audit churn)")
    void noMergeWhenAllPresent() throws Exception {
        when(repo.findAllByOrderByRankingAsc()).thenReturn(List.of(
                existing("openai", "gpt-5.4"), existing("anthropic", "claude-x")));

        int inserted = service(json(TWO_MODELS)).seedNow();

        verify(merge, never()).merge(anyList(), any());
        assertThat(inserted).isZero();
    }

    @Test
    @DisplayName("Missing seed resource is a no-op - never blocks startup")
    void missingResourceIsNoOp() throws Exception {
        Resource missing = Mockito.mock(Resource.class);
        when(missing.exists()).thenReturn(false);

        int inserted = service(missing).seedNow();

        verify(merge, never()).merge(anyList(), any());
        assertThat(inserted).isZero();
    }

    @Test
    @DisplayName("Malformed seed JSON is swallowed by the startup hook (app keeps booting)")
    void malformedJsonSwallowedOnStartup() {
        // seedOnStartup must not propagate - a bad seed cannot crash-loop the app.
        service(json("{ not valid json")).seedOnStartup();
        verify(merge, never()).merge(anyList(), any());
    }

    @Test
    @DisplayName("The SHIPPED classpath seed (model-catalog/models.json) parses and would seed an empty DB")
    void realClasspathSeedSeedsEmptyDb() throws Exception {
        when(repo.findAllByOrderByRankingAsc()).thenReturn(List.of()); // empty DB
        stubTx();
        when(merge.merge(anyList(), any())).thenAnswer(inv -> {
            List<?> applied = inv.getArgument(0);
            return new CatalogMergeService.MergeResult(applied.size(), 0, 0, 0, 0, 0);
        });

        Resource real = new ClassPathResource("model-catalog/models.json");
        assertThat(real.exists())
                .as("the seed resource must ship on the agent-service classpath")
                .isTrue();

        int inserted = service(real).seedNow();

        assertThat(inserted)
                .as("the curated seed should carry the full catalog (59 today); floor guards a gutted resource")
                .isGreaterThanOrEqualTo(50);
    }
}
