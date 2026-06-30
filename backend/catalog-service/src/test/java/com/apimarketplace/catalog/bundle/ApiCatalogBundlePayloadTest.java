package com.apimarketplace.catalog.bundle;

import com.apimarketplace.catalog.bundle.ApiCatalogBundlePayload.ApiRow;
import com.apimarketplace.catalog.bundle.ApiCatalogBundlePayload.CredentialTemplateRow;
import com.apimarketplace.catalog.bundle.ApiCatalogBundlePayload.ParameterRow;
import com.apimarketplace.catalog.bundle.ApiCatalogBundlePayload.ResponseRow;
import com.apimarketplace.catalog.bundle.ApiCatalogBundlePayload.ToolCredentialRow;
import com.apimarketplace.catalog.bundle.ApiCatalogBundlePayload.ToolRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Canonical-bytes contract for the API-catalog bundle payload: the signer
 * signs these bytes and CE verifies the exact same bytes, so two builds from
 * the same logical snapshot MUST be byte-identical - regardless of the order
 * the DB returned rows in.
 */
@DisplayName("ApiCatalogBundlePayload - canonical determinism + gzip")
class ApiCatalogBundlePayloadTest {

    private static final Instant SNAPSHOT_AT = Instant.parse("2026-06-10T12:00:00Z");

    private static ApiRow api(UUID id, String name, List<ToolRow> tools) {
        return new ApiRow(id, name, name.toLowerCase(), "desc " + name, "https://api.example",
                null, "Communication", "communication", "Email", "email",
                "oauth2", null, null, "public", true, true, false,
                "free", "APPROVED", "1.0.0", name.toLowerCase(), name.toLowerCase() + "_cred",
                null, null, null, "{\"per_minute\":60}", tools);
    }

    private static ToolRow tool(UUID id, String slug,
                                List<ParameterRow> params,
                                List<ResponseRow> responses,
                                List<ToolCredentialRow> creds) {
        return new ToolRow(id, slug, "does " + slug, null, "GET", "/v1/" + slug, "HTTP",
                null, null, "{\"mode\":\"http\"}", "[{\"name\":\"x\"}]", "http", null,
                null, null, "ACTIVE", null, true, "1.0.0", params, responses, creds);
    }

    private static ParameterRow param(UUID id, String name) {
        return new ParameterRow(id, "query", name, "string", true, "d", "ex", null, null,
                null, null, false);
    }

    @Test
    @DisplayName("Same logical input produces byte-identical output across calls")
    void deterministicAcrossCalls() {
        List<ApiRow> apis = sampleApis();
        byte[] first = ApiCatalogBundlePayload.canonicalBytes(7L, 1, "cloud", SNAPSHOT_AT, apis, sampleTemplates());
        byte[] second = ApiCatalogBundlePayload.canonicalBytes(7L, 1, "cloud", SNAPSHOT_AT, apis, sampleTemplates());
        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("Input list order does not change the bytes (apis, tools, params, templates all sorted)")
    void orderIndependence() {
        List<ApiRow> apis = sampleApis();
        byte[] reference = ApiCatalogBundlePayload.canonicalBytes(7L, 1, "cloud", SNAPSHOT_AT, apis, sampleTemplates());

        // Shuffle every level of the input.
        List<ApiRow> shuffledApis = new ArrayList<>();
        for (ApiRow a : apis) {
            List<ToolRow> shuffledTools = new ArrayList<>();
            for (ToolRow t : a.tools()) {
                List<ParameterRow> p = new ArrayList<>(t.parameters());
                Collections.shuffle(p, new Random(42));
                List<ToolCredentialRow> c = new ArrayList<>(t.toolCredentials());
                Collections.shuffle(c, new Random(42));
                shuffledTools.add(new ToolRow(t.id(), t.toolSlug(), t.description(), t.toolNameId(),
                        t.method(), t.endpoint(), t.protocol(), t.defaultHeaders(), t.runtimeMetadata(),
                        t.executionSpec(), t.outputSchema(), t.executionMode(), t.pagination(),
                        t.requiredScopes(), t.nextHint(), t.status(), t.testStatus(), t.isActive(),
                        t.version(), p, t.responses(), c));
            }
            Collections.shuffle(shuffledTools, new Random(42));
            shuffledApis.add(new ApiRow(a.id(), a.apiName(), a.apiSlug(), a.description(), a.baseUrl(),
                    a.healthcheckEndpoint(), a.categoryName(), a.categorySlug(), a.subcategoryName(),
                    a.subcategorySlug(), a.authType(), a.authHeaderName(), a.authHeaderValue(),
                    a.visibility(), a.isPublic(), a.isActive(), a.isLocal(), a.pricingModel(),
                    a.status(), a.version(), a.iconSlug(), a.platformCredentialName(), a.iconUrl(),
                    a.apiVersion(), a.documentation(), a.rateLimits(), shuffledTools));
        }
        Collections.shuffle(shuffledApis, new Random(42));
        List<CredentialTemplateRow> shuffledTemplates = new ArrayList<>(sampleTemplates());
        Collections.shuffle(shuffledTemplates, new Random(42));

        byte[] shuffled = ApiCatalogBundlePayload.canonicalBytes(
                7L, 1, "cloud", SNAPSHOT_AT, shuffledApis, shuffledTemplates);

        assertThat(shuffled).isEqualTo(reference);
    }

    @Test
    @DisplayName("Null fields are omitted entirely from the JSON")
    void nullFieldsOmitted() throws Exception {
        UUID apiId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ApiRow bare = new ApiRow(apiId, "Bare", null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, List.of());

        byte[] bytes = ApiCatalogBundlePayload.canonicalBytes(1L, 1, "cloud", SNAPSHOT_AT,
                List.of(bare), List.of());

        JsonNode apiNode = new ObjectMapper().readTree(bytes).get("apis").get(0);
        assertThat(apiNode.has("apiSlug")).isFalse();
        assertThat(apiNode.has("baseUrl")).isFalse();
        assertThat(apiNode.has("category")).isFalse();
        assertThat(apiNode.has("iconSlug")).isFalse();
        assertThat(apiNode.get("id").asText()).isEqualTo(apiId.toString());
        // tools is always present so the applier can iterate unconditionally.
        assertThat(apiNode.get("tools").isArray()).isTrue();
    }

    @Test
    @DisplayName("Top-level keys are sorted and carry version/schemaVersion/issuer/snapshotAt")
    void topLevelShape() throws Exception {
        byte[] bytes = ApiCatalogBundlePayload.canonicalBytes(99L, 2, "test-cloud", SNAPSHOT_AT,
                sampleApis(), sampleTemplates());
        JsonNode root = new ObjectMapper().readTree(bytes);

        assertThat(root.get("version").asLong()).isEqualTo(99L);
        assertThat(root.get("schemaVersion").asInt()).isEqualTo(2);
        assertThat(root.get("issuer").asText()).isEqualTo("test-cloud");
        assertThat(root.get("snapshotAt").asText()).isEqualTo("2026-06-10T12:00:00Z");
        assertThat(root.get("credentialTemplates").isArray()).isTrue();

        // Alphabetical key order at the top level - the determinism contract.
        List<String> keys = new ArrayList<>();
        Iterator<String> it = root.fieldNames();
        it.forEachRemaining(keys::add);
        assertThat(keys).isSorted();
    }

    @Test
    @DisplayName("Rejects null issuer/snapshotAt/apis")
    void rejectsNullInputs() {
        assertThatThrownBy(() -> ApiCatalogBundlePayload.canonicalBytes(
                1L, 1, null, SNAPSHOT_AT, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiCatalogBundlePayload.canonicalBytes(
                1L, 1, "cloud", null, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiCatalogBundlePayload.canonicalBytes(
                1L, 1, "cloud", SNAPSHOT_AT, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("gzip → gunzip round-trips arbitrary payload bytes")
    void gzipRoundTrip() {
        byte[] raw = ApiCatalogBundlePayload.canonicalBytes(5L, 1, "cloud", SNAPSHOT_AT,
                sampleApis(), sampleTemplates());

        byte[] gz = ApiCatalogBundlePayload.gzip(raw);
        assertThat(gz).isNotEqualTo(raw);
        assertThat(ApiCatalogBundlePayload.gunzip(gz)).isEqualTo(raw);
    }

    @Test
    @DisplayName("gzip actually compresses a repetitive catalog payload")
    void gzipCompresses() {
        // Real catalogs repeat key names hundreds of times - gzip must shrink them.
        String repetitive = "{\"description\":\"the same words over and over\"}".repeat(200);
        byte[] raw = repetitive.getBytes(StandardCharsets.UTF_8);
        assertThat(ApiCatalogBundlePayload.gzip(raw).length).isLessThan(raw.length / 4);
    }

    @Test
    @DisplayName("gunzip on non-gzip bytes throws (caught upstream as APPLY_FAILED)")
    void gunzipRejectsGarbage() {
        assertThatThrownBy(() -> ApiCatalogBundlePayload.gunzip(new byte[]{1, 2, 3}))
                .isInstanceOf(java.io.UncheckedIOException.class);
    }

    private static List<ApiRow> sampleApis() {
        UUID api1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID api2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID tool1 = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID tool2 = UUID.fromString("44444444-4444-4444-4444-444444444444");
        UUID p1 = UUID.fromString("55555555-5555-5555-5555-555555555555");
        UUID p2 = UUID.fromString("66666666-6666-6666-6666-666666666666");
        UUID r1 = UUID.fromString("77777777-7777-7777-7777-777777777777");

        ToolRow t1 = tool(tool1, "send-message",
                List.of(param(p1, "to"), param(p2, "body")),
                List.of(new ResponseRow(r1, "ok", null, null, "{}", "{}", null, 200, true, "json", true)),
                List.of(new ToolCredentialRow("slack", "oauth2", true, "authentication", null,
                        "{\"field\":\"access_token\"}")));
        ToolRow t2 = tool(tool2, "list-channels", List.of(), List.of(), List.of());

        return List.of(api(api1, "Slack", List.of(t1, t2)), api(api2, "Gmail", List.of()));
    }

    private static List<CredentialTemplateRow> sampleTemplates() {
        return List.of(
                new CredentialTemplateRow("slack", "oauth2", "Slack", null, "oauth", "oauth2",
                        null, null, null, "slack", "{\"client_id\":{}}", "{}", "{}"),
                new CredentialTemplateRow("gmail", "api_key", "Gmail", null, "key", "apikey",
                        null, null, null, "gmail", "{}", "{}", "{}"));
    }
}
