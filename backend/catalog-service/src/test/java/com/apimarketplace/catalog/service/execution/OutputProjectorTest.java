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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates that the typed OutputProjector enforces the tool's declared output_schema:
 * - drops undeclared fields
 * - recurses into nested object/array children
 * - returns raw response unchanged when no schema is declared
 * - tolerates malformed schemas gracefully
 */
class OutputProjectorTest {

    private OutputProjector projector;

    @BeforeEach
    void setUp() {
        projector = new OutputProjector(new ObjectMapper());
    }

    @Test
    @DisplayName("returns raw response unchanged when no schema is declared (legacy path)")
    void noSchemaIsNoOp() {
        Map<String, Object> raw = Map.of("a", 1, "b", "x", "c", List.of(1, 2, 3));
        assertSame(raw, projector.project(raw, null));
        assertSame(raw, projector.project(raw, ""));
    }

    @Test
    @DisplayName("returns null when raw response is null")
    void nullPassesThrough() {
        assertNull(projector.project(null, "[{\"key\":\"a\",\"type\":\"string\",\"description\":\"x\"}]"));
    }

    @Test
    @DisplayName("drops fields not declared in the schema")
    void dropsUndeclaredFields() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("declared", "keep");
        raw.put("undeclared", "drop");
        raw.put("also_undeclared", 42);

        String schema = "[{\"key\":\"declared\",\"type\":\"string\",\"description\":\"x\"}]";
        Object out = projector.project(raw, schema);

        assertInstanceOf(Map.class, out);
        Map<?, ?> projected = (Map<?, ?>) out;
        assertEquals(1, projected.size());
        assertEquals("keep", projected.get("declared"));
    }

    @Test
    @DisplayName("recurses into object children")
    void recursesIntoObjectChildren() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("title", "hello");
        nested.put("hidden", "drop");

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("metadata", nested);
        raw.put("noise", "drop");

        String schema = "[{" +
            "\"key\":\"metadata\",\"type\":\"object\",\"description\":\"\"," +
            "\"children\":[{\"key\":\"title\",\"type\":\"string\",\"description\":\"\"}]" +
            "}]";

        Object out = projector.project(raw, schema);
        Map<?, ?> projected = (Map<?, ?>) out;
        assertEquals(1, projected.size());
        Map<?, ?> innerProjected = (Map<?, ?>) projected.get("metadata");
        assertEquals(1, innerProjected.size());
        assertEquals("hello", innerProjected.get("title"));
    }

    @Test
    @DisplayName("recurses into array element children")
    void recursesIntoArrayChildren() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("results", List.of(
            Map.of("id", "1", "title", "a", "drop_me", "x"),
            Map.of("id", "2", "title", "b", "drop_me", "y")
        ));

        String schema = "[{" +
            "\"key\":\"results\",\"type\":\"array\",\"description\":\"\"," +
            "\"children\":[" +
            "  {\"key\":\"id\",\"type\":\"string\",\"description\":\"\"}," +
            "  {\"key\":\"title\",\"type\":\"string\",\"description\":\"\"}" +
            "]}]";

        Object out = projector.project(raw, schema);
        Map<?, ?> projected = (Map<?, ?>) out;
        List<?> items = (List<?>) projected.get("results");
        assertEquals(2, items.size());
        Map<?, ?> first = (Map<?, ?>) items.get(0);
        assertEquals(2, first.size());
        assertEquals("1", first.get("id"));
        assertEquals("a", first.get("title"));
        assertNull(first.get("drop_me"));
    }

    @Test
    @DisplayName("preserves fileRef structured value")
    void fileRefPassthrough() {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("_type", "file");
        file.put("path", "users/foo/img.png");
        file.put("name", "img.png");
        file.put("mimeType", "image/png");
        file.put("size", 12345);

        Map<String, Object> raw = Map.of("image", file, "ignore_me", "x");
        String schema = "[{\"key\":\"image\",\"type\":\"fileRef\",\"description\":\"img\"}]";

        Object out = projector.project(raw, schema);
        Map<?, ?> projected = (Map<?, ?>) out;
        Map<?, ?> projectedFile = (Map<?, ?>) projected.get("image");
        assertEquals("file", projectedFile.get("_type"));
        assertEquals("img.png", projectedFile.get("name"));
        assertEquals("image/png", projectedFile.get("mimeType"));
    }

    @Test
    @DisplayName("preserves a FileRef's opaque id when the file field is declared object+children WITHOUT id "
            + "(regression: WhatsApp/catalog-binary FileRefs shipped id-less post opaque-URL cutover)")
    void fileRefIdSurvivesObjectChildrenProjection() {
        // Prod bug repro: a tool whose output_schema declares the file field as `object` with children
        // listing only the old 5 sub-fields. Before the fix, projectAgainstFields filtered the FileRef
        // to those 5 in children order, dropping the opaque `id` - and the by-id file URL needs it, so
        // the file rendered broken. (WhatsApp download-media, run_<id>, id 4224a327…)
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("_type", "file");
        file.put("path", "1/general/catalog-binary/abc_media.json");
        file.put("name", "media.json");
        file.put("mimeType", "application/json");
        file.put("size", 176);
        file.put("id", "4224a327-9365-4c99-8ee7-e96da21ccdfe");

        Map<String, Object> raw = Map.of("media", file);
        // file field declared as object with the OLD 5 children (observed order), NO id child.
        String schema = "[{\"key\":\"media\",\"type\":\"object\",\"description\":\"the media\",\"children\":["
            + "{\"key\":\"name\",\"type\":\"string\",\"description\":\"\"},"
            + "{\"key\":\"path\",\"type\":\"string\",\"description\":\"\"},"
            + "{\"key\":\"size\",\"type\":\"number\",\"description\":\"\"},"
            + "{\"key\":\"_type\",\"type\":\"string\",\"description\":\"\"},"
            + "{\"key\":\"mimeType\",\"type\":\"string\",\"description\":\"\"}"
            + "]}]";

        Map<?, ?> projectedFile = (Map<?, ?>) ((Map<?, ?>) projector.project(raw, schema)).get("media");
        assertEquals("4224a327-9365-4c99-8ee7-e96da21ccdfe", projectedFile.get("id"),
            "the opaque id MUST survive the object+children projection - the by-id file URL is built from it");
        assertEquals("file", projectedFile.get("_type"));
        assertEquals("media.json", projectedFile.get("name"));
    }

    @Test
    @DisplayName("preserves FileRef id inside an array declared with non-id children")
    void fileRefIdSurvivesArrayChildrenProjection() {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("_type", "file");
        file.put("path", "p");
        file.put("name", "a.png");
        file.put("mimeType", "image/png");
        file.put("size", 1);
        file.put("id", "id-xyz");

        Map<String, Object> raw = Map.of("images", List.of(file));
        String schema = "[{\"key\":\"images\",\"type\":\"array\",\"description\":\"\",\"children\":["
            + "{\"key\":\"name\",\"type\":\"string\",\"description\":\"\"},"
            + "{\"key\":\"path\",\"type\":\"string\",\"description\":\"\"}]}]";

        List<?> items = (List<?>) ((Map<?, ?>) projector.project(raw, schema)).get("images");
        assertEquals("id-xyz", ((Map<?, ?>) items.get(0)).get("id"),
            "FileRef id must survive array-children projection too");
    }

    @Test
    @DisplayName("malformed schema falls back to raw response")
    void malformedSchemaFallsBack() {
        Map<String, Object> raw = Map.of("a", 1);
        Object out = projector.project(raw, "{not valid json");
        assertSame(raw, out);
    }

    @Test
    @DisplayName("schema that is not an array falls back to raw response")
    void schemaNotArrayFallsBack() {
        Map<String, Object> raw = Map.of("a", 1);
        Object out = projector.project(raw, "{\"key\":\"a\"}");
        assertSame(raw, out);
    }

    @Test
    @DisplayName("missing fields are simply omitted from projected output")
    void missingDeclaredFieldsOmitted() {
        Map<String, Object> raw = Map.of("present", "x");
        String schema = "[" +
            "{\"key\":\"present\",\"type\":\"string\",\"description\":\"\"}," +
            "{\"key\":\"absent\",\"type\":\"string\",\"description\":\"\"}" +
            "]";
        Object out = projector.project(raw, schema);
        Map<?, ?> projected = (Map<?, ?>) out;
        assertEquals(1, projected.size());
        assertEquals("x", projected.get("present"));
        assertFalse(projected.containsKey("absent"));
    }

    @Test
    @DisplayName("root field projects the whole JSON object under one declared key")
    void rootFieldProjectsWholeObjectUnderDeclaredKey() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("output", "hello");
        raw.put("latency_ms", 42);
        raw.put("model_specific", Map.of("score", 0.98));

        String schema = "[{" +
            "\"key\":\"model_output\"," +
            "\"type\":\"object\"," +
            "\"description\":\"Dynamic model response\"," +
            "\"root\":true" +
            "}]";

        Object out = projector.project(raw, schema);

        assertInstanceOf(Map.class, out);
        Map<?, ?> projected = (Map<?, ?>) out;
        assertEquals(1, projected.size());
        Map<?, ?> modelOutput = (Map<?, ?>) projected.get("model_output");
        assertEquals("hello", modelOutput.get("output"));
        assertEquals(42, modelOutput.get("latency_ms"));
        assertEquals(Map.of("score", 0.98), modelOutput.get("model_specific"));
    }

    @Test
    @DisplayName("rootArrayWithFlatFieldSchemaProjectsEachElement - bug regression: list endpoints (e.g. JSONPlaceholder /comments) returned an empty Map")
    void rootArrayWithFlatFieldSchemaProjectsEachElement() {
        // Regression for the 2026-05-04 bug: an API returning a JSON array at
        // the root paired with a flat output_schema (the shape of one element)
        // produced an empty Map instead of a List of projected elements,
        // making `data` invisible in the catalog response.
        List<Map<String, Object>> raw = List.of(
                new LinkedHashMap<>(Map.of("postId", 1, "id", 1, "name", "alice", "email", "a@x.com", "body", "first", "extraField", "dropped")),
                new LinkedHashMap<>(Map.of("postId", 1, "id", 2, "name", "bob",   "email", "b@x.com", "body", "second", "extraField", "dropped"))
        );
        String schema = "[" +
                "{\"key\":\"postId\",\"type\":\"number\",\"description\":\"postId\"}," +
                "{\"key\":\"id\",\"type\":\"number\",\"description\":\"id\"}," +
                "{\"key\":\"name\",\"type\":\"string\",\"description\":\"name\"}," +
                "{\"key\":\"email\",\"type\":\"string\",\"description\":\"email\"}," +
                "{\"key\":\"body\",\"type\":\"string\",\"description\":\"body\"}" +
                "]";

        Object out = projector.project(raw, schema);

        assertTrue(out instanceof List, "root-array response must project to a List, not an empty Map");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> projected = (List<Map<String, Object>>) out;
        assertEquals(2, projected.size());
        // Each element keeps its declared fields and drops `extraField`
        assertEquals(1, projected.get(0).get("id"));
        assertEquals("alice", projected.get(0).get("name"));
        assertFalse(projected.get(0).containsKey("extraField"));
        assertEquals("bob", projected.get(1).get("name"));
    }

    @Test
    @DisplayName("rootArrayWithNonObjectElementsPassesThrough - primitive array (e.g. ['a','b']) preserved as-is")
    void rootArrayWithNonObjectElementsPassesThrough() {
        List<String> raw = List.of("alpha", "beta", "gamma");
        String schema = "[{\"key\":\"name\",\"type\":\"string\",\"description\":\"\"}]";

        Object out = projector.project(raw, schema);

        assertTrue(out instanceof List);
        @SuppressWarnings("unchecked")
        List<Object> projected = (List<Object>) out;
        assertEquals(List.of("alpha", "beta", "gamma"), projected,
                "non-object elements must be preserved verbatim");
    }

    // ---------------------------------------------------------------------------------------------
    // Telegram get_updates callback_query regression (inline-button clicks were stripped at runtime)
    //
    // Bug: get_updates.outputSchema.result[] declared only update_id + message, so a callback_query
    // update (sent when a user presses an inline keyboard button) was projected down to just
    // update_id - the .data field carrying "OUI:<code>" / "NON:<code>" never reached the workflow,
    // the branch was skipped and the button "blinked" forever. Fix = declare callback_query (and the
    // other Update variants) in result[].children so the projector keeps them.
    // ---------------------------------------------------------------------------------------------

    /** Telegram Update.message sub-schema, shared by the two inline tests below. */
    private static final String MESSAGE_CHILD =
        "{\"key\":\"message\",\"type\":\"object\",\"description\":\"msg\",\"children\":["
        + "{\"key\":\"message_id\",\"type\":\"number\",\"description\":\"\"},"
        + "{\"key\":\"text\",\"type\":\"string\",\"description\":\"\"}]}";

    @Test
    @DisplayName("callback_query.data is DROPPED when result[] schema omits callback_query (pre-fix behavior)")
    void callbackQueryDataDroppedWhenSchemaOmitsCallbackQuery() {
        // Reproduces the pre-fix schema: result[] children = [update_id, message] only.
        String preFixSchema = "[{\"key\":\"result\",\"type\":\"array\",\"description\":\"updates\",\"children\":["
            + "{\"key\":\"update_id\",\"type\":\"number\",\"description\":\"\"},"
            + MESSAGE_CHILD + "]}]";

        Object out = projector.project(callbackQueryResponse(), preFixSchema);

        Map<?, ?> firstUpdate = firstUpdate(out);
        assertEquals(1, firstUpdate.size(),
            "pre-fix: only update_id survives, callback_query is stripped because it is undeclared");
        assertTrue(firstUpdate.containsKey("update_id"));
        assertFalse(firstUpdate.containsKey("callback_query"),
            "the whole callback_query (including .data) is dropped before the fix");
    }

    @Test
    @DisplayName("callback_query.data SURVIVES when result[] schema declares callback_query (post-fix behavior)")
    void callbackQueryDataSurvivesWhenSchemaDeclaresCallbackQuery() {
        String fixedSchema = "[{\"key\":\"result\",\"type\":\"array\",\"description\":\"updates\",\"children\":["
            + "{\"key\":\"update_id\",\"type\":\"number\",\"description\":\"\"},"
            + MESSAGE_CHILD + ","
            + "{\"key\":\"callback_query\",\"type\":\"object\",\"description\":\"cbq\",\"children\":["
            + "  {\"key\":\"id\",\"type\":\"string\",\"description\":\"\"},"
            + "  {\"key\":\"data\",\"type\":\"string\",\"description\":\"\"},"
            + "  {\"key\":\"chat_instance\",\"type\":\"string\",\"description\":\"\"}]}"
            + "]}]";

        Object out = projector.project(callbackQueryResponse(), fixedSchema);

        Map<?, ?> firstUpdate = firstUpdate(out);
        assertTrue(firstUpdate.containsKey("callback_query"), "callback_query survives once declared");
        Map<?, ?> cbq = (Map<?, ?>) firstUpdate.get("callback_query");
        assertEquals("OUI:abc123", cbq.get("data"), "the critical .data field must survive the projection");
        assertEquals("cbq-1", cbq.get("id"));
        assertNull(cbq.get("secret_field"), "undeclared sub-fields are still dropped (strict schema preserved)");
    }

    @Test
    @DisplayName("the REAL telegram.json get_updates schema preserves a callback_query update's .data "
            + "(regression guard tied to the seed file - fails if callback_query is removed from result[])")
    void realTelegramGetUpdatesSchemaPreservesCallbackQueryData() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode telegram = mapper.readTree(Files.readString(telegramJsonPath()));

        JsonNode getUpdatesSchema = null;
        for (JsonNode ep : telegram.path("endpoints")) {
            if ("get_updates".equals(ep.path("name").asText())) {
                getUpdatesSchema = ep.path("outputSchema");
                break;
            }
        }
        assertNotNull(getUpdatesSchema, "get_updates endpoint must exist in telegram.json");
        String schemaJson = mapper.writeValueAsString(getUpdatesSchema);

        Object out = projector.project(callbackQueryResponse(), schemaJson);

        @SuppressWarnings("unchecked")
        Map<String, Object> projected = (Map<String, Object>) out;
        List<?> result = (List<?>) projected.get("result");
        assertEquals(1, result.size());
        Map<?, ?> update = (Map<?, ?>) result.get(0);
        assertEquals(123L, ((Number) update.get("update_id")).longValue());
        Map<?, ?> cbq = (Map<?, ?>) update.get("callback_query");
        assertNotNull(cbq, "the seed schema must declare callback_query so it survives the projector");
        assertEquals("OUI:abc123", cbq.get("data"),
            "{{...result[].callback_query.data}} must resolve - this is exactly what was empty before the fix");
        Map<?, ?> cbqMessage = (Map<?, ?>) cbq.get("message");
        assertNotNull(cbqMessage, "callback_query.message reuses the message sub-schema");
        assertEquals(55L, ((Number) cbqMessage.get("message_id")).longValue());
    }

    @Test
    @DisplayName("real telegram.json schema preserves a modeled variant (my_chat_member) AND a passthrough "
            + "variant (inline_query whole object), and still drops undeclared update-level fields")
    void realTelegramGetUpdatesSchemaPreservesOtherVariants() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode telegram = mapper.readTree(Files.readString(telegramJsonPath()));
        JsonNode getUpdatesSchema = null;
        for (JsonNode ep : telegram.path("endpoints")) {
            if ("get_updates".equals(ep.path("name").asText())) {
                getUpdatesSchema = ep.path("outputSchema");
                break;
            }
        }
        String schemaJson = mapper.writeValueAsString(getUpdatesSchema);

        // Update 1: my_chat_member (MODELED with children) - status/user survive, undeclared sibling drops.
        Map<String, Object> newMember = new LinkedHashMap<>();
        newMember.put("status", "member");
        newMember.put("user", Map.of("id", 7, "is_bot", false, "first_name", "Ann"));
        Map<String, Object> myChatMember = new LinkedHashMap<>();
        myChatMember.put("date", 1700);
        myChatMember.put("new_chat_member", newMember);
        myChatMember.put("invite_link", "https://t.me/+secret"); // undeclared on the modeled variant -> dropped
        Map<String, Object> update1 = new LinkedHashMap<>();
        update1.put("update_id", 200);
        update1.put("my_chat_member", myChatMember);

        // Update 2: inline_query (PASSTHROUGH object, no children) - the WHOLE object survives verbatim.
        Map<String, Object> inlineQuery = new LinkedHashMap<>();
        inlineQuery.put("id", "iq-9");
        inlineQuery.put("query", "weather paris");
        inlineQuery.put("offset", "");
        Map<String, Object> update2 = new LinkedHashMap<>();
        update2.put("update_id", 201);
        update2.put("inline_query", inlineQuery);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("result", List.of(update1, update2));

        @SuppressWarnings("unchecked")
        Map<String, Object> projected = (Map<String, Object>) projector.project(response, schemaJson);
        List<?> result = (List<?>) projected.get("result");
        assertEquals(2, result.size());

        Map<?, ?> mcm = (Map<?, ?>) ((Map<?, ?>) result.get(0)).get("my_chat_member");
        assertNotNull(mcm, "modeled variant my_chat_member must survive");
        assertEquals("member", ((Map<?, ?>) mcm.get("new_chat_member")).get("status"));
        assertFalse(mcm.containsKey("invite_link"),
            "a sub-field NOT declared on the modeled variant is still dropped (strict schema)");

        Map<?, ?> iq = (Map<?, ?>) ((Map<?, ?>) result.get(1)).get("inline_query");
        assertNotNull(iq, "passthrough variant inline_query must survive");
        assertEquals("weather paris", iq.get("query"),
            "a passthrough object (declared object with no children) preserves its whole payload verbatim");
        assertEquals("iq-9", iq.get("id"));
    }

    /** A getUpdates response carrying a single callback_query update (inline-button click). */
    private Object callbackQueryResponse() {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("message_id", 55);
        message.put("text", "Approve?");

        Map<String, Object> callbackQuery = new LinkedHashMap<>();
        callbackQuery.put("id", "cbq-1");
        callbackQuery.put("data", "OUI:abc123");
        callbackQuery.put("chat_instance", "ci-1");
        callbackQuery.put("message", message);
        callbackQuery.put("secret_field", "should-be-dropped"); // undeclared - must not survive

        Map<String, Object> update = new LinkedHashMap<>();
        update.put("update_id", 123);
        update.put("callback_query", callbackQuery);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("result", List.of(update));
        return response;
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> firstUpdate(Object projected) {
        Map<String, Object> map = (Map<String, Object>) projected;
        List<?> result = (List<?>) map.get("result");
        assertEquals(1, result.size());
        return (Map<?, ?>) result.get(0);
    }

    /** Locate scripts/api-migrations/telegram.json by walking up from the test working directory. */
    private static Path telegramJsonPath() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve("scripts/api-migrations/telegram.json");
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
            "could not locate scripts/api-migrations/telegram.json from " + Path.of("").toAbsolutePath());
    }
}
