package com.apimarketplace.catalog.service.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LoggedShape - keys, types and sizes of a tool call, never a value")
class LoggedShapeTest {

    private static final String SECRET = "sk_live_FAKE_client_secret_value_123";

    @Test
    @DisplayName("A map keeps its keys and the size of each string, and drops every value")
    void mapShape() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client_id", "abc");
        body.put("client_secret", SECRET);
        body.put("amount", 42);
        body.put("live", true);
        body.put("nothing", null);

        String shape = LoggedShape.of(body);

        assertThat(shape)
                .isEqualTo("{client_id=<string 3>, client_secret=<string " + SECRET.length()
                        + ">, amount=<integer>, live=<boolean>, nothing=null}")
                .doesNotContain(SECRET);
    }

    @Test
    @DisplayName("JSON parameters (the array of single-key objects the tools receive) keep keys only")
    void jsonParameterArray() {
        ObjectMapper m = new ObjectMapper();
        ArrayNode params = m.createArrayNode();
        params.addObject().put("text", "hello there");
        params.addObject().put("api_key", SECRET);
        params.addObject().putObject("nested").put("deep", SECRET).put("n", 1.5);

        String shape = LoggedShape.of(params);

        assertThat(shape)
                .isEqualTo("[{text=<string 11>}, {api_key=<string " + SECRET.length()
                        + ">}, {nested={deep=<string " + SECRET.length() + ">, n=<number>}}]")
                .doesNotContain(SECRET);
    }

    @Test
    @DisplayName("Strings, bytes, lists and unknown types: size or type, never content")
    void scalarsAndOthers() {
        assertThat(LoggedShape.of(SECRET)).isEqualTo("<string " + SECRET.length() + ">");
        assertThat(LoggedShape.of(new byte[]{1, 2, 3})).isEqualTo("<bytes 3>");
        assertThat(LoggedShape.of(List.of(SECRET, 7))).isEqualTo("[<string " + SECRET.length() + ">, <integer>]");
        assertThat(LoggedShape.of(List.of())).isEqualTo("[0 items]");
        assertThat(LoggedShape.of(null)).isEqualTo("null");
        assertThat(LoggedShape.of(new StringBuilder(SECRET))).doesNotContain(SECRET);
        assertThat(LoggedShape.of(new Object() {
            @Override public String toString() { return SECRET; }
        })).doesNotContain(SECRET);
    }

    @Test
    @DisplayName("A multipart body: part names and types only, and no part is ever read")
    void multipartNeverConsumed() {
        java.util.concurrent.atomic.AtomicBoolean read = new java.util.concurrent.atomic.AtomicBoolean();
        org.springframework.core.io.ByteArrayResource file =
                new org.springframework.core.io.ByteArrayResource(SECRET.getBytes()) {
                    @Override public java.io.InputStream getInputStream() throws java.io.IOException {
                        read.set(true);
                        return super.getInputStream();
                    }
                    @Override public String getDescription() {
                        // A toString() path would come through here; it must not be reached.
                        read.set(true);
                        return SECRET;
                    }
                };
        org.springframework.util.LinkedMultiValueMap<String, Object> form = new org.springframework.util.LinkedMultiValueMap<>();
        form.add("file", file);
        form.add("meta", new org.springframework.http.HttpEntity<>(SECRET));
        form.add("key", SECRET);

        String shape = LoggedShape.of(form);

        assertThat(read).isFalse();
        assertThat(shape).doesNotContain(SECRET)
                .startsWith("{file=[<")
                .contains("meta=[<HttpEntity>]")
                .contains("key=[<string " + SECRET.length() + ">]");
    }

    @Test
    @DisplayName("Output is bounded in depth and width, so a huge body cannot flood the log")
    void bounded() {
        Map<String, Object> deep = Map.of("a", Map.of("b", Map.of("c", Map.of("d", SECRET))));
        assertThat(LoggedShape.of(deep)).isEqualTo("{a={b={c={1 keys}}}}");

        Map<String, Object> wide = new LinkedHashMap<>();
        for (int i = 0; i < LoggedShape.MAX_ENTRIES + 5; i++) {
            wide.put("k" + i, i);
        }
        assertThat(LoggedShape.of(wide)).endsWith("... 5 more}");
    }
}
