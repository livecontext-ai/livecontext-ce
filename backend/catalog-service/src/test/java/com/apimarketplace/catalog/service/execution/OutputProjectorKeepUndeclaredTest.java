package com.apimarketplace.catalog.service.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The projection mode that keeps what the seed's schema does not declare, used when the caller
 * explicitly selected fields.
 *
 * <p>Bug this exists for (2026-09-28): {@code get_user_timeline} on X called with
 * {@code tweet.fields=created_at,entities,attachments} answered green with {@code created_at}
 * and without {@code entities} or {@code attachments}. X had sent them; the twitter.json
 * outputSchema declares only id / text / author_id / created_at / public_metrics, so the
 * projection dropped them, and there was no way for the caller to learn it.
 */
class OutputProjectorKeepUndeclaredTest {

    private static final String DECLARED_ONLY =
        "[{\"key\":\"declared\",\"type\":\"string\",\"description\":\"x\"}]";

    private OutputProjector projector;

    @BeforeEach
    void setUp() {
        projector = new OutputProjector(new ObjectMapper());
    }

    private Map<?, ?> keep(Object raw, String schema) {
        Object out = projector.project(raw, schema, null, true);
        assertInstanceOf(Map.class, out);
        return (Map<?, ?>) out;
    }

    @Test
    @DisplayName("REGRESSION 2026-09-28: the real twitter.json timeline schema keeps the entities, "
            + "attachments and includes a caller selected with tweet.fields / expansions")
    void realTwitterTimelineSchemaKeepsSelectedFields() throws Exception {
        String schema = endpointSchema("twitter.json", "get_user_timeline");
        Map<String, Object> raw = xTimelineResponse();

        Map<?, ?> dropped = (Map<?, ?>) projector.project(raw, schema, null, false);
        Map<?, ?> droppedTweet = (Map<?, ?>) ((List<?>) dropped.get("data")).get(0);
        assertNull(droppedTweet.get("attachments"),
            "the default mode must stay exactly as before: this is the pre-fix behaviour, pinned");
        assertNull(dropped.get("includes"));

        Map<?, ?> kept = keep(raw, schema);
        Map<?, ?> tweet = (Map<?, ?>) ((List<?>) kept.get("data")).get(0);
        assertEquals("2104241296843874325", tweet.get("id"));
        assertEquals("2026-09-27T16:06:34.000Z", tweet.get("created_at"));
        assertEquals(Map.of("media_keys", List.of("13_1")), tweet.get("attachments"),
            "attachments was requested in tweet.fields; losing it is the reported bug");
        assertNotNull(tweet.get("entities"));
        Map<?, ?> metrics = (Map<?, ?>) tweet.get("public_metrics");
        assertEquals(12, ((Number) metrics.get("impression_count")).intValue(),
            "an undeclared child inside a DECLARED object is kept too, not only at the root");
        Map<?, ?> includes = (Map<?, ?>) kept.get("includes");
        assertEquals("video", ((Map<?, ?>) ((List<?>) includes.get("media")).get(0)).get("type"),
            "includes is where X puts the expanded media: it is the answer to expansions=");
        assertEquals(1, ((Number) ((Map<?, ?>) kept.get("meta")).get("result_count")).intValue());
    }

    @Test
    @DisplayName("declared fields come first and undeclared ones follow in the provider's order")
    void declaredFirstThenUndeclared() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("extra_b", 2);
        raw.put("declared", "keep");
        raw.put("extra_a", 1);

        Map<?, ?> out = keep(raw, DECLARED_ONLY);

        assertEquals(List.of("declared", "extra_b", "extra_a"), List.copyOf(out.keySet()));
    }

    @Test
    @DisplayName("a declared field is still projected by its schema, not copied raw over the top")
    void declaredFieldIsStillProjected() {
        String schema = "[{\"key\":\"user\",\"type\":\"object\",\"description\":\"\","
            + "\"children\":[{\"key\":\"name\",\"type\":\"string\",\"description\":\"\"}]}]";
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("name", "Ada");
        user.put("bio", "kept in this mode");

        Map<?, ?> out = keep(Map.of("user", user), schema);

        assertEquals(Map.of("name", "Ada", "bio", "kept in this mode"), out.get("user"));
    }

    @Test
    @DisplayName("a declared field the provider sent as null stays absent, as in the default mode")
    void declaredNullStaysAbsent() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("declared", null);
        raw.put("other", "x");

        Map<?, ?> out = keep(raw, DECLARED_ONLY);

        assertFalse(out.containsKey("declared"),
            "a declared key must not come back in through the undeclared pass");
        assertEquals("x", out.get("other"));
    }

    @Test
    @DisplayName("a declared object with no children is returned whole, as in the default mode")
    void declaredObjectWithoutChildrenIsWhole() {
        String schema = "[{\"key\":\"meta\",\"type\":\"object\",\"description\":\"\"}]";
        Map<String, Object> meta = Map.of("a", 1, "b", Map.of("c", 2));

        Map<?, ?> out = keep(Map.of("meta", meta, "extra", true), schema);

        assertEquals(meta, out.get("meta"));
        assertEquals(true, out.get("extra"));
    }

    @Test
    @DisplayName("a declared array of scalars is returned as is, and its undeclared siblings are kept")
    void declaredScalarArray() {
        String schema = "[{\"key\":\"tags\",\"type\":\"array\",\"description\":\"\"}]";

        Map<?, ?> out = keep(Map.of("tags", List.of("a", "b"), "count", 2), schema);

        assertEquals(List.of("a", "b"), out.get("tags"));
        assertEquals(2, out.get("count"));
    }

    @Test
    @DisplayName("an entry with a blank type projects nothing, so the body key it names is kept as undeclared")
    void blankTypeEntryShadowsNothing() {
        String schema = "[{\"key\":\"loose\",\"type\":\"\",\"description\":\"\"},"
            + "{\"key\":\"declared\",\"type\":\"string\",\"description\":\"\"}]";

        Map<?, ?> out = keep(Map.of("loose", "sent", "declared", "d"), schema);

        assertEquals("sent", out.get("loose"),
            "the projecting loop skips a blank-type entry; treating it as declared would lose the value in BOTH passes");
        assertEquals("d", out.get("declared"));
    }

    @Test
    @DisplayName("an array-root response keeps undeclared fields on every element")
    void arrayRootKeepsUndeclaredPerElement() {
        List<Map<String, Object>> raw = List.of(
            Map.of("declared", "a", "extra", 1),
            Map.of("declared", "b", "extra", 2));

        Object out = projector.project(raw, DECLARED_ONLY, null, true);

        assertEquals(List.of(Map.of("declared", "a", "extra", 1), Map.of("declared", "b", "extra", 2)), out);
    }

    @Test
    @DisplayName("a root-declared field names no body key, so a body key of the same name is still kept")
    void rootDeclarationShadowsNothing() {
        String schema = "[{\"key\":\"whole\",\"type\":\"object\",\"root\":true,\"description\":\"\"}]";
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("a", 1);

        Map<?, ?> out = keep(raw, schema);

        assertEquals(Map.of("a", 1), out.get("whole"));
        assertEquals(1, out.get("a"));
    }

    @Test
    @DisplayName("a header-sourced field still reads the header only: a body key of that name is not kept")
    void headerSourcedFieldStillIgnoresTheBody() {
        String schema = "[{\"key\":\"etag\",\"type\":\"string\",\"source\":\"header\"}]";

        Object absent = projector.project(Map.of("id", "x", "etag", "BODY"), schema, Map.of(), true);
        Object present = projector.project(Map.of("id", "x", "etag", "BODY"), schema, Map.of("ETag", "H"), true);

        assertEquals(Map.of("id", "x"), absent,
            "keeping undeclared fields must not reopen the body fallback the header rule forbids");
        assertEquals(Map.of("id", "x", "etag", "H"), present);
    }

    @Test
    @DisplayName("a FileRef still passes through whole in this mode")
    void fileRefUntouched() {
        String schema = "[{\"key\":\"file\",\"type\":\"object\",\"description\":\"\","
            + "\"children\":[{\"key\":\"path\",\"type\":\"string\",\"description\":\"\"}]}]";
        Map<String, Object> ref = Map.of("_type", "file", "id", "opaque-1", "path", "p", "name", "n");

        Map<?, ?> out = keep(Map.of("file", ref), schema);

        assertEquals(ref, out.get("file"));
    }

    @Test
    @DisplayName("no schema still returns the raw response untouched")
    void noSchemaIsStillANoOp() {
        Map<String, Object> raw = Map.of("a", 1);

        assertTrue(raw == projector.project(raw, null, null, true));
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────

    /** The shape X returns for tweet.fields=created_at,entities,attachments,public_metrics + expansions=attachments.media_keys. */
    private static Map<String, Object> xTimelineResponse() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("retweet_count", 0);
        metrics.put("like_count", 1);
        metrics.put("impression_count", 12);

        Map<String, Object> tweet = new LinkedHashMap<>();
        tweet.put("id", "2104241296843874325");
        tweet.put("text", "Three hundred CVs for one job. https://t.co/1iHqjrz84Y");
        tweet.put("created_at", "2026-09-27T16:06:34.000Z");
        tweet.put("public_metrics", metrics);
        tweet.put("attachments", Map.of("media_keys", List.of("13_1")));
        tweet.put("entities", Map.of("hashtags", List.of(Map.of("tag", "automation"))));

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("data", List.of(tweet));
        raw.put("includes", Map.of("media", List.of(Map.of("media_key", "13_1", "type", "video"))));
        raw.put("meta", Map.of("result_count", 1));
        return raw;
    }

    private static String endpointSchema(String seed, String endpoint) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(Files.readString(seedPath(seed)));
        for (JsonNode ep : root.path("endpoints")) {
            if (endpoint.equals(ep.path("name").asText())) {
                return mapper.writeValueAsString(ep.path("outputSchema"));
            }
        }
        throw new IllegalStateException(endpoint + " not found in " + seed);
    }

    private static Path seedPath(String seed) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve("scripts/api-migrations/" + seed);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not locate scripts/api-migrations/" + seed);
    }
}
