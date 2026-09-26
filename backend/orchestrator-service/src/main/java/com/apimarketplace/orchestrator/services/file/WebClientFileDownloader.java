package com.apimarketplace.orchestrator.services.file;

import com.apimarketplace.common.web.UrlResolutionException;
import com.apimarketplace.common.web.UrlSafetyValidator;
import com.apimarketplace.orchestrator.services.template.ReportedParams;
import com.apimarketplace.orchestrator.utils.file.FileConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * WebClient-based file downloader implementation.
 * Uses Spring's reactive WebClient with connection pooling.
 *
 * Features:
 * - Connection pooling via WebClient.Builder
 * - Configurable buffer size for large files
 * - Timeout handling with descriptive errors
 * - Status code propagation in exceptions
 * - Redirects followed manually, every hop re-validated against SSRF
 *
 * <p>Note for whoever generalises this: {@code RssNode} and the two {@code RestTemplate}
 * clients still refuse every redirect, from the same hardening commit. A share link is
 * therefore still unreachable through {@code core:rss} and {@code core:http_request}.
 * That was left alone deliberately, not overlooked: those call sites carry different
 * request shapes and risk, and folding all four onto one shared fetcher is a larger
 * change than the download path needed.
 */
@Service
public class WebClientFileDownloader implements FileDownloader {

    private static final Logger logger = LoggerFactory.getLogger(WebClientFileDownloader.class);

    /**
     * Hops allowed before the chain is called a failure. Five covers every real share
     * link seen in production (Drive, Dropbox, CDN edges and URL shorteners all resolve
     * in one or two) while stopping a misbehaving server from spending the caller's
     * whole timeout budget on redirects.
     */
    static final int MAX_REDIRECTS = 5;

    private final WebClient webClient;
    private final Consumer<String> urlValidator;

    @Autowired
    public WebClientFileDownloader(WebClient.Builder webClientBuilder) {
        this(webClientBuilder, UrlSafetyValidator::validateUrl);
    }

    /**
     * Test seam for the validator. Production always gets {@link UrlSafetyValidator},
     * which refuses loopback and private addresses, and a local test server IS loopback,
     * so following a redirect cannot be observed through it. The refusal path is tested
     * against the real one instead, so the wiring is covered.
     */
    WebClientFileDownloader(WebClient.Builder webClientBuilder, Consumer<String> urlValidator) {
        // followRedirect stays FALSE on purpose. Letting Netty follow would skip the
        // per-hop validation below, which is the SSRF bypass 9f16b7f02 closed: an allowed
        // host redirecting to an internal address. Redirects are followed here instead,
        // one hop at a time, each validated before it is requested.
        HttpClient httpClient = HttpClient.create()
            .followRedirect(false);

        this.webClient = webClientBuilder
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .codecs(configurer -> configurer
                .defaultCodecs()
                .maxInMemorySize((int) FileConstants.MAX_FILE_SIZE_BYTES))
            .build();
        this.urlValidator = urlValidator;
    }

    @Override
    public byte[] download(String url) throws FileDownloadException {
        return download(url, FileConstants.DOWNLOAD_TIMEOUT);
    }

    @Override
    public byte[] download(String url, Duration timeout) throws FileDownloadException {
        if (url == null || url.isBlank()) {
            throw new FileDownloadException("URL is required");
        }

        logger.debug("Downloading file from: {}", url);

        // The guard runs HERE because this is where the request is made. FileToolsProvider,
        // which exposes downloading to agents, had no check of its own, so the capability
        // reached internal addresses through that door. Validating here covers it and every
        // future caller.
        //
        // DownloadFileNode validates too, before calling, and that stays. Not because its
        // tests assert it (they do, but those tests exist because the check does, which
        // proves nothing): because the node can be wired with a FileDownloader that does
        // not validate at all - MockFileDownloader is exactly that, and mock mode uses it.
        // The node-level check is real defence in depth, and it costs one DNS lookup.
        String currentUrl = url.trim();
        validateHop(currentUrl, null);

        Set<String> visited = new LinkedHashSet<>();
        visited.add(currentUrl);

        long deadlineNanos = System.nanoTime() + timeout.toNanos();

        for (int hop = 0; ; hop++) {
            Hop result = fetchOnce(currentUrl, url, remainingOrTimeout(deadlineNanos, timeout), timeout);

            if (!result.isRedirect()) {
                logger.debug("Downloaded {} bytes from {}", result.body().length, safe(currentUrl));
                return result.body();
            }

            if (hop >= MAX_REDIRECTS) {
                throw new FileDownloadException(
                    "Too many redirects (limit " + MAX_REDIRECTS + ") starting from " + safe(url));
            }

            String next = resolveLocation(currentUrl, result);
            validateHop(next, currentUrl);
            if (!visited.add(next)) {
                throw new FileDownloadException(
                    "Redirect loop: " + safe(next) + " was already requested while downloading " + safe(url));
            }
            logger.debug("Following redirect {} -> {}", safe(currentUrl), safe(next));
            currentUrl = next;
        }
    }

    /**
     * One budget for the whole chain, not one per hop: six requests each allowed the full
     * timeout would let a slow redirect chain run six times longer than the caller asked for.
     *
     * <p>This bounds the REQUESTS. The DNS resolution inside {@link #validateHop} has its
     * own ceiling (3 s, plus up to 3 s waiting on the validator's semaphore) and is not
     * subtracted from what is left here, so a chain can overrun its deadline by that much
     * per hop before this check catches it on the next pass. Known and accepted: bounding
     * it properly means giving the validator a deadline, which is a change to a security
     * helper shared by five services.
     */
    private Duration remainingOrTimeout(long deadlineNanos, Duration timeout) {
        Duration remaining = Duration.ofNanos(deadlineNanos - System.nanoTime());
        if (remaining.isZero() || remaining.isNegative()) {
            throw new FileDownloadException("Download timeout after " + timeout.toSeconds() + "s");
        }
        return remaining;
    }

    private Hop fetchOnce(String currentUrl, String originalUrl, Duration remaining, Duration timeout) {
        try {
            Hop hop = webClient.get()
                // URI.create, never the String overload. WebClient.uri(String) runs the value
                // through the UriBuilderFactory, which re-encodes an already-encoded query: the
                // '%' of a '%2F' becomes '%25', so '%2F' ships as '%252F'. That silently breaks
                // EVERY provider-presigned download URL, because their signature covers the exact
                // query string - BytePlus TOS (X-Tos-Credential), AWS S3 (X-Amz-Credential),
                // Azure SAS and GCS signed URLs all carry '%2F' there. The provider then answers
                // 400 AuthorizationHeaderMalformed, which reads like a broken credential rather
                // than a mangled URL. A URI built here is used verbatim.
                .uri(URI.create(currentUrl))
                .exchangeToMono(response -> {
                    HttpStatusCode status = response.statusCode();
                    if (status.is3xxRedirection()) {
                        String location = response.headers().header(HttpHeaders.LOCATION)
                            .stream().findFirst().orElse(null);
                        // Belt and braces: exchangeToMono already releases a body this handler
                        // does not consume. Saying so explicitly keeps the intent readable, and
                        // costs nothing.
                        return response.releaseBody()
                            .thenReturn(Hop.redirect(status.value(), location));
                    }
                    if (status.isError()) {
                        return response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .flatMap(body -> Mono.error(new FileDownloadException(
                                // The URL is named because after a redirect it is NOT the one
                                // the caller passed: a Drive link answering 403 from an
                                // interstitial host sends the reader to inspect the wrong link.
                                "Download failed with status " + status + " for " + safe(currentUrl)
                                    + (body.isBlank() ? "" : ": " + truncate(body, 200)),
                                status.value()
                            )));
                    }
                    return response.bodyToMono(byte[].class).map(Hop::body);
                })
                .timeout(remaining)
                .block();

            if (hop == null) {
                throw new FileDownloadException("No content received from URL: " + safe(currentUrl));
            }
            return hop;

        } catch (FileDownloadException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof TimeoutException || e.getCause() instanceof TimeoutException) {
                logger.error("Timeout downloading from {}", safe(currentUrl));
                throw new FileDownloadException("Download timeout after " + timeout.toSeconds() + "s", e);
            }
            // WebClient words its failure around the url it called, query (signature) included.
            String reason = ReportedParams.scrubUrl(ReportedParams.scrubUrl(e.getMessage(), currentUrl), originalUrl);
            logger.error("Failed to download from {} (requested {}): {}", safe(currentUrl), safe(originalUrl), reason);
            throw new FileDownloadException(
                "Download failed for " + safe(currentUrl) + ": " + reason, e);
        }
    }

    /**
     * Resolves a {@code Location} against the URL that produced it, so a relative target
     * ("/target", "../file.bin") reaches the right host.
     */
    private String resolveLocation(String currentUrl, Hop redirect) {
        String location = redirect.location();
        if (location == null || location.isBlank()) {
            throw new FileDownloadException(
                "Download failed with status " + redirect.status()
                    + ": redirect without a Location header, from " + safe(currentUrl),
                redirect.status());
        }
        try {
            return URI.create(currentUrl).resolve(encodeIllegalCharacters(location.trim())).toString();
        } catch (IllegalArgumentException e) {
            throw new FileDownloadException(
                "Cannot follow redirect from " + safe(currentUrl) + ": malformed Location "
                    + truncate(safe(location), 200),
                redirect.status(), e);
        }
    }

    /**
     * Characters that cannot legally appear in a URI, that servers send anyway, and that
     * browsers percent-encode before carrying on. Refusing them would turn a download that
     * works in a browser into a platform failure.
     *
     * <p>None of them is a delimiter anywhere in a URI, so encoding them can never change
     * how one parses, and {@code %} is never touched, so the escapes of a presigned target
     * survive intact. That is the property that matters here, and it is tested.
     *
     * <p>{@code [} and {@code ]} are deliberately NOT in the set even though browsers
     * encode them in a path or query, because they are the delimiters of an IPv6 literal
     * host: encoding them would break {@code http://[2001:db8::1]/file}. The cost is a
     * known gap, a bracketed query parameter ({@code filter[name]=x}) in a Location still
     * fails. Closing it means encoding only past the authority, which needs the parse this
     * method exists to make possible.
     */
    private static final String ILLEGAL_URI_CHARACTERS = " \"<>{}|\\^`";

    private static String encodeIllegalCharacters(String location) {
        StringBuilder out = null;
        for (int i = 0; i < location.length(); i++) {
            char c = location.charAt(i);
            if (ILLEGAL_URI_CHARACTERS.indexOf(c) < 0) {
                if (out != null) {
                    out.append(c);
                }
                continue;
            }
            if (out == null) {
                out = new StringBuilder(location.length() + 8).append(location, 0, i);
            }
            out.append('%').append(String.format("%02X", (int) c));
        }
        return out == null ? location : out.toString();
    }

    /**
     * Decides whether a URL may be requested. Package-private so the https rule can be
     * tested directly: it needs a TLS origin, and the loopback test server has none, so
     * its wiring into the redirect loop is covered only by the refusal path above it.
     *
     * @param from the URL that redirected here, or {@code null} for the URL the caller asked for
     */
    void validateHop(String target, String from) {
        // Only http is called a downgrade. Any other scheme (ftp, file, gopher) is refused
        // a line below by the validator, which names the real reason; claiming a downgrade
        // there would describe a mechanism that is not what happened.
        if (from != null && isScheme(from, "https") && isScheme(target, "http")) {
            // A downgrade would hand the bytes, and any credential the caller put in the
            // query of a presigned URL, to a plaintext connection. Browsers allow it; a
            // server-side fetcher has no reason to.
            throw new UrlNotAllowedException(
                "Refused to follow redirect from " + safe(from) + " to " + safe(target)
                    + ": it downgrades https to http");
        }
        try {
            urlValidator.accept(target);
        } catch (UrlResolutionException e) {
            // The URL was not CHECKED, as opposed to checked and refused: the validator's
            // DNS ran out of capacity, timed out or was interrupted. Those describe this
            // process under load, not the URL, and the same URL will very likely pass a
            // moment later. It must stay an ordinary FileDownloadException, because that
            // is what tells an agent to retry rather than rewrite a URL that was fine.
            throw new FileDownloadException("Could not check " + safe(target) + ": "
                    + ReportedParams.scrubUrl(e.getMessage(), target), e);
        } catch (RuntimeException e) {
            // RuntimeException, not just IllegalArgumentException: the validator resolves
            // DNS on a shared executor and can surface other unchecked failures. Every one
            // of them means "this URL was not cleared", and download() owes its callers a
            // FileDownloadException rather than whatever the resolver happened to throw.
            if (from == null) {
                throw new UrlNotAllowedException("Refused to download " + safe(target) + ": "
                        + ReportedParams.scrubUrl(e.getMessage(), target));
            }
            throw new UrlNotAllowedException(
                "Refused to follow redirect from " + safe(from) + " to " + safe(target) + ": "
                        + ReportedParams.scrubUrl(e.getMessage(), target));
        }
    }

    private static boolean isScheme(String url, String scheme) {
        try {
            return scheme.equalsIgnoreCase(URI.create(url).getScheme());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private String truncate(String s, int maxLength) {
        if (s == null) return "";
        return s.length() <= maxLength ? s : s.substring(0, maxLength) + "...";
    }

    /**
     * One response: either the bytes, or a redirect to follow.
     */
    private record Hop(byte[] body, int status, String location) {
        static Hop body(byte[] bytes) {
            return new Hop(bytes, 200, null);
        }

        static Hop redirect(int status, String location) {
            return new Hop(null, status, location);
        }

        boolean isRedirect() {
            return body == null;
        }
    }

    /**
     * A url as a message or log line may show it: credential query parameters (a presigned
     * url's signature, {@code ?token=}, {@code ?key=}) withheld. These messages are logged and
     * returned to the caller as the step's or tool's error.
     */
    private static String safe(String url) {
        return url == null ? null : ReportedParams.maskUrlSecrets(url);
    }
}
