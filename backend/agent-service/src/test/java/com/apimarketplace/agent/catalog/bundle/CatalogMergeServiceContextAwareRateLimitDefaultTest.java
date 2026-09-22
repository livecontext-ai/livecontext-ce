package com.apimarketplace.agent.catalog.bundle;

import com.apimarketplace.agent.catalog.CatalogDefaults;
import com.apimarketplace.agent.config.ModelPricingConfig;
import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import com.apimarketplace.agent.repository.ModelConfigOverrideRepository;
import com.apimarketplace.agent.service.AuthPricingSyncClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.lang.reflect.Field;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The rate-limit fallback must scale with the model's context window.
 *
 * <p>Why a flat number cannot work. A token ceiling is only a ceiling relative
 * to what one request costs, and that cost is bounded by the context window. The
 * same value that is generous for a 32k model sits below a SINGLE request on a
 * 1M-context one, and the limiter then inverts: no request ever fits a fresh
 * window, so every call waits out the full 60s window before proceeding.
 *
 * <p>Measured on production 2026-09-11 with the flat 60 000 fallback,
 * {@code deepseek/deepseek-v4-flash} (1M context, ~50 000 estimated tokens per
 * tool-heavy agent turn): 16 of 17 calls delayed, ~51s of limiter wait each, for
 * provider responses that took 1.5-10s. 701 of 793 catalog rows carried that
 * same fallback.
 */
@DisplayName("CatalogMergeService - the rate-limit fallback scales with the context window")
class CatalogMergeServiceContextAwareRateLimitDefaultTest {

    /** What a tool-heavy agent turn reserves, from the production measurement above. */
    private static final int HEAVY_AGENT_TURN_TOKENS = 50_000;

    private ModelConfigOverrideRepository modelRepo;
    private CatalogMergeService merge;
    private CatalogDefaults defaults;

    @BeforeEach
    void setUp() {
        modelRepo = mock(ModelConfigOverrideRepository.class);
        when(modelRepo.findMaxRanking()).thenReturn(0);
        when(modelRepo.findByProviderAndModelId(any(), any())).thenReturn(Optional.empty());
        when(modelRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // The values actually shipped in application.yml.
        defaults = new CatalogDefaults();

        merge = new CatalogMergeService(modelRepo, null, mock(AuthPricingSyncClient.class), defaults);
        injectCuratedTable(curatedWith("gpt-5.4-mini"));
    }

    /** The field is @Autowired(required=false); tests set it directly. */
    private void injectCuratedTable(ModelPricingConfig config) {
        try {
            Field f = CatalogMergeService.class.getDeclaredField("modelPricingConfig");
            f.setAccessible(true);
            f.set(merge, config);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("field renamed? keep this test in step with it", e);
        }
    }

    private static ModelPricingConfig curatedWith(String... keys) {
        ModelPricingConfig cfg = new ModelPricingConfig();
        Map<String, ModelPricingConfig.ModelRateLimitInfo> table = new HashMap<>();
        for (String k : keys) {
            ModelPricingConfig.ModelRateLimitInfo info = new ModelPricingConfig.ModelRateLimitInfo();
            info.setTpm(10_000_000);
            info.setRpm(10_000);
            table.put(k, info);
        }
        cfg.setRateLimits(table);
        return cfg;
    }

    private ModelConfigOverrideEntity mergeOne(String provider, String modelId, Integer contextWindow) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("provider", provider);
        row.put("modelId", modelId);
        row.put("displayName", modelId);
        if (contextWindow != null) row.put("contextWindow", contextWindow);
        return mergeRow(row, MergeOptions.forSync());
    }

    private ModelConfigOverrideEntity mergeRow(Map<String, Object> row, MergeOptions opts) {
        merge.merge(List.of(row), opts);

        org.mockito.ArgumentCaptor<ModelConfigOverrideEntity> cap =
                org.mockito.ArgumentCaptor.forClass(ModelConfigOverrideEntity.class);
        verify(modelRepo, atLeastOnce()).save(cap.capture());
        return cap.getValue();
    }

    /** Route the next merge through the UPDATE branch, on top of this row. */
    private void existingRow(ModelConfigOverrideEntity existing) {
        when(modelRepo.findByProviderAndModelId(any(), any())).thenReturn(Optional.of(existing));
    }

    private static ModelConfigOverrideEntity rowWith(String provider, String modelId,
                                                     Integer contextWindow, Integer tpm, Integer tenantTpm) {
        ModelConfigOverrideEntity e = new ModelConfigOverrideEntity();
        e.setProvider(provider);
        e.setModelId(modelId);
        e.setDisplayName(modelId);
        e.setContextWindow(contextWindow);
        e.setRateLimitTpm(tpm);
        e.setRateLimitTpmPerTenant(tenantTpm);
        e.setUserModifiedFields(new String[0]);
        return e;
    }

    @Test
    @DisplayName("A 1M-context model gets a window sized from its context, not the flat floor")
    void largeContextModelGetsADerivedWindow() {
        // The exact shape of the production incident: before this behaviour the
        // row landed at 60 000, below the cost of one of its own requests.
        ModelConfigOverrideEntity saved = mergeOne("deepseek", "deepseek-v4-flash", 1_000_000);

        assertThat(saved.getRateLimitTpm())
                .as("1_000_000 context x 4 = 4_000_000")
                .isEqualTo(4_000_000);
    }

    @Test
    @DisplayName("The derived window fits several full-size requests, never fewer than one")
    void derivedWindowAlwaysFitsMoreThanOneRequest() {
        // The defect was not "the number is small", it was "the ceiling is under
        // the cost of one request", which is what turns the limiter into the
        // bottleneck. Pin the property, not just the arithmetic.
        for (int contextWindow : new int[]{8_192, 32_768, 131_072, 262_144, 1_000_000, 2_000_000}) {
            ModelConfigOverrideEntity saved = mergeOne("acme", "model-" + contextWindow, contextWindow);

            assertThat(saved.getRateLimitTpm())
                    .as("context %d: a window under one agent turn deadlocks the limiter", contextWindow)
                    .isGreaterThan(HEAVY_AGENT_TURN_TOKENS);
            assertThat(saved.getRateLimitTpm())
                    .as("context %d: the window must fit several full-context requests", contextWindow)
                    .isGreaterThanOrEqualTo(contextWindow * 2);
        }
    }

    @Test
    @DisplayName("A small-context model keeps the flat floor, which is larger than its derived value")
    void smallContextModelKeepsTheFlatFloor() {
        // 32_768 x 4 = 131_072, well under the 2_000_000 floor.
        ModelConfigOverrideEntity saved = mergeOne("moonshot", "moonshot-v1-32k", 32_768);

        assertThat(saved.getRateLimitTpm()).isEqualTo(defaults.getRateLimitTpm());
    }

    @Test
    @DisplayName("A model declaring no context window falls back to the flat value")
    void noContextWindowFallsBackToTheFlatValue() {
        ModelConfigOverrideEntity saved = mergeOne("perplexity", "sonar-pro", null);

        assertThat(saved.getRateLimitTpm()).isEqualTo(defaults.getRateLimitTpm());
        assertThat(saved.getRateLimitTpmPerTenant()).isEqualTo(defaults.getRateLimitTpmPerTenant());
    }

    @Test
    @DisplayName("A non-positive context window is ignored rather than producing a zero ceiling")
    void nonPositiveContextWindowIsIgnored() {
        // A 0 ceiling is the worst possible outcome: ProviderRateLimiter treats
        // >= 0 as an enabled limit, so 0 blocks every call for that model.
        ModelConfigOverrideEntity saved = mergeOne("acme", "broken-ctx", 0);

        assertThat(saved.getRateLimitTpm()).isEqualTo(defaults.getRateLimitTpm());
    }

    @Test
    @DisplayName("The per-tenant floor rises with the global one, so it never sits under one request")
    void perTenantFloorTracksTheGlobalWindow() {
        // ProviderRateLimiter.acquireWithWait rejects outright, non-retryably,
        // when a single estimate exceeds the per-tenant TPM cap. A per-tenant
        // cap frozen at the flat value while the global one grew would hard-fail
        // every agent turn the day the strategy moves to PER_TENANT or HYBRID.
        ModelConfigOverrideEntity saved = mergeOne("deepseek", "deepseek-v4-flash", 1_000_000);

        assertThat(saved.getRateLimitTpmPerTenant())
                .as("a quarter of the 4_000_000 global window")
                .isEqualTo(1_000_000);
        assertThat(saved.getRateLimitTpmPerTenant())
                .as("a per-tenant cap below one agent turn is a hard failure, not a slowdown")
                .isGreaterThan(HEAVY_AGENT_TURN_TOKENS);
    }

    @Test
    @DisplayName("An absurd context window is clamped instead of overflowing the integer column")
    void absurdContextWindowIsClamped() {
        // contextWindow is feed-supplied and untrusted. 2e9 x 4 overflows int
        // and would land a negative ceiling, which reads as "limit disabled".
        ModelConfigOverrideEntity saved = mergeOne("acme", "bogus-feed-row", 2_000_000_000);

        assertThat(saved.getRateLimitTpm())
                .as("clamped, and above all still positive")
                .isPositive()
                .isEqualTo(100_000_000);
    }

    @Test
    @DisplayName("Setting the multiplier to zero switches the derivation off")
    void multiplierZeroDisablesTheDerivation() {
        defaults.setRateLimitTpmContextMultiplier(0);

        ModelConfigOverrideEntity saved = mergeOne("deepseek", "deepseek-v4-flash", 1_000_000);

        assertThat(saved.getRateLimitTpm()).isEqualTo(defaults.getRateLimitTpm());
    }

    @Test
    @DisplayName("Clearing the flat default still means no fallback at all, on both columns")
    void nullFlatDefaultStillMeansNoFallback() {
        // An operator who blanks the property is switching the fallback off;
        // deriving a value from the context window would override that. The
        // tenant column has to follow, or the fallback is only half off.
        defaults.setRateLimitTpm(null);
        defaults.setRateLimitTpmPerTenant(null);

        ModelConfigOverrideEntity saved = mergeOne("deepseek", "deepseek-v4-flash", 1_000_000);

        assertThat(saved.getRateLimitTpm()).isNull();
        assertThat(saved.getRateLimitTpmPerTenant()).isNull();
    }

    @Test
    @DisplayName("-1 means 'no limit' to the limiter, so the floor must not raise it into one")
    void disabledSentinelIsNotRaised() {
        // RateLimitConfig.hasGlobalTokenLimit reads >= 0 as enabled, so -1 is
        // the off-switch - V216__deepseek_disable_per_tenant_tpm.sql ships
        // exactly that value. Raising it would silently re-enable a cap an
        // operator had removed.
        defaults.setRateLimitTpm(-1);
        defaults.setRateLimitTpmPerTenant(-1);

        ModelConfigOverrideEntity saved = mergeOne("deepseek", "deepseek-v4-flash", 1_000_000);

        assertThat(saved.getRateLimitTpm()).isEqualTo(-1);
        assertThat(saved.getRateLimitTpmPerTenant()).isEqualTo(-1);
    }

    @Test
    @DisplayName("0 is the block-everything kill-switch, so the floor must not raise it either")
    void killSwitchSentinelIsNotRaised() {
        // ProviderRateLimiter documents cap=0 as "block all tenant traffic
        // (legitimate ops kill-switch)". Turning it into 4 000 000 would undo
        // an operator's deliberate stop.
        defaults.setRateLimitTpm(0);
        defaults.setRateLimitTpmPerTenant(0);

        ModelConfigOverrideEntity saved = mergeOne("deepseek", "deepseek-v4-flash", 1_000_000);

        assertThat(saved.getRateLimitTpm()).isZero();
        assertThat(saved.getRateLimitTpmPerTenant()).isZero();
    }

    @Test
    @DisplayName("A null or negative multiplier leaves the flat value, like zero does")
    void unusableMultiplierLeavesTheFlatValue() {
        defaults.setRateLimitTpmContextMultiplier(null);
        assertThat(mergeOne("acme", "null-mult", 1_000_000).getRateLimitTpm())
                .isEqualTo(defaults.getRateLimitTpm());

        defaults.setRateLimitTpmContextMultiplier(-4);
        assertThat(mergeOne("acme", "negative-mult", 1_000_000).getRateLimitTpm())
                .isEqualTo(defaults.getRateLimitTpm());
    }

    @Test
    @DisplayName("A curated model is still skipped entirely, however large its context window")
    void curatedModelIsStillSkipped() {
        // The context-aware floor must not reopen the hole closed in a21d88dfc:
        // a curated entry keeps NULL columns so the researched value wins.
        ModelConfigOverrideEntity saved = mergeOne("openai", "gpt-5.4-mini", 1_000_000);

        assertThat(saved.getRateLimitTpm()).isNull();
        assertThat(saved.getRateLimitTpmPerTenant()).isNull();
    }

    @Test
    @DisplayName("A feed-supplied limit still wins over the derived floor")
    void feedSuppliedLimitWinsOverTheDerivedFloor() {
        ModelConfigOverrideEntity saved = mergeRow(feedRowWithTpm(800_000), MergeOptions.forSync());

        assertThat(saved.getRateLimitTpm())
                .as("the fallback only fills a null; a real published limit is not a floor to raise")
                .isEqualTo(800_000);
    }

    @Test
    @DisplayName("The per-tenant cap never exceeds the global one, at any feed-supplied global")
    void perTenantNeverExceedsTheGlobalCapOnTheRow() {
        // Two distinct ways to break this, so a single input proves nothing.
        // Above the configured flat floor, the danger is sizing the tenant
        // share off the DISCARDED default (that gave tenant 1 048 576 against
        // global 800 000). BELOW the flat floor, the flat floor itself is the
        // excess: 500 000 against a global of 100 000 is five times the whole
        // platform. Both regions are swept here. Under PER_TENANT only the
        // tenant dimension is checked, so either shape lets one tenant out-run
        // the published limit the row exists to respect.
        for (int feedTpm : new int[]{60_000, 100_000, 250_000, 499_999, 800_000, 4_000_000}) {
            ModelConfigOverrideEntity saved = mergeRow(feedRowWithTpm(feedTpm), MergeOptions.forSync());

            assertThat(saved.getRateLimitTpm()).isEqualTo(feedTpm);
            assertThat(saved.getRateLimitTpmPerTenant())
                    .as("global %d: a tenant may not be allowed more than the whole platform", feedTpm)
                    .isLessThanOrEqualTo(saved.getRateLimitTpm());
        }
    }

    @Test
    @DisplayName("Above the flat floor the tenant gets a quarter; below it, the whole window")
    void perTenantIsAQuarterOrTheWholeWindow() {
        // Pins which of the two bounds fires, so the test above cannot pass by
        // accident with a degenerate implementation that always returns zero.
        assertThat(mergeRow(feedRowWithTpm(4_000_000), MergeOptions.forSync()).getRateLimitTpmPerTenant())
                .as("a quarter of 4 000 000, which clears the 500 000 floor")
                .isEqualTo(1_000_000);
        assertThat(mergeRow(feedRowWithTpm(800_000), MergeOptions.forSync()).getRateLimitTpmPerTenant())
                .as("a quarter of 800 000 is 200 000, so the configured 500 000 floor wins")
                .isEqualTo(500_000);
        assertThat(mergeRow(feedRowWithTpm(100_000), MergeOptions.forSync()).getRateLimitTpmPerTenant())
                .as("the floor would exceed the platform, so the tenant is capped at it")
                .isEqualTo(100_000);
    }

    /** A 1M-context row whose feed declares its own global TPM. */
    private static Map<String, Object> feedRowWithTpm(int tpm) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("provider", "google");
        row.put("modelId", "gemini-3.6-flash");
        row.put("displayName", "gemini-3.6-flash");
        row.put("contextWindow", 1_048_576);
        row.put("rateLimitTpm", tpm);
        return row;
    }

    @Test
    @DisplayName("A sync over a row still carrying the old flat value raises it - no migration needed")
    void syncUpdateRaisesARowCarryingTheOldFlatValue() {
        // This is what repairs a fleet already stamped with the old 60 000, and
        // it is not obvious: forSync() is partialUpdate=false, so applyFields
        // NULLS a column the feed does not carry, and the fallback then fills
        // the derived value. Were forSync ever switched to partial semantics,
        // that repair would silently stop and this test is what says so.
        existingRow(rowWith("deepseek", "deepseek-v4-flash", 1_000_000, 60_000, 20_000));

        ModelConfigOverrideEntity saved = mergeOne("deepseek", "deepseek-v4-flash", 1_000_000);

        assertThat(saved.getRateLimitTpm()).isEqualTo(4_000_000);
        assertThat(saved.getRateLimitTpmPerTenant()).isEqualTo(1_000_000);
    }

    @Test
    @DisplayName("A user-modified row is never raised, whatever it holds")
    void userModifiedRowIsNeverRaised() {
        // An admin who set a deliberate ceiling keeps it. This is also why the
        // rows repaired by hand in production stay put: the repair marked those
        // four fields user-modified.
        ModelConfigOverrideEntity existing =
                rowWith("deepseek", "deepseek-v4-flash", 1_000_000, 60_000, 20_000);
        existing.setUserModifiedFields(new String[]{"rateLimitTpm", "rateLimitTpmPerTenant"});
        existingRow(existing);

        ModelConfigOverrideEntity saved = mergeOne("deepseek", "deepseek-v4-flash", 1_000_000);

        assertThat(saved.getRateLimitTpm()).isEqualTo(60_000);
        assertThat(saved.getRateLimitTpmPerTenant()).isEqualTo(20_000);
    }

    @Test
    @DisplayName("On an update, a row whose columns are null gets the derived floor")
    void updatePathFillsNullColumns() {
        existingRow(rowWith("deepseek", "deepseek-v4-flash", 1_000_000, null, null));

        ModelConfigOverrideEntity saved = mergeOne("deepseek", "deepseek-v4-flash", 1_000_000);

        assertThat(saved.getRateLimitTpm()).isEqualTo(4_000_000);
        assertThat(saved.getRateLimitTpmPerTenant()).isEqualTo(1_000_000);
    }

    @Test
    @DisplayName("The sync repair also needs the feed to carry contextWindow, or it collapses to the flat value")
    void syncRepairDependsOnTheFeedCarryingContextWindow() {
        // The other half of the repair mechanism. Under partialUpdate=false a
        // payload that omits contextWindow NULLS it on the row, and the derived
        // floor then has nothing to derive from. Benign while both feed parsers
        // emit it (LiteLlmFeedParser, OpenRouterFeedParser), which is exactly
        // why it needs a guard: the day one stops, a 1M-context model quietly
        // drops from 4 000 000 to the flat value instead of failing loudly.
        existingRow(rowWith("deepseek", "deepseek-v4-flash", 1_000_000, 60_000, 20_000));

        ModelConfigOverrideEntity saved = mergeOne("deepseek", "deepseek-v4-flash", null);

        assertThat(saved.getContextWindow()).isNull();
        assertThat(saved.getRateLimitTpm()).isEqualTo(defaults.getRateLimitTpm());
    }

    @Test
    @DisplayName("A bundle carrying the old flat value re-stamps it over a repaired row")
    void bundleCarryingTheOldValueReStampsIt() {
        // The channel CE actually receives its catalog on. A bundle is an
        // authoritative snapshot, so whatever TPM the cloud row holds is what
        // lands here - including a stale 60 000 if the cloud was not repaired
        // first. This is why the seed/cloud repair has to come BEFORE the
        // bundle, not after, and it is not something this fallback can fix.
        existingRow(rowWith("deepseek", "deepseek-v4-flash", 1_000_000, 4_000_000, 1_000_000));

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("provider", "deepseek");
        row.put("modelId", "deepseek-v4-flash");
        row.put("displayName", "deepseek-v4-flash");
        row.put("contextWindow", 1_000_000);
        row.put("rateLimitTpm", 60_000);

        ModelConfigOverrideEntity saved = mergeRow(row, MergeOptions.forBundle(7L));

        assertThat(saved.getRateLimitTpm()).isEqualTo(60_000);
    }

    @Test
    @DisplayName("A curated model is skipped on the update path too, not only on insert")
    void curatedModelIsSkippedOnUpdateToo() {
        // The row starts with the OLD fallback rather than nulls, so a null
        // result can only mean applyFields nulled it and the early return then
        // declined to refill. Starting from nulls would also pass with the
        // curated guard removed, since the fallback could simply return null.
        existingRow(rowWith("openai", "gpt-5.4-mini", 1_000_000, 60_000, 20_000));

        ModelConfigOverrideEntity saved = mergeOne("openai", "gpt-5.4-mini", 1_000_000);

        assertThat(saved.getRateLimitTpm())
                .as("a curated model must resolve through ai.agent.rate-limits, not a stamped column")
                .isNull();
        assertThat(saved.getRateLimitTpmPerTenant()).isNull();
    }

    @Test
    @DisplayName("A seed payload that carries its own TPM keeps it, context window or not")
    void seedSuppliedTpmIsKept() {
        // The CE seed (model-catalog/models.json) declares rateLimitTpm per
        // model, so applyFields writes it and the fallback never runs. A seed
        // shipping a low value therefore pins that value on every install,
        // which is a property of the seed file, not something this method can
        // repair.
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("provider", "deepseek");
        row.put("modelId", "deepseek-v4-flash");
        row.put("displayName", "deepseek-v4-flash");
        row.put("contextWindow", 1_000_000);
        row.put("rateLimitTpm", 60_000);

        ModelConfigOverrideEntity saved = mergeRow(row, MergeOptions.forSeed());

        assertThat(saved.getRateLimitTpm()).isEqualTo(60_000);
    }

    @Test
    @DisplayName("The shipped defaults are the ones this fix was sized for, and beat one agent turn")
    void shippedDefaultsArePinned() {
        // Self-referential assertions elsewhere in this class compare against
        // whatever the bean happens to hold. This one pins the literals, so a
        // revert to the old 60 000 / 20 000 fails here rather than passing
        // everywhere.
        CatalogDefaults shipped = new CatalogDefaults();

        assertThat(shipped.getRateLimitTpm()).isEqualTo(2_000_000);
        assertThat(shipped.getRateLimitTpmPerTenant()).isEqualTo(500_000);
        assertThat(shipped.getRateLimitTpmContextMultiplier()).isEqualTo(4);
        assertThat(shipped.getRateLimitTpm())
                .as("a model with no declared context window must still clear one agent turn")
                .isGreaterThan(HEAVY_AGENT_TURN_TOKENS);
        assertThat(shipped.getRateLimitTpmPerTenant())
                .isGreaterThan(HEAVY_AGENT_TURN_TOKENS);
    }

    @Test
    @DisplayName("application.yml and the Java defaults agree, so neither can drift unnoticed")
    void ymlMatchesTheJavaDefaults() {
        // The two are redundant on purpose: the yml is the documented override
        // point, the Java field is what a context without that yml gets (CE
        // monolith, tests, a trimmed profile). If they drift, the value in
        // force depends on which one loaded, which is the kind of difference
        // nobody notices until a model is throttled on one deployment only.
        // Loaded the way Spring itself would, so a key this test reads is a key
        // that actually binds.
        Properties yml = loadApplicationYml();

        assertThat(intProperty(yml, "rate-limit-tpm")).isEqualTo(defaults.getRateLimitTpm());
        assertThat(intProperty(yml, "rate-limit-rpm")).isEqualTo(defaults.getRateLimitRpm());
        assertThat(intProperty(yml, "rate-limit-tpm-per-tenant")).isEqualTo(defaults.getRateLimitTpmPerTenant());
        assertThat(intProperty(yml, "rate-limit-rpm-per-tenant")).isEqualTo(defaults.getRateLimitRpmPerTenant());
        assertThat(intProperty(yml, "rate-limit-tpm-context-multiplier"))
                .as("a key typo here binds nothing and the drift is silent")
                .isEqualTo(defaults.getRateLimitTpmContextMultiplier());
    }

    private static Properties loadApplicationYml() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));
        Properties props = factory.getObject();
        assertThat(props).as("application.yml must be on the test classpath").isNotNull();
        return props;
    }

    private static Integer intProperty(Properties yml, String key) {
        String raw = yml.getProperty("ai.agent.defaults." + key);
        assertThat(raw).as("ai.agent.defaults.%s must exist in application.yml", key).isNotNull();
        return Integer.valueOf(raw);
    }
}
