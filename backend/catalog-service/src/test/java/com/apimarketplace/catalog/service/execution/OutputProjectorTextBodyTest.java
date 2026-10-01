package com.apimarketplace.catalog.service.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a body the projection cannot read field by field is placed: a text answer, a JSON Lines
 * answer, or the file reference the base64 dehydrator made of a text. Object projection used to
 * turn each of them into an empty map, reported as a success.
 */
@DisplayName("OutputProjector - text, JSON Lines and dehydrated bodies are placed, never dropped")
class OutputProjectorTextBodyTest {

    private final OutputProjector projector = new OutputProjector(new ObjectMapper());

    private static final String CSV = "a,b\n1,2\n";

    @Test
    @DisplayName("a single string field receives the whole text under its key")
    void singleStringFieldTakesTheText() {
        Object out = projector.project(CSV, "[{\"key\":\"csv\",\"type\":\"string\",\"description\":\"\"}]");

        assertThat(out).isEqualTo(Map.of("csv", CSV));
    }

    @Test
    @DisplayName("a multi-field schema returns the raw text, nothing invented")
    void multiFieldSchemaReturnsRawText() {
        Object out = projector.project(CSV, "[{\"key\":\"a\",\"type\":\"string\"},{\"key\":\"b\",\"type\":\"string\"}]");

        assertThat(out).isEqualTo(CSV);
    }

    @Test
    @DisplayName("a single OBJECT field (the AWS XML case) returns the raw text")
    void singleObjectFieldReturnsRawText() {
        Object out = projector.project("<X/>", "[{\"key\":\"ResponseMetadata\",\"type\":\"object\"}]");

        assertThat(out).isEqualTo("<X/>");
    }

    @Test
    @DisplayName("header-sourced fields do not count as body fields, and are still added beside the text")
    void headerFieldsBesideTheText() {
        String schema = "[{\"key\":\"csv\",\"type\":\"string\"},"
                + "{\"key\":\"ETag\",\"type\":\"string\",\"source\":\"header\"}]";

        Object out = projector.project(CSV, schema, Map.of("etag", "\"v1\""));

        assertThat(out).isEqualTo(Map.of("csv", CSV, "ETag", "\"v1\""));
    }

    @Test
    @DisplayName("with no single string body field, the text goes under data and the header field beside it")
    void headerFieldsWithRawText() {
        String schema = "[{\"key\":\"ETag\",\"type\":\"string\",\"source\":\"header\"}]";

        Object out = projector.project(CSV, schema, Map.of("ETag", "\"v1\""));

        assertThat(out).isEqualTo(Map.of("data", CSV, "ETag", "\"v1\""));
    }

    @Test
    @DisplayName("entries with a blank key or type are skipped, as projectAgainstFields skips them")
    void blankEntriesSkipped() {
        String schema = "[{\"key\":\"\",\"type\":\"string\"},{\"key\":\"x\",\"type\":\"\"},"
                + "{\"key\":\"csv\",\"type\":\"string\"}]";

        assertThat(projector.project(CSV, schema)).isEqualTo(Map.of("csv", CSV));
    }

    @Test
    @DisplayName("a root string field names the whole body, so the text lands under it")
    void rootStringField() {
        Object out = projector.project(CSV, "[{\"key\":\"body\",\"type\":\"string\",\"root\":true}]");

        assertThat(out).isEqualTo(Map.of("body", CSV));
    }

    @Test
    @DisplayName("JSON Lines + a single string field: the JSONL text, not [{}, {}]")
    void jsonLinesUnderSingleString() {
        String text = "{\"id\":\"1\"}\n{\"id\":\"2\"}\n";
        JsonLines lines = new JsonLines(List.of(Map.of("id", "1"), Map.of("id", "2")), text);

        Object out = projector.project(lines, "[{\"key\":\"documents\",\"type\":\"string\"}]");

        assertThat(out).isEqualTo(Map.of("documents", text));
    }

    @Test
    @DisplayName("a one-document JSON Lines body + a single string field: the text too")
    void oneDocumentJsonLinesUnderSingleString() {
        String text = "{\"id\":\"1\"}\n";
        JsonLines lines = new JsonLines(List.of(Map.of("id", "1")), text);

        Object out = projector.project(lines, "[{\"key\":\"documents\",\"type\":\"string\"}]");

        assertThat(out).isEqualTo(Map.of("documents", text));
    }

    @Test
    @DisplayName("JSON Lines + a schema describing one record: each record is projected, like a root array")
    void jsonLinesProjectedAsRecords() {
        JsonLines lines = new JsonLines(
                List.of(Map.of("id", "1", "x", 9, "y", 0), Map.of("id", "2", "x", 8, "y", 0)), "ignored");

        Object out = projector.project(lines,
                "[{\"key\":\"id\",\"type\":\"string\"},{\"key\":\"x\",\"type\":\"number\"}]");

        assertThat(out).isEqualTo(List.of(Map.of("id", "1", "x", 9), Map.of("id", "2", "x", 8)));
    }

    @Test
    @DisplayName("placeUnparsed: a file reference goes under the single string key, else under data")
    void placeUnparsedFileRef() {
        Map<String, Object> ref = Map.of("_type", "file", "path", "p");

        assertThat(projector.placeUnparsed(ref, "[{\"key\":\"content\",\"type\":\"string\"}]"))
                .isEqualTo(Map.of("content", ref));
        assertThat(projector.placeUnparsed(ref, "[{\"key\":\"Result\",\"type\":\"object\"}]"))
                .isEqualTo(Map.of("data", ref));
    }
}
