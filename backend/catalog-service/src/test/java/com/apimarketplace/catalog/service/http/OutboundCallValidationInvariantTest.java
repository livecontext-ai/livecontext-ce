package com.apimarketplace.catalog.service.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariant for LC-006 / LC-073 (CASA readiness): every outbound HTTP call catalog-service makes
 * is either to an operator-configured endpoint, or goes through the SSRF check on its FINAL URL
 * and the pinned transport.
 *
 * <p>Source-level on purpose: the failure being prevented is "a NEW outbound call was added
 * without the check", which no behavioural test of the existing calls catches. A file that starts
 * making HTTP calls and is on neither list fails here until someone classifies it.
 */
@DisplayName("catalog-service - every outbound call is classified and user targets are validated")
class OutboundCallValidationInvariantTest {

    private static final Path MAIN = Paths.get("src/main/java");
    private static final String PKG = "com/apimarketplace/catalog/";

    /** A transport call, and the first argument it is given. */
    private static final Pattern CALL = Pattern.compile(
            "\\.(exchange|getForEntity|getForObject|postForEntity|postForObject|patchForObject|put|delete|execute|uri|send|consume)\\s*\\(",
            Pattern.DOTALL);

    /**
     * Files whose outbound calls go to operator-configured endpoints (cloud relay, LLM providers
     * of the platform, the catalog's own local URL), never to a URL a user supplies.
     */
    private static final Set<String> OPERATOR_TARGETS = Set.of(
            PKG + "bundle/ApiCatalogBundleFetcher.java",
            PKG + "bundle/ApiCatalogBundleTrustBootstrap.java",
            PKG + "service/DeepInfraDescriptionService.java",
            PKG + "service/QueryUnderstandingService.java",
            PKG + "service/RerankingService.java",
            PKG + "service/relay/CloudCatalogRelayClient.java",
            PKG + "tools/CatalogExecuteModule.java",
            PKG + "tools/CatalogSchemaModule.java",
            PKG + "tools/CatalogSearchModule.java",
            PKG + "tools/websearch/WebSearchConfig.java");

    /** Files that call a user-influenced URL, each with the guard its calls must follow. */
    private static final Map<String, String> USER_TARGETS = Map.of(
            PKG + "service/http/HttpExecutionService.java", "validatedUri(|validatedTarget(",
            PKG + "service/execution/AsyncPollExecutor.java", "checkPollTarget(pollUrl)",
            PKG + "service/ApiService.java", "UrlSafetyValidator.validateEgressUrl(healthUrl)",
            PKG + "service/execution/StreamingResponseHandler.java", "consumer()",
            PKG + "service/generation/GenerationAssetResolver.java", "refuseUnfetchable(target)",
            PKG + "service/http/OutboundHttpClients.java", "SafeAddressResolverGroup.egress()");

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean makesHttpCalls(String source) {
        boolean usesClient = source.contains("RestTemplate") || source.contains("WebClient")
                || source.contains("java.net.http") || source.contains("HttpClient")
                || source.contains("SseStreamConsumer");
        return usesClient && CALL.matcher(source).find();
    }

    @Test
    @DisplayName("every catalog file making HTTP calls is classified as operator- or user-targeted")
    void everyCallingFileIsClassified() throws IOException {
        Set<String> unclassified = new TreeSet<>();
        int callers = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                String rel = MAIN.relativize(p).toString().replace('\\', '/');
                if (!makesHttpCalls(read(p))) {
                    continue;
                }
                callers++;
                if (!OPERATOR_TARGETS.contains(rel) && !USER_TARGETS.containsKey(rel)) {
                    unclassified.add(rel);
                }
            }
        }
        assertThat(callers).as("anti-vacuity: the scan must find the calling files").isGreaterThanOrEqualTo(10);
        assertThat(unclassified)
                .as("a new outbound caller must be classified: if its URL can come from a user, route it "
                        + "through HttpExecutionService.validatedTarget and OutboundHttpClients")
                .isEmpty();
    }

    @Test
    @DisplayName("every user-targeted file carries its guard")
    void userTargetFilesCarryTheirGuard() {
        for (Map.Entry<String, String> e : USER_TARGETS.entrySet()) {
            String source = read(MAIN.resolve(e.getKey()));
            boolean found = false;
            for (String guard : e.getValue().split("\\|")) {
                found |= source.contains(guard);
            }
            assertThat(found).as("%s must contain %s", e.getKey(), e.getValue()).isTrue();
        }
    }

    @Test
    @DisplayName("HttpExecutionService: every exchange/stream target is validated and leaves via transport()")
    void httpExecutionServiceCallsAreValidatedAndPinned() {
        String source = read(MAIN.resolve(PKG + "service/http/HttpExecutionService.java"));
        Matcher m = Pattern.compile(
                "(transport\\(\\)|restTemplate|streamingResponseHandler)\\s*\\.\\s*(exchange|handle)\\s*\\(\\s*([^,]+?)\\s*,",
                Pattern.DOTALL).matcher(source);
        List<String> offenders = new ArrayList<>();
        int found = 0;
        while (m.find()) {
            found++;
            String receiver = m.group(1);
            String target = m.group(3).trim();
            if (!target.startsWith("validatedTarget(") && !target.startsWith("validatedUri(")) {
                offenders.add(receiver + "." + m.group(2) + " #" + found + " targets `" + target + "`");
            }
            if ("exchange".equals(m.group(2)) && !"transport()".equals(receiver)) {
                offenders.add("exchange #" + found + " goes through `" + receiver + "`, not the pinned transport()");
            }
        }
        assertThat(found).as("six exchange sites plus the streaming handle").isGreaterThanOrEqualTo(7);
        assertThat(offenders).isEmpty();
        assertThat(source)
                .as("legacy String targets re-parse differently from the validated URI (parser differential)")
                .doesNotContain("validatedTarget(requestUrl)")
                .doesNotContain("validatedTarget(refreshedUrl)");
    }

    @Test
    @DisplayName("AsyncPollExecutor re-validates on every attempt and uses the pinned transport")
    void asyncPollRevalidatesEveryAttempt() {
        String source = read(MAIN.resolve(PKG + "service/execution/AsyncPollExecutor.java"));
        // The poll loop, from its head to the pause that ends each attempt.
        int loop = source.indexOf("while (true) {");
        assertThat(loop).isPositive();
        int pause = source.indexOf("pauser.pause(pauseMs)", loop);
        assertThat(pause).isGreaterThan(loop);
        String body = source.substring(loop, pause);
        assertThat(body).contains("checkPollTarget(pollUrl);").contains("transport().exchange(");
        assertThat(body.indexOf("checkPollTarget(pollUrl);")).isLessThan(body.indexOf("transport().exchange("));
    }

    @Test
    @DisplayName("validatedTarget runs the egress SSRF check and the credential host binding")
    void validatedTargetRunsTheChecks() {
        String source = read(MAIN.resolve(PKG + "service/http/HttpExecutionService.java"));
        int start = source.indexOf("public static String validatedTarget(String url)");
        assertThat(start).isPositive();
        String body = source.substring(start, start + 400);
        assertThat(body).contains("UrlSafetyValidator.validateEgressUrl(url)").contains("CredentialHostBinding.enforce(url)");
    }
}
