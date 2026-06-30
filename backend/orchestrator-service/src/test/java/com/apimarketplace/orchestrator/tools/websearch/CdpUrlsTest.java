package com.apimarketplace.orchestrator.tools.websearch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CdpUrls}.
 *
 * <p>The util is small but load-bearing: any drift between the URL
 * format used by the initial submit response and the refresh response
 * silently breaks the frontend's reconnect path (the wss:// URL on
 * the new socket would target a different proxy route than what
 * cdp.py registers).</p>
 */
@DisplayName("CdpUrls")
class CdpUrlsTest {

    @Test
    @DisplayName("Upgrades https:// base to wss:// scheme")
    void upgradesHttpsToWss() {
        assertThat(CdpUrls.buildWsUrl("https://websearch-host.example.com", "sess_abc"))
                .isEqualTo("wss://websearch-host.example.com/cdp/sess_abc");
    }

    @Test
    @DisplayName("Upgrades http:// base to ws:// scheme (dev)")
    void upgradesHttpToWs() {
        assertThat(CdpUrls.buildWsUrl("http://localhost:8085", "sess_abc"))
                .isEqualTo("ws://localhost:8085/cdp/sess_abc");
    }

    @Test
    @DisplayName("Returns null when base is null - caller gates the response field")
    void returnsNullForNullBase() {
        assertThat(CdpUrls.buildWsUrl(null, "sess_abc")).isNull();
    }

    @Test
    @DisplayName("Returns null when base is blank")
    void returnsNullForBlankBase() {
        assertThat(CdpUrls.buildWsUrl("   ", "sess_abc")).isNull();
    }

    @Test
    @DisplayName("Falls through unchanged when base already uses ws:// or wss:// scheme")
    void passesThroughNonHttpScheme() {
        // Defensive: if the configured serviceUrl is already ws(s)://
        // we don't try to be clever about it - append /cdp/{sid} as-is.
        assertThat(CdpUrls.buildWsUrl("wss://websearch-host.example.com", "sess_abc"))
                .isEqualTo("wss://websearch-host.example.com/cdp/sess_abc");
    }

    @Test
    @DisplayName("Strips trailing slash so 'https://x/' yields 'wss://x/cdp/{sid}', not 'wss://x//cdp/{sid}'")
    void stripsTrailingSlashFromBase() {
        // A serviceUrl configured with a trailing `/` (legitimate for
        // some deployments) would otherwise produce a double slash
        // before /cdp/, which some WS proxies refuse to route.
        assertThat(CdpUrls.buildWsUrl("https://websearch-host.example.com/", "sess_abc"))
                .isEqualTo("wss://websearch-host.example.com/cdp/sess_abc");
    }
}
