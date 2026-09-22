package com.apimarketplace.catalog.seed;

import com.apimarketplace.catalog.bundle.ApiCatalogGenerationPriceApplier;
import com.apimarketplace.catalog.domain.ApiCatalogBundleEntity;
import com.apimarketplace.catalog.repository.ApiCatalogBundleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DescriptiveResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The seed that gives a never-linked self-hosted install its generation models
 * and their starting prices: what it writes, what it refuses to write, and when
 * it stands aside for the cloud.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GenerationSeedBootstrap - a fresh self-hosted install can generate, without a network")
class GenerationSeedBootstrapTest {

    private static final String TOOL_A = "11111111-1111-4111-8111-111111111111";
    private static final String TOOL_B = "22222222-2222-4222-8222-222222222222";

    @Mock private NamedParameterJdbcTemplate jdbc;
    @Mock private ApiCatalogBundleRepository bundleRepository;
    @Mock private ApiCatalogGenerationPriceApplier priceApplier;
    @Mock private PlatformTransactionManager transactionManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** api_tool_id -> platform credential name, as the eligibility query would answer. */
    private final Map<UUID, String> eligibleRows = new LinkedHashMap<>();
    /** What the seed-state marker holds, null when never applied. */
    private Long markerVersion;
    /** Every UPDATE the seed issued, as (sql, apiToolId). */
    private final List<String> descriptorUpdates = new ArrayList<>();
    /** The version the seed wrote back to the marker, null when it wrote none. */
    private Long markerWritten;

    @BeforeEach
    void setUp() {
        when(bundleRepository.findActiveMetadata()).thenReturn(List.of());
        stubReads();
        stubWrites();
    }

    // ── stubs standing in for the two reads and the two writes ──────────────

    @SuppressWarnings("unchecked")
    private void stubReads() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    SqlParameterSource params = invocation.getArgument(1);
                    RowMapper<Object> mapper = invocation.getArgument(2);
                    List<Object> out = new ArrayList<>();
                    if (sql.contains("generation_seed_state")) {
                        if (markerVersion != null) {
                            out.add(mapper.mapRow(markerRow(markerVersion), 0));
                        }
                        return out;
                    }
                    // Stand in for the WHERE t.id IN (:ids) AND a.source = 'import'
                    // filter: only the ids the seed asked about, and only those
                    // the test declared this install still owns.
                    List<?> asked = (List<?>) params.getValue("ids");
                    int row = 0;
                    for (Map.Entry<UUID, String> entry : eligibleRows.entrySet()) {
                        if (asked != null && !asked.contains(entry.getKey())) continue;
                        out.add(mapper.mapRow(eligibleRow(entry.getKey(), entry.getValue()), row++));
                    }
                    return out;
                });
    }

    private void stubWrites() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            SqlParameterSource params = invocation.getArgument(1);
            if (sql.contains("generation_seed_state")) {
                markerWritten = (Long) params.getValue("version");
                return 1;
            }
            descriptorUpdates.add(String.valueOf(params.getValue("id")));
            return 1;
        });
    }

    private static ResultSet markerRow(long version) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("applied_version")).thenReturn(version);
        when(rs.wasNull()).thenReturn(false);
        return rs;
    }

    private static ResultSet eligibleRow(UUID id, String integrationName) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("id")).thenReturn(id);
        when(rs.getString("integration_name")).thenReturn(integrationName);
        return rs;
    }

    // ── seed documents ──────────────────────────────────────────────────────

    private static String descriptor(String modelId, String priceJson) {
        return """
                {
                  "kind": "image",
                  "assetPath": "data.url",
                  "paramMap": { "prompt": "prompt" },
                  "models": [ { "id": "%s", "capabilities": ["prompt"]%s } ]
                }
                """.formatted(modelId, priceJson);
    }

    private static String entry(String toolId, String name, String descriptor) {
        return """
                { "apiToolId": "%s", "endpointName": "%s", "sourceFile": "x.json",
                  "generationSpec": %s }
                """.formatted(toolId, name, descriptor);
    }

    private static Resource seed(long version, String... entries) {
        String json = """
                { "version": %d, "endpoints": [ %s ] }
                """.formatted(version, String.join(",", entries));
        return new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8));
    }

    private GenerationSeedBootstrap bootstrapWith(Resource resource) {
        return new GenerationSeedBootstrap(jdbc, bundleRepository, priceApplier,
                objectMapper, transactionManager, resource);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> offeredPrices() {
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(priceApplier).apply(captor.capture(), any(), anyString());
        return captor.getValue();
    }

    // ── the branches ────────────────────────────────────────────────────────

    @Test
    @DisplayName("a fresh install gets the descriptors, the marker, and one starting price per model")
    void freshInstallIsSeeded() throws Exception {
        eligibleRows.put(UUID.fromString(TOOL_A), "seedance");
        markerVersion = null;

        GenerationSeedBootstrap.Result result = bootstrapWith(seed(2012,
                entry(TOOL_A, "create_video_task",
                        descriptor("seedance-2.0", ", \"price\": {\"unit\":\"call\",\"baseCredits\":120}"))))
                .seedNow();

        assertThat(result.descriptorsApplied()).isEqualTo(1);
        assertThat(result.supersededByBundle()).isFalse();
        assertThat(descriptorUpdates).containsExactly(TOOL_A);
        assertThat(markerWritten).isEqualTo(2012L);

        Map<String, Object> price = offeredPrices().get(0);
        assertThat(price).containsEntry("integrationName", "seedance")
                .containsEntry("apiToolId", TOOL_A)
                .containsEntry("modelId", "seedance-2.0")
                .containsEntry("priceUnit", "call")
                .containsEntry("baseCredits", "120");
    }

    @Test
    @DisplayName("the prices are published as bundle-owned, under the seed's own name in the history")
    void pricesTravelTheBundlePathUnderTheSeedsName() throws Exception {
        eligibleRows.put(UUID.fromString(TOOL_A), "seedance");

        bootstrapWith(seed(2012, entry(TOOL_A, "e", descriptor("m-1", "")))).seedNow();

        // Going through the price APPLIER, and not publishing a version here, is
        // what stamps the row `bundle`: not decided on this install, therefore
        // replaceable by the first real bundle and preserved once an admin edits
        // it. The origin only keeps the history from naming a bundle that never
        // existed.
        verify(priceApplier).apply(anyList(), eq(2012L), eq("generation-seed"));
    }

    @Test
    @DisplayName("an install the cloud already feeds is left alone entirely")
    void anActiveBundleSupersedesTheSeed() throws Exception {
        // The bootstrap only asks WHETHER a bundle owns the catalog, so it reads
        // the payload-free projection: the entity finder would pull ~24 MB of
        // gzip into heap on every boot to answer a yes/no question.
        when(bundleRepository.findActiveMetadata()).thenReturn(List.of(org.mockito.Mockito.mock(ApiCatalogBundleRepository.ActiveBundleMeta.class)));
        eligibleRows.put(UUID.fromString(TOOL_A), "seedance");

        GenerationSeedBootstrap.Result result =
                bootstrapWith(seed(2012, entry(TOOL_A, "e", descriptor("m-1", "")))).seedNow();

        assertThat(result.supersededByBundle()).isTrue();
        assertThat(descriptorUpdates).isEmpty();
        assertThat(markerWritten).isNull();
        // Not even the prices: the bundle re-offers its own on every sync tick,
        // so a seed that kept speaking would flip the price back on each restart
        // and mint an immutable pricing version for every flip.
        verifyNoInteractions(priceApplier);
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
        verify(jdbc, never()).query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
    }

    @Test
    @DisplayName("an already-seeded install rewrites no descriptor, but still offers its prices")
    void alreadySeededSkipsDescriptorsAndStillPrices() throws Exception {
        eligibleRows.put(UUID.fromString(TOOL_A), "seedance");
        markerVersion = 2012L;

        GenerationSeedBootstrap.Result result =
                bootstrapWith(seed(2012, entry(TOOL_A, "e", descriptor("m-1", "")))).seedNow();

        assertThat(result.descriptorsAlreadyApplied()).isTrue();
        assertThat(descriptorUpdates).isEmpty();
        assertThat(markerWritten).isNull();
        // Re-offered on purpose: the operator who pastes the provider key a week
        // later had no credential for the price to attach to the first time.
        assertThat(offeredPrices()).hasSize(1);
    }

    @Test
    @DisplayName("a release that bumps the version refreshes an install that is already seeded")
    void aNewerVersionReapplies() throws Exception {
        eligibleRows.put(UUID.fromString(TOOL_A), "seedance");
        markerVersion = 2011L;

        GenerationSeedBootstrap.Result result =
                bootstrapWith(seed(2012, entry(TOOL_A, "e", descriptor("m-1", "")))).seedNow();

        assertThat(result.descriptorsAlreadyApplied()).isFalse();
        assertThat(descriptorUpdates).containsExactly(TOOL_A);
        assertThat(markerWritten).isEqualTo(2012L);
    }

    @Test
    @DisplayName("an endpoint this install does not own gets neither a descriptor nor a price")
    void anEndpointTheSeedDoesNotOwnIsSkipped() throws Exception {
        // Only TOOL_A comes back from the eligibility query. TOOL_B is absent
        // because its API is source='custom' or 'bundle', or because the catalog
        // does not carry it at all - the seed cannot tell those apart and must
        // not write in any of them.
        eligibleRows.put(UUID.fromString(TOOL_A), "seedance");

        GenerationSeedBootstrap.Result result = bootstrapWith(seed(2012,
                entry(TOOL_A, "mine", descriptor("m-1", "")),
                entry(TOOL_B, "theirs", descriptor("m-2", "")))).seedNow();

        assertThat(descriptorUpdates).containsExactly(TOOL_A);
        assertThat(result.descriptorsSkipped()).isEqualTo(1);
        assertThat(offeredPrices()).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("modelId", "m-1"));
    }

    @Test
    @DisplayName("an endpoint whose API has no platform key is described but not priced")
    void anApiWithNoPlatformCredentialIsNotPriced() throws Exception {
        eligibleRows.put(UUID.fromString(TOOL_A), null);

        bootstrapWith(seed(2012, entry(TOOL_A, "e", descriptor("m-1", "")))).seedNow();

        // The descriptor is still worth having: the model exists the moment a key
        // is pasted. The price has nothing to hang off until then.
        assertThat(descriptorUpdates).containsExactly(TOOL_A);
        verify(priceApplier).apply(eq(List.of()), any(), anyString());
    }

    @Test
    @DisplayName("a model with no price block is seeded free rather than left unpriced")
    void aModelWithoutAPriceIsSeededFree() throws Exception {
        eligibleRows.put(UUID.fromString(TOOL_A), "seedance");

        bootstrapWith(seed(2012, entry(TOOL_A, "e", descriptor("m-1", "")))).seedNow();

        assertThat(offeredPrices().get(0))
                .containsEntry("priceUnit", "call")
                .containsEntry("baseCredits", "0")
                .containsEntry("unitCredits", "0");
    }

    @Test
    @DisplayName("a malformed descriptor costs itself only, never the providers next to it")
    void aMalformedDescriptorIsSkippedAlone() throws Exception {
        eligibleRows.put(UUID.fromString(TOOL_A), "seedance");
        eligibleRows.put(UUID.fromString(TOOL_B), "elevenlabs");

        // A per-second price with no unitCredits: the unit would have no effect,
        // and GenerationSpec refuses it at parse rather than at call time.
        String broken = """
                {
                  "kind": "video",
                  "assetPath": "data.url",
                  "paramMap": { "prompt": "prompt", "duration_seconds": "duration" },
                  "models": [ { "id": "bad", "capabilities": ["prompt","duration_seconds"],
                                "price": { "unit": "second", "unitCredits": 0 } } ]
                }
                """;

        GenerationSeedBootstrap.Result result = bootstrapWith(seed(2012,
                entry(TOOL_A, "good", descriptor("m-1", "")),
                entry(TOOL_B, "broken", broken))).seedNow();

        assertThat(descriptorUpdates).containsExactly(TOOL_A);
        assertThat(result.descriptorsApplied()).isEqualTo(1);
        assertThat(offeredPrices()).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("modelId", "m-1"));
    }

    @Test
    @DisplayName("a seed with no version is refused, because nothing could ever say it was done")
    void anUnversionedSeedIsRefused() throws Exception {
        eligibleRows.put(UUID.fromString(TOOL_A), "seedance");

        Resource unversioned = new ByteArrayResource(("""
                { "endpoints": [ %s ] }
                """.formatted(entry(TOOL_A, "e", descriptor("m-1", ""))))
                .getBytes(StandardCharsets.UTF_8));

        GenerationSeedBootstrap.Result result = bootstrapWith(unversioned).seedNow();

        assertThat(result.version()).isZero();
        assertThat(descriptorUpdates).isEmpty();
        verifyNoInteractions(priceApplier);
    }

    @Test
    @DisplayName("a missing seed resource leaves the install exactly as it was")
    void aMissingResourceIsANoOp() throws Exception {
        GenerationSeedBootstrap.Result result =
                bootstrapWith(new DescriptiveResource("absent")).seedNow();

        assertThat(result).isEqualTo(new GenerationSeedBootstrap.Result(0, false, false, 0, 0, 0));
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
        verifyNoInteractions(priceApplier);
    }

    @Test
    @DisplayName("a broken seed is logged, never a boot failure")
    void aBrokenSeedNeverStopsTheBoot() {
        Resource garbage = new ByteArrayResource("not json".getBytes(StandardCharsets.UTF_8));

        GenerationSeedBootstrap.Result result = bootstrapWith(garbage).seedOnStartup();

        assertThat(result.version()).isZero();
        verify(priceApplier, never()).apply(anyList(), any(), anyString());
    }
}
