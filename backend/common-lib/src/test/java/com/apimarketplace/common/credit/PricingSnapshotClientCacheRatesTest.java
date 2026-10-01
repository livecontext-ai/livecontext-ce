package com.apimarketplace.common.credit;

import com.apimarketplace.common.credit.PricingSnapshotClient.PricingRates;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cache prices the budget guards read from the pricing snapshot (2026-09-30).
 *
 * <p>Without them a guard priced every cache read at the input rate and killed a free
 * account's Claude Code turn at about five times its real debit. The snapshot is served
 * over a real local HTTP endpoint because the client owns its RestTemplate: that is the
 * only way to exercise the parsing the guards depend on.
 */
@DisplayName("PricingSnapshotClient - cache rates")
class PricingSnapshotClientCacheRatesTest {

    private static final String SNAPSHOT = """
        {"version":"v1","rates":[
          {"provider":"claude-code","model":"claude-sonnet-5-5","inputRate":2.666667,"outputRate":13.333333,
           "fixedCost":0,"contextWindow":null,"maxOutputTokens":null,
           "cacheReadRate":0.266667,"cacheWriteRate":3.333333},
          {"provider":"openai","model":"legacy-row","inputRate":1.0,"outputRate":4.0,"fixedCost":0},
          {"provider":"x","model":"zero-cache","inputRate":1.0,"outputRate":1.0,"fixedCost":0,
           "cacheReadRate":0,"cacheWriteRate":"0"}
        ]}
        """;

    private HttpServer server;
    private PricingSnapshotClient client;

    @BeforeEach
    void startSnapshotEndpoint() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/internal/auth/pricing/snapshot", exchange -> {
            byte[] body = SNAPSHOT.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        client = new PricingSnapshotClient("http://127.0.0.1:" + server.getAddress().getPort());
        client.refresh();
    }

    @AfterEach
    void stopSnapshotEndpoint() {
        server.stop(0);
    }

    @Test
    @DisplayName("reads cacheReadRate / cacheWriteRate off a snapshot row")
    void readsCacheRates() {
        PricingRates rates = client.getRates("claude-code", "claude-sonnet-5-5").orElseThrow();

        assertThat(rates.cacheReadRate()).isEqualByComparingTo("0.266667");
        assertThat(rates.cacheWriteRate()).isEqualByComparingTo("3.333333");
    }

    @Test
    @DisplayName("a row from an older auth-service without cache rates reads them as null (guards fall back to the input rate)")
    void absentCacheRatesAreNull() {
        PricingRates rates = client.getRates("openai", "legacy-row").orElseThrow();

        assertThat(rates.cacheReadRate()).isNull();
        assertThat(rates.cacheWriteRate()).isNull();
        assertThat(rates.inputRate()).isEqualByComparingTo("1.0");
    }

    @Test
    @DisplayName("a 0 cache rate is read as unknown, never as free cached input")
    void zeroCacheRateIsUnknown() {
        PricingRates rates = client.getRates("x", "zero-cache").orElseThrow();

        assertThat(rates.cacheReadRate()).isNull();
        assertThat(rates.cacheWriteRate()).isNull();
    }
}
