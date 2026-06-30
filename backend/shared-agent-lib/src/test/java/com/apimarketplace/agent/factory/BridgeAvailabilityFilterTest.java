package com.apimarketplace.agent.factory;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BridgeAvailabilityFilter}.
 *
 * <p>The filter builds its own {@link org.springframework.web.client.RestTemplate}
 * internally, so we stub the bridge's {@code /cli-status} with a tiny in-process
 * JDK {@link HttpServer} rather than mocking the client. Each test uses a fresh
 * filter instance so the per-instance TTL cache always starts empty (first call
 * fetches).
 */
@DisplayName("BridgeAvailabilityFilter")
class BridgeAvailabilityFilterTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /** Start a stub bridge that returns {@code body} (JSON) for GET /cli-status. */
    private String startBridge(String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cli-status", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private Map<String, Object> provider(String name) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        return p;
    }

    /** A models map with the given providers, in a MUTABLE list so removeIf works. */
    private Map<String, Object> baseWith(String... providerNames) {
        List<Map<String, Object>> providers = new ArrayList<>();
        for (String n : providerNames) providers.add(provider(n));
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("providers", providers);
        return base;
    }

    @SuppressWarnings("unchecked")
    private List<String> names(Map<String, Object> base) {
        List<Map<String, Object>> providers = (List<Map<String, Object>>) base.get("providers");
        return providers.stream().map(p -> (String) p.get("name")).toList();
    }

    private String cli(String id, boolean installed, boolean authenticated) {
        return "\"" + id + "\":{\"id\":\"" + id + "\",\"installed\":" + installed
                + ",\"authenticated\":" + authenticated + "}";
    }

    @Test
    @DisplayName("(a) bridge reports codex not installed -> codex removed, installed+authed CLI and API providers kept")
    void codexNotInstalledIsRemoved() throws IOException {
        String url = startBridge("{\"clis\":{"
                + cli("claudeCode", true, true) + ","
                + cli("codex", false, false) + "}}");
        BridgeAvailabilityFilter filter = new BridgeAvailabilityFilter(url); // strict by default

        Map<String, Object> base = baseWith("openai", "claude-code", "codex");
        filter.filter(base);

        assertThat(names(base)).containsExactly("openai", "claude-code");
    }

    @Test
    @DisplayName("(b) bridge unreachable / unverifiable (blank URL) in STRICT mode removes ALL CLI providers")
    void unverifiableStrictRemovesAllCliProviders() {
        BridgeAvailabilityFilter filter = new BridgeAvailabilityFilter(""); // blank url => can't verify, strict default

        Map<String, Object> base = baseWith("openai", "claude-code", "codex", "gemini-cli", "mistral-vibe", "anthropic");
        filter.filter(base);

        // Every CLI provider dropped; API providers untouched.
        assertThat(names(base)).containsExactly("openai", "anthropic");
    }

    @Test
    @DisplayName("(b2) bridge that errors (connection refused) in STRICT mode removes ALL CLI providers")
    void unreachableBridgeStrictRemovesAllCliProviders() throws IOException {
        // Bind then immediately free a port so the connection is refused fast and deterministically.
        HttpServer tmp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int freePort = tmp.getAddress().getPort();
        tmp.stop(0);
        BridgeAvailabilityFilter filter = new BridgeAvailabilityFilter("http://127.0.0.1:" + freePort);

        Map<String, Object> base = baseWith("openai", "codex");
        filter.filter(base);

        assertThat(names(base)).containsExactly("openai");
    }

    @Test
    @DisplayName("(c) API providers are never touched, even in strict mode")
    void apiProvidersNeverTouched() {
        BridgeAvailabilityFilter filter = new BridgeAvailabilityFilter(""); // strict, unverifiable

        Map<String, Object> base = baseWith("openai", "anthropic", "deepseek", "mistral");
        filter.filter(base);

        assertThat(names(base)).containsExactly("openai", "anthropic", "deepseek", "mistral");
    }

    @Test
    @DisplayName("lenient mode: unverifiable bridge keeps CLI providers (legacy no-op)")
    void lenientUnverifiableKeepsCliProviders() {
        BridgeAvailabilityFilter filter = new BridgeAvailabilityFilter("", false); // lenient

        Map<String, Object> base = baseWith("openai", "claude-code", "codex");
        filter.filter(base);

        assertThat(names(base)).containsExactly("openai", "claude-code", "codex");
    }

    @Test
    @DisplayName("installed but NOT authenticated -> removed (a CLI that can't run must not be offered)")
    void installedButNotAuthedIsRemoved() throws IOException {
        String url = startBridge("{\"clis\":{"
                + cli("codex", true, false) + ","      // installed, not authed
                + cli("claudeCode", true, true) + "}}"); // installed + authed
        BridgeAvailabilityFilter filter = new BridgeAvailabilityFilter(url);

        Map<String, Object> base = baseWith("codex", "claude-code", "openai");
        filter.filter(base);

        assertThat(names(base)).containsExactly("claude-code", "openai");
    }

    @Test
    @DisplayName("missing 'authenticated' field (older bridge) falls back to installed -> kept")
    void missingAuthFieldFallsBackToInstalled() throws IOException {
        // No 'authenticated' key at all: don't over-hide against a bridge that predates the auth probe.
        String url = startBridge("{\"clis\":{\"codex\":{\"id\":\"codex\",\"installed\":true}}}");
        BridgeAvailabilityFilter filter = new BridgeAvailabilityFilter(url);

        Map<String, Object> base = baseWith("codex", "openai");
        filter.filter(base);

        assertThat(names(base)).containsExactly("codex", "openai");
    }

    @Test
    @DisplayName("installedMap() reflects the raw 'installed' flag (NOT auth) for admin annotation")
    void installedMapReflectsInstalledOnly() throws IOException {
        String url = startBridge("{\"clis\":{"
                + cli("codex", true, false) + ","      // installed but not authed
                + cli("claudeCode", false, false) + "}}");
        BridgeAvailabilityFilter filter = new BridgeAvailabilityFilter(url);

        Map<String, Boolean> installed = filter.installedMap();

        // installed=true even though not authed (admin "bridgeAvailable" stays installed-based)...
        assertThat(installed).containsEntry("codex", true);
        assertThat(installed).containsEntry("claudeCode", false);
        // ...while the user-facing filter still drops the un-authed codex.
        Map<String, Object> base = baseWith("codex", "openai");
        filter.filter(base);
        assertThat(names(base)).containsExactly("openai");
    }

    @Test
    @DisplayName("default (single-arg) constructor is STRICT")
    void singleArgConstructorIsStrict() {
        BridgeAvailabilityFilter filter = new BridgeAvailabilityFilter(""); // blank => unverifiable

        Map<String, Object> base = baseWith("codex");
        filter.filter(base);

        assertThat(names(base)).isEmpty();
    }

    @Test
    @DisplayName("null / empty providers list is handled without error")
    void nullAndEmptyProvidersAreSafe() {
        BridgeAvailabilityFilter filter = new BridgeAvailabilityFilter("");

        filter.filter(null); // no NPE
        Map<String, Object> empty = new LinkedHashMap<>();
        filter.filter(empty); // no providers key
        Map<String, Object> emptyList = baseWith();
        filter.filter(emptyList);

        assertThat(names(emptyList)).isEmpty();
    }
}
