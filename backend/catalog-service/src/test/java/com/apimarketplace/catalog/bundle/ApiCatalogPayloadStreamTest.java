package com.apimarketplace.catalog.bundle;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * On a well-formed payload the streaming reader must say exactly what the old whole-document
 * parse said, so the merge's input is unchanged and only the memory profile moves. On a malformed
 * one it is deliberately STRICTER (see the class Javadoc): those tests are named as tightenings.
 */
@DisplayName("ApiCatalogPayloadStream - reads a catalog payload without holding it whole")
class ApiCatalogPayloadStreamTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ApiCatalogPayloadStream stream(String json) {
        return new ApiCatalogPayloadStream(mapper,
                ApiCatalogBundlePayload.gzip(json.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<Map<String, Object>> drain(Iterable<Map<String, Object>> apis) {
        List<Map<String, Object>> out = new ArrayList<>();
        apis.forEach(out::add);
        return out;
    }

    @Test
    @DisplayName("scan counts the APIs and returns templates and prices, whatever the key order")
    void scanReadsEverythingButTheApis() throws IOException {
        ApiCatalogPayloadStream s = stream("{\"generationPrices\":[{\"toolSlug\":\"flux\"}],"
                + "\"snapshotAt\":\"2026-09-03\",\"credentialTemplates\":[{\"credentialName\":\"slack\"}],"
                + "\"apis\":[{\"id\":\"a\",\"tools\":[{\"x\":[1,2,{\"y\":null}]}]},{\"id\":\"b\"}],\"version\":7}");

        ApiCatalogPayloadStream.Scan scan = s.scan();

        assertThat(scan.apisIsArray()).isTrue();
        assertThat(scan.apiCount()).isEqualTo(2);
        assertThat(scan.nonObjectApis()).isZero();
        assertThat(scan.templates()).extracting(m -> m.get("credentialName")).containsExactly("slack");
        assertThat(scan.prices()).extracting(m -> m.get("toolSlug")).containsExactly("flux");
    }

    @Test
    @DisplayName("apis() yields every API in document order, as the same maps a whole parse gave")
    void apisYieldInOrderWithSameShape() throws IOException {
        String json = "{\"apis\":[{\"id\":\"a\",\"n\":1,\"tools\":[{\"t\":true}]},{\"id\":\"b\",\"n\":2.5}],"
                + "\"credentialTemplates\":[]}";
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> whole = (List<Map<String, Object>>) mapper.readValue(json, Map.class).get("apis");

        assertThat(drain(stream(json).apis())).isEqualTo(whole);
    }

    @Test
    @DisplayName("Each iterator() is a fresh pass, and APIs after the templates key are still found")
    void freshPassPerIterator() {
        ApiCatalogPayloadStream s = stream("{\"credentialTemplates\":[{\"c\":1}],\"apis\":[{\"id\":\"a\"},{\"id\":\"b\"}]}");

        assertThat(drain(s.apis())).hasSize(2);
        Iterator<Map<String, Object>> again = s.apis().iterator();
        assertThat(again.next()).containsEntry("id", "a");
        assertThat(again.next()).containsEntry("id", "b");
        assertThat(again.hasNext()).isFalse();
    }

    @Test
    @DisplayName("Absent optional keys: templates empty, prices null ('says nothing'), exactly as before")
    void absentOptionalKeys() throws IOException {
        ApiCatalogPayloadStream.Scan scan = stream("{\"apis\":[{\"id\":\"a\"}]}").scan();

        assertThat(scan.templates()).isEmpty();
        assertThat(scan.prices()).isNull();
    }

    @Test
    @DisplayName("Missing or non-array 'apis' is reported as not an array; an empty one as zero APIs")
    void apisShape() throws IOException {
        assertThat(stream("{\"credentialTemplates\":[]}").scan().apisIsArray()).isFalse();
        assertThat(stream("{\"apis\":{\"id\":\"a\"}}").scan().apisIsArray()).isFalse();
        assertThat(stream("{\"apis\":null}").scan().apisIsArray()).isFalse();
        ApiCatalogPayloadStream.Scan empty = stream("{\"apis\":[]}").scan();
        assertThat(empty.apisIsArray()).isTrue();
        assertThat(empty.apiCount()).isZero();
        assertThat(drain(stream("{\"credentialTemplates\":[]}").apis())).isEmpty();
    }

    @Test
    @DisplayName("Entries of 'apis' that are not objects are counted, so the applier can refuse them")
    void nonObjectApisCounted() throws IOException {
        ApiCatalogPayloadStream.Scan scan = stream("{\"apis\":[{\"id\":\"a\"},\"oops\",[1],null]}").scan();

        assertThat(scan.apiCount()).isEqualTo(4);
        assertThat(scan.nonObjectApis()).isEqualTo(3);
    }

    @Test
    @DisplayName("Malformed JSON anywhere, even deep inside an API, fails the scan before any write")
    void malformedFailsScan() {
        assertThatThrownBy(() -> stream("{\"apis\":[{\"id\":\"a\",\"tools\":[{\"x\":}]}]}").scan())
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> stream("[1,2]").scan()).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("TIGHTENING: data after the root object is refused (the old whole parse ignored it)")
    void trailingDataRefused() {
        assertThatThrownBy(() -> stream("{\"apis\":[]} {\"extra\":1}").scan()).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("TIGHTENING: a second 'apis' key is flagged (the old parse silently kept the last one)")
    void duplicateApisFlagged() throws IOException {
        assertThat(stream("{\"apis\":[{\"id\":\"a\"}],\"apis\":[{\"id\":\"b\"}]}").scan().duplicateApis()).isTrue();
        assertThat(stream("{\"apis\":[{\"id\":\"a\"}]}").scan().duplicateApis()).isFalse();
    }

    @Test
    @DisplayName("TIGHTENING: a template or price entry that is not an object fails the scan, before any write")
    void nonObjectTemplateOrPriceFailsScan() {
        assertThatThrownBy(() -> stream("{\"apis\":[{\"id\":\"a\"}],\"credentialTemplates\":[\"x\"]}").scan())
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> stream("{\"apis\":[{\"id\":\"a\"}],\"generationPrices\":[42]}").scan())
                .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("Bytes that are not gzip fail the scan with an IOException, never an unchecked crash")
    void notGzip() {
        ApiCatalogPayloadStream s = new ApiCatalogPayloadStream(mapper, "{\"apis\":[]}".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(s::scan).isInstanceOf(IOException.class);
    }
}
