package com.apimarketplace.orchestrator.services.file;

import com.apimarketplace.common.web.UrlResolutionException;
import com.apimarketplace.orchestrator.services.file.FileDownloader.FileDownloadException;
import com.apimarketplace.orchestrator.services.file.FileDownloader.UrlNotAllowedException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("WebClientFileDownloader")
class WebClientFileDownloaderTest {

    /**
     * A validator that allows everything. The real {@link com.apimarketplace.common.web.UrlSafetyValidator}
     * refuses loopback, and every server in this file IS loopback, so following a redirect
     * cannot be observed through it. The production wiring is covered separately by
     * {@link #shouldUseTheRealValidatorWhenConstructedForSpring()}, which asserts that the
     * Spring constructor refuses a loopback URL outright.
     */
    private static final Consumer<String> ALLOW_ALL = url -> { };

    /**
     * The download budget is deliberately generous wherever latency is not the subject.
     * A tight budget turns the first WebClient call in the JVM (Netty event loop, resolver
     * and connection-pool bootstrap) into the thing under test.
     */
    private static final Duration GENEROUS = Duration.ofSeconds(60);

    private HttpServer server;
    private AtomicInteger targetHits;
    private AtomicInteger blockedHits;
    private AtomicInteger slowTargetRequests;
    private final AtomicReference<String> receivedRawQuery = new AtomicReference<>();
    private WebClientFileDownloader downloader;

    @BeforeEach
    void setUp() throws Exception {
        targetHits = new AtomicInteger();
        blockedHits = new AtomicInteger();
        slowTargetRequests = new AtomicInteger();
        receivedRawQuery.set(null);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/redirect", redirectTo("/target", 302));
        server.createContext("/absolute-redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", baseUrl() + "/target");
            exchange.sendResponseHeaders(303, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            targetHits.incrementAndGet();
            receivedRawQuery.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, "target");
        });
        server.createContext("/presigned", exchange -> {
            receivedRawQuery.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, "clip");
        });
        // /hop/N redirects to /hop/(N-1); /hop/0 serves the bytes. So /hop/3 is three hops.
        server.createContext("/hop/", exchange -> {
            int remaining = Integer.parseInt(
                    exchange.getRequestURI().getPath().substring("/hop/".length()));
            if (remaining == 0) {
                targetHits.incrementAndGet();
                respond(exchange, 200, "target");
                return;
            }
            exchange.getResponseHeaders().set("Location", "/hop/" + (remaining - 1));
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/loop-a", redirectTo("/loop-b", 302));
        server.createContext("/loop-b", redirectTo("/loop-a", 302));
        server.createContext("/to-blocked", redirectTo("/blocked", 302));
        server.createContext("/blocked", exchange -> {
            blockedHits.incrementAndGet();
            respond(exchange, 200, "secret");
        });
        server.createContext("/no-location", exchange -> {
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        // "%zz" is not a valid escape pair, so URI refuses it however it is encoded.
        server.createContext("/bad-location", redirectTo("http://example.com/%zz", 302));
        // A raw space is illegal in a Location but real servers send one. The %2F alongside
        // it is what makes this test prove the encoder's central claim: it fixes the space
        // WITHOUT touching an existing escape.
        server.createContext("/spaced-location", redirectTo("/target?c=A%2FB&name=my report.pdf", 302));
        server.createContext("/illegal-chars", redirectTo("/target?q=a|b^c{d}", 302));
        server.createContext("/missing", exchange -> respond(exchange, 404, "no such object"));
        server.createContext("/empty", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        // Two slow hops, 1200 ms each. See the timeout test for why those numbers.
        server.createContext("/slow-redirect", exchange -> {
            sleep(1200);
            exchange.getResponseHeaders().set("Location", "/slow-target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/slow-target", exchange -> {
            // Counted on ARRIVAL, before the sleep, so the assertion cannot race the handler.
            slowTargetRequests.incrementAndGet();
            sleep(1200);
            respond(exchange, 200, "target");
        });

        server.start();
        downloader = new WebClientFileDownloader(WebClient.builder(), ALLOW_ALL);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private com.sun.net.httpserver.HttpHandler redirectTo(String location, int status) {
        return exchange -> {
            exchange.getResponseHeaders().set("Location", location);
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        };
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ==================== following ====================

    /**
     * Regression for the production failure of 2026-09-17: core:download_file answered
     * "Download failed with status 303 SEE_OTHER" on a Google Drive share link, because
     * the client refused every 3xx instead of following it. Share links from Drive,
     * Dropbox, CDN edges and every URL shortener redirect, so this was not one provider,
     * it was most of the web. On the pre-fix code this test fails with that same 302.
     */
    @Test
    @DisplayName("should follow a redirect and return the target bytes")
    void shouldFollowARedirectAndReturnTheTargetBytes() {
        byte[] content = downloader.download(baseUrl() + "/redirect", GENEROUS);

        assertThat(content).isEqualTo("target".getBytes(StandardCharsets.UTF_8));
        assertThat(targetHits).hasValue(1);
    }

    /**
     * The Location above is relative ("/target"), which is what the servlet spec allows
     * and what real servers send. Resolving it as an absolute URL would produce a
     * hostless URI and fail; it has to be resolved against the URL that sent it.
     */
    @Test
    @DisplayName("should resolve an absolute Location as well as a relative one")
    void shouldResolveAnAbsoluteLocation() {
        byte[] content = downloader.download(baseUrl() + "/absolute-redirect", GENEROUS);

        assertThat(content).isEqualTo("target".getBytes(StandardCharsets.UTF_8));
        assertThat(targetHits).hasValue(1);
    }

    /**
     * Anchored on a literal 3, not only on MAX_REDIRECTS. Both limit tests derive their
     * URL from the constant, so with the constant at 0 they would both stay green while
     * redirect-following was entirely dead: the first would fetch /hop/0 and get bytes,
     * the second would refuse at the very first hop. This assertion is what makes the
     * pair mean something.
     */
    @Test
    @DisplayName("should follow a chain of three hops")
    void shouldFollowAChainOfSeveralHops() {
        assertThat(WebClientFileDownloader.MAX_REDIRECTS)
                .as("a literal 3-hop chain is what proves following works; the limit must allow it")
                .isGreaterThanOrEqualTo(3);

        byte[] content = downloader.download(baseUrl() + "/hop/3", GENEROUS);

        assertThat(content).isEqualTo("target".getBytes(StandardCharsets.UTF_8));
        assertThat(targetHits).hasValue(1);
    }

    @Test
    @DisplayName("should follow a chain that is exactly at the limit")
    void shouldFollowAChainAtTheLimit() {
        byte[] content = downloader.download(
                baseUrl() + "/hop/" + WebClientFileDownloader.MAX_REDIRECTS, GENEROUS);

        assertThat(content).isEqualTo("target".getBytes(StandardCharsets.UTF_8));
        assertThat(targetHits).hasValue(1);
    }

    /**
     * The two regressions this file guards, in one request: a share link that redirects
     * to a signed CDN URL. URI.resolve must carry the raw query through untouched, or the
     * '%2F' of the credential is mangled and the provider answers 400 - the older bug,
     * reintroduced through the new code path.
     */
    @Test
    @DisplayName("should carry a presigned query through a redirect without re-encoding it")
    void shouldCarryAPresignedQueryThroughARedirect() {
        String rawQuery = "X-Amz-Credential=AKLT%2F20260905%2Fap-southeast-1%2Fs3%2Frequest"
                + "&X-Amz-Signature=a6a5d7fc53ea64fff95f9a2a5880f38173ee9f34";
        server.createContext("/redirect-to-signed", redirectTo("/target?" + rawQuery, 302));

        byte[] content = downloader.download(baseUrl() + "/redirect-to-signed", GENEROUS);

        assertThat(content).isEqualTo("target".getBytes(StandardCharsets.UTF_8));
        assertThat(receivedRawQuery.get())
                .as("a signature covers the exact query string, so a redirect must not rewrite it")
                .isEqualTo(rawQuery);
        assertThat(receivedRawQuery.get()).doesNotContain("%252F");
    }

    /**
     * A raw space is illegal in a Location, yet servers send one and browsers carry on.
     * Refusing it would turn a download that works in a browser into a platform failure.
     */
    /**
     * The encoder's whole claim is that it repairs what a browser repairs WITHOUT touching
     * an existing escape, because rewriting a '%' is how the presigned regression this file
     * already guards would come back through the redirect path. A test with a space and no
     * escape, or an escape and no space, cannot prove that. This one carries both.
     */
    @Test
    @DisplayName("should encode a raw space in a Location while leaving an existing escape alone")
    void shouldEncodeARawSpaceWithoutTouchingAnEscape() {
        byte[] content = downloader.download(baseUrl() + "/spaced-location", GENEROUS);

        assertThat(content).isEqualTo("target".getBytes(StandardCharsets.UTF_8));
        assertThat(receivedRawQuery.get()).isEqualTo("c=A%2FB&name=my%20report.pdf");
        assertThat(receivedRawQuery.get()).doesNotContain("%252F");
    }

    @Test
    @DisplayName("should encode the other characters a URI forbids, not only the space")
    void shouldEncodeOtherIllegalCharacters() {
        byte[] content = downloader.download(baseUrl() + "/illegal-chars", GENEROUS);

        assertThat(content).isEqualTo("target".getBytes(StandardCharsets.UTF_8));
        assertThat(receivedRawQuery.get()).isEqualTo("q=a%7Cb%5Ec%7Bd%7D");
    }

    // ==================== refusing ====================

    @Test
    @DisplayName("should stop one hop past the redirect limit instead of following forever")
    void shouldStopPastTheRedirectLimit() {
        String url = baseUrl() + "/hop/" + (WebClientFileDownloader.MAX_REDIRECTS + 1);

        assertThatThrownBy(() -> downloader.download(url, GENEROUS))
                .isInstanceOf(FileDownloadException.class)
                .hasMessageContaining("Too many redirects")
                .hasMessageContaining(url)
                .extracting(e -> ((FileDownloadException) e).getStatusCode())
                .as("the limit is our policy, not the server's answer, so no HTTP status applies")
                .isEqualTo(-1);
        assertThat(targetHits)
                .as("the chain must be cut before it reaches its end")
                .hasValue(0);
    }

    @Test
    @DisplayName("should detect a redirect loop rather than spend the whole budget on it")
    void shouldDetectARedirectLoop() {
        assertThatThrownBy(() -> downloader.download(baseUrl() + "/loop-a", GENEROUS))
                .isInstanceOf(FileDownloadException.class)
                .hasMessageContaining("Redirect loop")
                .extracting(e -> ((FileDownloadException) e).getStatusCode())
                .isEqualTo(-1);
    }

    /**
     * The SSRF case the manual following exists for: a host that passes validation
     * redirects somewhere that does not. Netty's own followRedirect would have fetched
     * it without asking anyone, which is why it stays disabled.
     */
    @Test
    @DisplayName("should refuse a redirect target the validator rejects, and never request it")
    void shouldRefuseARedirectTargetTheValidatorRejects() {
        Consumer<String> refuseBlocked = url -> {
            if (url.contains("/blocked")) {
                throw new IllegalArgumentException(
                        "Requests to private/internal network addresses are not allowed: 10.0.0.1");
            }
        };
        WebClientFileDownloader guarded =
                new WebClientFileDownloader(WebClient.builder(), refuseBlocked);

        assertThatThrownBy(() -> guarded.download(baseUrl() + "/to-blocked", GENEROUS))
                .isInstanceOf(UrlNotAllowedException.class)
                .hasMessageContaining("Refused to follow redirect")
                .hasMessageContaining("private/internal network addresses");
        assertThat(blockedHits)
                .as("a refused hop must never be requested")
                .hasValue(0);
    }

    /**
     * The type matters as much as the refusal: FileToolsProvider maps
     * UrlNotAllowedException to INVALID_PARAMETER_VALUE, while a plain
     * FileDownloadException reads as transient and an agent retries it forever.
     */
    @Test
    @DisplayName("should raise a refusal, not a transient failure, when a validator throws anything")
    void shouldRaiseARefusalWhateverTheValidatorThrows() {
        Consumer<String> exploding = url -> {
            throw new IllegalStateException("resolver unavailable");
        };
        WebClientFileDownloader guarded =
                new WebClientFileDownloader(WebClient.builder(), exploding);

        assertThatThrownBy(() -> guarded.download(baseUrl() + "/target", GENEROUS))
                .isInstanceOf(UrlNotAllowedException.class)
                .hasMessageContaining("Refused to download")
                .hasMessageContaining("resolver unavailable");
        assertThat(targetHits).hasValue(0);
    }

    /**
     * The other half of the refusal/outage split, and the one that is easy to get wrong.
     * UrlSafetyValidator raises UrlResolutionException when its OWN DNS runs out of
     * capacity, times out or is interrupted. That describes this process under load, not
     * the URL. Reporting it as UrlNotAllowedException would reach the agent as
     * INVALID_PARAMETER_VALUE with "correct the URL", and the agent would rewrite a URL
     * that was fine instead of retrying the call that would have worked.
     */
    @Test
    @DisplayName("should keep a resolution failure retryable instead of calling it a refusal")
    void shouldNotTurnATransientResolutionFailureIntoARefusal() {
        Consumer<String> resolverDown = url -> {
            throw new UrlResolutionException("DNS resolution capacity exceeded for hostname: example.com");
        };
        WebClientFileDownloader guarded =
                new WebClientFileDownloader(WebClient.builder(), resolverDown);

        assertThatThrownBy(() -> guarded.download(baseUrl() + "/target", GENEROUS))
                .isInstanceOf(FileDownloadException.class)
                .isNotInstanceOf(UrlNotAllowedException.class)
                .hasMessageContaining("Could not check")
                .hasMessageContaining("DNS resolution capacity exceeded");
        assertThat(targetHits).hasValue(0);
    }

    /**
     * A downgrade hands the bytes, and any credential carried in the query of a
     * presigned URL, to a plaintext connection. The check runs before the validator,
     * which would allow http on its own.
     */
    @Test
    @DisplayName("should refuse a redirect that downgrades https to http")
    void shouldRefuseAnHttpsToHttpDowngrade() {
        assertThatThrownBy(() -> downloader.validateHop("http://example.com/file", "https://example.com/start"))
                .isInstanceOf(UrlNotAllowedException.class)
                .hasMessageContaining("downgrades https to http");
    }

    @Test
    @DisplayName("should allow a redirect that upgrades http to https")
    void shouldAllowAnHttpToHttpsUpgrade() {
        assertThatCode(() -> downloader.validateHop("https://example.com/file", "http://example.com/start"))
                .doesNotThrowAnyException();
    }

    /**
     * ftp is not http, so the downgrade branch must not claim it. Refusing it is the
     * validator's job, and its message is the one that tells the truth about why.
     */
    @Test
    @DisplayName("should let the validator refuse a non-http scheme, not call it a downgrade")
    void shouldNotCallANonHttpSchemeADowngrade() {
        Consumer<String> refuseNonHttp = url -> {
            if (!url.startsWith("http")) {
                throw new IllegalArgumentException("Only http and https schemes are allowed");
            }
        };
        WebClientFileDownloader guarded =
                new WebClientFileDownloader(WebClient.builder(), refuseNonHttp);

        assertThatThrownBy(() -> guarded.validateHop("ftp://internal/secret", "https://example.com/start"))
                .isInstanceOf(UrlNotAllowedException.class)
                .hasMessageContaining("Only http and https schemes are allowed")
                .hasMessageNotContaining("downgrades");
    }

    @Test
    @DisplayName("should fail with the status when a redirect carries no Location")
    void shouldFailOnARedirectWithoutLocation() {
        assertThatThrownBy(() -> downloader.download(baseUrl() + "/no-location", GENEROUS))
                .isInstanceOf(FileDownloadException.class)
                .hasMessageContaining("redirect without a Location header");
    }

    @Test
    @DisplayName("should report a Location that cannot be parsed even once encoded")
    void shouldReportAMalformedLocation() {
        assertThatThrownBy(() -> downloader.download(baseUrl() + "/bad-location", GENEROUS))
                .isInstanceOf(FileDownloadException.class)
                .hasMessageContaining("malformed Location")
                .hasMessageContaining("%zz");
    }

    /**
     * The validator now runs inside the downloader, not only in DownloadFileNode.
     * FileToolsProvider.executeDownloadFile passes an agent-supplied URL straight to
     * download() with no check of its own, so before this the agent-facing files tool
     * could reach an internal address. This test pins the production constructor: it
     * must install the real validator, which refuses loopback.
     */
    @Test
    @DisplayName("should use the real validator when constructed for Spring")
    void shouldUseTheRealValidatorWhenConstructedForSpring() {
        WebClientFileDownloader production = new WebClientFileDownloader(WebClient.builder());

        assertThatThrownBy(() -> production.download(baseUrl() + "/target", GENEROUS))
                .isInstanceOf(UrlNotAllowedException.class)
                .hasMessageContaining("Refused to download")
                .hasMessageContaining("private/internal network addresses");
        assertThat(targetHits)
                .as("a refused URL must never be requested")
                .hasValue(0);
    }

    // ==================== preserved behaviour ====================

    /**
     * The URL is now named in the message. After a redirect the failing URL is NOT the
     * one the caller passed, and a bare "403 FORBIDDEN" sends the reader to inspect a
     * link that answered perfectly well.
     */
    @Test
    @DisplayName("should surface the status, body and URL of an error response")
    void shouldSurfaceTheStatusOfAnErrorResponse() {
        String url = baseUrl() + "/missing";

        assertThatThrownBy(() -> downloader.download(url, GENEROUS))
                .isInstanceOf(FileDownloadException.class)
                .hasMessageContaining("404")
                .hasMessageContaining("no such object")
                .hasMessageContaining(url)
                .extracting(e -> ((FileDownloadException) e).getStatusCode())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("should report an empty 200 as no content rather than zero bytes")
    void shouldReportAnEmptyBodyAsNoContent() {
        assertThatThrownBy(() -> downloader.download(baseUrl() + "/empty", GENEROUS))
                .isInstanceOf(FileDownloadException.class)
                .hasMessageContaining("No content received");
    }

    @Test
    @DisplayName("should reject a blank URL before opening a connection")
    void shouldRejectABlankUrl() {
        assertThatThrownBy(() -> downloader.download("   ", GENEROUS))
                .isInstanceOf(FileDownloadException.class)
                .hasMessageContaining("URL is required");
    }

    /**
     * The timeout is ONE budget for the whole chain, not one per hop. Both hops sleep
     * 1200 ms against a 2 s budget: per-hop timing would let each one through (1200 < 2000)
     * and return the bytes, a shared budget runs out during the second (2400 > 2000).
     *
     * The assertion on slowTargetRequests is what stops this passing for the wrong reason.
     * Without it, a loaded machine that spent the 800 ms margin on the Netty bootstrap
     * would time out on hop ONE, the message would still say "timeout", and the test would
     * go green having proven nothing about a shared budget. The counter is incremented when
     * the second request ARRIVES, before its sleep, so it cannot race the client giving up.
     */
    @Test
    @DisplayName("should spend one timeout budget across the whole redirect chain")
    void shouldApplyOneTimeoutBudgetAcrossTheChain() {
        assertThatThrownBy(() -> downloader.download(baseUrl() + "/slow-redirect", Duration.ofSeconds(2)))
                .isInstanceOf(FileDownloadException.class)
                .hasMessageContaining("timeout");

        assertThat(slowTargetRequests)
                .as("the first hop must have completed and the second started, or this proves nothing")
                .hasValue(1);
    }

    /**
     * Regression for the download that reached BytePlus TOS as
     * "400 AuthorizationHeaderMalformed" while the very same URL served the clip
     * fine through curl. WebClient.uri(String) hands the value to the
     * UriBuilderFactory, which re-encodes an already-encoded query: the '%' of a
     * '%2F' becomes '%25', so the signed credential shipped as '%252F' and the
     * signature no longer matched. Every provider-presigned URL carries '%2F' in
     * its credential (TOS X-Tos-Credential, S3 X-Amz-Credential, Azure SAS, GCS),
     * so this broke the whole class, not one provider.
     *
     * The assertion is on what the SERVER received, not on what the client was
     * given: that is the only place the difference is observable, and it fails on
     * the pre-fix code with '%252F'.
     */
    @Test
    @DisplayName("should send a presigned query verbatim, never re-encoding its escapes")
    void shouldSendPresignedQueryVerbatim() {
        String rawQuery = "X-Tos-Algorithm=TOS4-HMAC-SHA256"
                + "&X-Tos-Credential=AKLT%2F20260905%2Fap-southeast-1%2Ftos%2Frequest"
                + "&X-Tos-Signature=a6a5d7fc53ea64fff95f9a2a5880f38173ee9f348335fc7a3199635f8ddd2234";
        String url = baseUrl() + "/presigned?" + rawQuery;

        byte[] content = downloader.download(url, GENEROUS);

        assertThat(content).isEqualTo("clip".getBytes(StandardCharsets.UTF_8));
        assertThat(receivedRawQuery.get())
                .as("the presigned query must arrive byte-identical, or the provider signature breaks")
                .isEqualTo(rawQuery);
        assertThat(receivedRawQuery.get()).doesNotContain("%252F");
    }
}
