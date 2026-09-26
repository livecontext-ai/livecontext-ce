package com.apimarketplace.sse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The consumer is handed a URL that can already carry the caller's credential (a path variable
 * or a query key), so its log lines name the host only (regression 2026-09-25).
 */
@DisplayName("WebClientSseConsumer - log helpers never print the path or query")
class WebClientSseConsumerLogRedactionTest {

    private static final String URL = "https://api.example.com/bot1234567890:FAKE-token/stream?key=FAKE-query-key";

    @Test
    @DisplayName("hostOf keeps the host and drops path and query; an unparseable URL gets a marker")
    void hostOf() {
        assertThat(WebClientSseConsumer.hostOf(URL)).isEqualTo("api.example.com");
        assertThat(WebClientSseConsumer.hostOf("not a url")).isEqualTo("<unparseable url>");
    }

    @Test
    @DisplayName("withoutUrl rewrites the URL both whole and query-less, as client messages render it")
    void withoutUrl() {
        assertThat(WebClientSseConsumer.withoutUrl("failed for " + URL + " (reset)", URL))
                .isEqualTo("failed for api.example.com (reset)");
        String queryLess = URL.substring(0, URL.indexOf('?'));
        assertThat(WebClientSseConsumer.withoutUrl("failed for " + queryLess, URL))
                .isEqualTo("failed for api.example.com");
        assertThat(WebClientSseConsumer.withoutUrl(null, URL)).isNull();
    }

    @Test
    @DisplayName("exceptionChain names every class, outermost first, and no message")
    void exceptionChain() {
        Throwable e = new RuntimeException("wrapper " + URL,
                new IllegalStateException("inner " + URL));

        assertThat(WebClientSseConsumer.exceptionChain(e))
                .isEqualTo("java.lang.RuntimeException <- java.lang.IllegalStateException")
                .doesNotContain("FAKE");
    }
}
