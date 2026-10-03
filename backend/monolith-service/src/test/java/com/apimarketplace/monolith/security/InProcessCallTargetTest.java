package com.apimarketplace.monolith.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LC-032: which outbound URIs may carry this JVM's in-process secret.
 *
 * <p>The interesting cases are all NEGATIVE. The secret is only a barrier while it stays inside
 * this process, and a CE container talks to several other things over its own loopback interface -
 * MinIO on 9000, the CLI bridge on 8093 - so a host-only match would hand this JVM's internal-trust
 * marker to a separate program. Every test here that expects {@code false} is a leak that would
 * otherwise be shipped.
 */
@DisplayName("InProcessCallTarget: only this monolith's own loopback endpoint")
class InProcessCallTargetTest {

    private final InProcessCallTarget target = new InProcessCallTarget(8080);

    @ParameterizedTest(name = "{0} is this application")
    @ValueSource(strings = {
            "http://localhost:8080/api/internal/credentials/all",
            "http://LOCALHOST:8080/api/internal/credentials/all",
            "http://127.0.0.1:8080/api/workflows",
            "http://127.5.6.7:8080/api/workflows",
            "http://[::1]:8080/api/workflows"
    })
    @DisplayName("the monolith's own services.*-url shapes are recognised")
    void ownEndpointsAreSelf(String uri) {
        assertThat(target.isSelf(URI.create(uri))).isTrue();
    }

    @Test
    @DisplayName("another service on the SAME loopback interface is not this application")
    void otherLoopbackPortsAreNotSelf() {
        // The two that exist in the shipped CE compose. Stamping either would hand the secret to a
        // different process, which is the failure this check exists to prevent.
        assertThat(target.isSelf(URI.create("http://localhost:9000/workflow-files/x")))
                .as("MinIO").isFalse();
        assertThat(target.isSelf(URI.create("http://127.0.0.1:8093/dispatch")))
                .as("the CLI bridge").isFalse();
    }

    @Test
    @DisplayName("an external host is not this application, even one named localhost-ish")
    void externalHostsAreNotSelf() {
        assertThat(target.isSelf(URI.create("https://api.openai.com/v1/chat/completions"))).isFalse();
        assertThat(target.isSelf(URI.create("http://host.docker.internal:8080/dispatch"))).isFalse();
        assertThat(target.isSelf(URI.create("http://localhost.evil.example.com:8080/x"))).isFalse();
    }

    @Test
    @DisplayName("a hostname that RESOLVES to loopback is not accepted, because it is never resolved")
    void namesAreNeverResolved() {
        // Two reasons, and both matter. A DNS lookup on every outbound provider call is a cost this
        // has no right to impose, and a name resolving to 127.0.0.1 proves nothing anyway: whoever
        // controls the name chooses the address.
        assertThat(target.isSelf(URI.create("http://localtest.me:8080/api/workflows"))).isFalse();
        // A hostname made only of hex letters must be read as a NAME, not as an IPv6 literal, or
        // the no-DNS rule quietly stops holding for it.
        assertThat(target.isSelf(URI.create("http://cafe:8080/api/workflows"))).isFalse();
        assertThat(target.isSelf(URI.create("http://deadbeef:8080/api/workflows"))).isFalse();
    }

    @Test
    @DisplayName("a non-http scheme is never this application")
    void nonHttpSchemesAreNotSelf() {
        assertThat(target.isSelf(URI.create("ws://127.0.0.1:8080/ws"))).isFalse();
        assertThat(target.isSelf(URI.create("file:///etc/passwd"))).isFalse();
        assertThat(target.isSelf(URI.create("/api/workflows"))).as("relative, no host").isFalse();
        assertThat(target.isSelf(null)).isFalse();
    }

    @Test
    @DisplayName("an implicit port is compared against the scheme default, not ignored")
    void implicitPortsUseTheSchemeDefault() {
        assertThat(new InProcessCallTarget(80).isSelf(URI.create("http://127.0.0.1/api/workflows"))).isTrue();
        assertThat(new InProcessCallTarget(8080).isSelf(URI.create("http://127.0.0.1/api/workflows"))).isFalse();
        assertThat(new InProcessCallTarget(443).isSelf(URI.create("https://127.0.0.1/api/workflows"))).isTrue();
    }

    @Test
    @DisplayName("the port the web server actually bound wins over the configured one")
    void boundPortWins() {
        InProcessCallTarget resolved = new InProcessCallTarget(0);
        assertThat(resolved.isSelf(URI.create("http://127.0.0.1:54321/api/workflows"))).isFalse();

        resolved.bindPort(54321);

        assertThat(resolved.port()).isEqualTo(54321);
        assertThat(resolved.isSelf(URI.create("http://127.0.0.1:54321/api/workflows"))).isTrue();
    }
}
