package com.apimarketplace.agent.config;

import com.apimarketplace.common.web.GatewayAuthenticationFilter;
import com.apimarketplace.common.web.GatewayFilterProperties;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the agent-service endpoints that OTHER services call directly, pod to pod.
 *
 * <p>Those callers ({@code conversation-service}'s {@code AgentConfigProvider}, notably)
 * send only {@code X-User-ID} - they never sign a gateway HMAC. So every such endpoint
 * MUST appear in {@code gateway.filter.public-paths}, or
 * {@link GatewayAuthenticationFilter} answers 401 and the caller, which wraps the call in
 * a catch-and-continue, degrades silently with no failing test anywhere to show it.
 *
 * <p>That is exactly what happened to {@code /api/skills/default-active/summary}: it
 * shipped with V275 without being added to the list, so in cloud every general-chat turn
 * ran with NO default-active skills while the log showed only a swallowed WARN. CE was
 * unaffected (the monolith trusts an {@code X-User-ID} loopback), which is why it survived
 * local testing.
 *
 * <p>The test drives the REAL filter with the REAL list from {@code application.yml}, so it
 * asserts production behaviour rather than a copy of the matching rule.
 */
@DisplayName("Agent-service public paths - cross-service callers")
class CrossServicePublicPathsTest {

    @Test
    @DisplayName("the default-active skills summary is reachable without a gateway HMAC (bug: 401 killed default skills in chat)")
    void defaultActiveSkillsSummaryIsReachableWithoutGatewayHmac() throws IOException, ServletException {
        MockHttpServletResponse response = callAsConversationService("/api/skills/default-active/summary");

        assertThat(response.getStatus())
            .as("conversation-service AgentConfigProvider sends only X-User-ID; a 401 here is "
                + "swallowed by its catch block and general chat silently loses every "
                + "default-active skill")
            .isNotEqualTo(401);
    }

    @Test
    @DisplayName("an unlisted /api/skills path is still rejected, so the list is what grants access")
    void unlistedSkillsPathIsStillRejected() throws IOException, ServletException {
        MockHttpServletResponse response = callAsConversationService("/api/skills/not-exposed");

        assertThat(response.getStatus())
            .as("the filter must still reject a path no prefix covers - otherwise the "
                + "assertions above would pass for the wrong reason")
            .isEqualTo(401);
    }

    @Test
    @DisplayName("every agent-service path literal conversation-service builds is covered")
    void everyUrlTheCallerBuildsIsCovered() throws IOException, ServletException {
        // Derived from the CALLER'S SOURCE, not from a list maintained by hand here. The hand
        // -maintained list is precisely what failed the first time: V275 added an endpoint and
        // nobody remembered the yml. A guard that needs the same human step guards nothing.
        List<String> urls = agentServiceUrlsBuiltByConversationService();
        assertThat(urls)
            .as("the caller source must still be readable from here; if it moved, fix this "
                + "path rather than deleting the assertion")
            .isNotEmpty();

        for (String url : urls) {
            // A path template like /api/internal/agents/ + id: the prefix match is what
            // matters, so the literal prefix is the right thing to probe.
            assertThat(callAsConversationService(url).getStatus())
                .as("conversation-service calls %s with only X-User-ID, so it must be covered "
                    + "by a gateway.filter.public-paths prefix", url)
                .isNotEqualTo(401);
        }
    }

    /**
     * Every {@code /api/...} literal that conversation-service pairs with an agent-service base
     * URL: the paths it reaches pod-to-pod. Scanned across the WHOLE module, because a second
     * calling class is exactly what a fixed list misses.
     *
     * <p><b>What this cannot see</b>, in rising order of how much it hides:
     * <ul>
     *   <li>A path assembled from a constant or a variable instead of written as a literal near
     *       the base URL.</li>
     *   <li>A base URL that arrives from a method rather than a field - {@code ToolServiceRouter}
     *       already does this with {@code getServiceUrl(toolName) + "/api/agent-tools/execute"}.</li>
     *   <li><b>The whole {@code agent-client} module.</b> {@code AgentClient} assembles roughly
     *       thirty agent-service paths, and this walk never leaves conversation-service, so none
     *       of them is seen. They are all covered by existing prefixes today.</li>
     * </ul>
     * The {@code isNotEmpty()} at the call site catches total failure, not partial shrinkage, so
     * treat a widening of any of these as work to do rather than silence as coverage.
     */
    private static List<String> agentServiceUrlsBuiltByConversationService() throws IOException {
        Path sources = Path.of("..", "conversation-service", "src", "main", "java");
        assertThat(Files.exists(sources))
            .as("expected conversation-service sources at %s; if the module moved, fix this "
                + "path rather than dropping the assertion", sources.toAbsolutePath())
            .isTrue();

        // A bare agentServiceUrl / agentUrl - optionally qualified, as in this.agentServiceUrl -
        // concatenated OR passed to a builder: fromHttpUrl(agentServiceUrl).path("/api/x") is
        // already the shape AgentClient uses. The word boundary anchors the START of the name,
        // so a field called chatAgentServiceUrl would NOT match: widen this rather than read
        // the resulting silence as coverage.
        Pattern urlLiteral = Pattern.compile(
            "(?i)\\bagent(?:Service)?Url\\s*[).,+]?[^;\"]{0,40}?\"(/api/[^\"]+)\"");
        List<String> urls = new ArrayList<>();
        try (java.util.stream.Stream<Path> files = Files.walk(sources)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher matcher = urlLiteral.matcher(Files.readString(file));
                while (matcher.find()) {
                    urls.add(matcher.group(1));
                }
            }
        }
        return urls;
    }

    /** Replays the exact request shape a sibling service sends: GET, X-User-ID only, no HMAC. */
    private static MockHttpServletResponse callAsConversationService(String path)
            throws IOException, ServletException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        request.addHeader("X-User-ID", "42");

        MockHttpServletResponse response = new MockHttpServletResponse();
        newFilter().doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static GatewayAuthenticationFilter newFilter() {
        GatewayFilterProperties properties = new GatewayFilterProperties();
        properties.setPublicPaths(listFromApplicationYml("gateway.filter.public-paths"));
        // The filter's real rule is isPublicEndpoint(path) && !isHmacRequiredEndpoint(path).
        // agent-service declares no hmac-required paths today, but loading the list means
        // adding one later fails here instead of 401-ing in production with this test green.
        properties.setHmacRequiredPaths(listFromApplicationYml("gateway.filter.hmac-required-paths"));
        properties.setVerificationEnabled(true);
        // Any non-blank value: these tests never exercise signature verification, only the
        // public-path short-circuit that runs before it. A blank secret fails the ctor.
        properties.setSecretKey("test-secret-not-used-by-these-assertions");
        return new GatewayAuthenticationFilter(properties);
    }

    private static List<String> listFromApplicationYml(String key) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));
        Properties props = factory.getObject();
        assertThat(props).as("application.yml must be on the test classpath").isNotNull();

        List<String> paths = new ArrayList<>();
        for (int i = 0; ; i++) {
            String value = props.getProperty(key + "[" + i + "]");
            if (value == null) {
                break;
            }
            paths.add(value);
        }
        if ("gateway.filter.public-paths".equals(key)) {
            assertThat(paths)
                .as("%s must be declared in agent-service application.yml", key)
                .isNotEmpty();
        }
        return paths;
    }
}
