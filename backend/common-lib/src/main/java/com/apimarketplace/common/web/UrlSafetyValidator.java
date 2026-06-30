package com.apimarketplace.common.web;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Validates URLs to prevent Server-Side Request Forgery (SSRF) attacks.
 *
 * Rejects:
 * - Private IP ranges (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16)
 * - Loopback addresses (127.0.0.0/8, ::1)
 * - Link-local addresses (169.254.0.0/16 - cloud metadata endpoint)
 * - localhost by name
 * - Non-HTTP(S) schemes (ftp, file, etc.)
 *
 * Template placeholders like {region} or {account} are tolerated at format/DNS
 * time - they are substituted with a safe literal before URI parsing, and DNS
 * resolution is skipped when placeholders touch the host. The fully resolved
 * URL is re-validated at execution time.
 *
 * Used by HttpRequestNode, DownloadFileNode, RssNode, and catalog-service.
 */
public final class UrlSafetyValidator {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[^{}/\\s]+\\}");
    private static final String PLACEHOLDER_SUBSTITUTE = "xplaceholderx";
    private static final Duration DNS_RESOLUTION_TIMEOUT = Duration.ofSeconds(3);
    private static final int DNS_RESOLUTION_MAX_CONCURRENCY = 16;
    private static final Semaphore DNS_RESOLUTION_PERMITS =
            new Semaphore(DNS_RESOLUTION_MAX_CONCURRENCY, true);
    private static final ExecutorService DNS_RESOLVER_EXECUTOR =
            Executors.newFixedThreadPool(DNS_RESOLUTION_MAX_CONCURRENCY, new DnsThreadFactory());
    private static volatile DnsResolver dnsResolver = InetAddress::getAllByName;

    private UrlSafetyValidator() {
        // Utility class
    }

    private static String substitutePlaceholders(String url) {
        return PLACEHOLDER.matcher(url).replaceAll(PLACEHOLDER_SUBSTITUTE);
    }

    private static boolean hasPlaceholder(String s) {
        return s != null && PLACEHOLDER.matcher(s).find();
    }

    /**
     * Validates that the given URL is safe to fetch.
     *
     * @param url the URL to validate
     * @throws IllegalArgumentException if the URL is unsafe or malformed
     */
    public static void validateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL must not be null or blank");
        }

        String trimmed = url.trim();
        boolean hadPlaceholders = hasPlaceholder(trimmed);
        String parsable = hadPlaceholders ? substitutePlaceholders(trimmed) : trimmed;

        URI uri;
        try {
            uri = URI.create(parsable);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Malformed URL: " + url);
        }

        // Validate scheme
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException(
                "Only http and https schemes are allowed (got: "
                    + (scheme == null ? "<none>" : scheme)
                    + ") for URL: " + url);
        }

        // Validate host
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL must have a valid hostname");
        }

        // Reject localhost by name
        String lowerHost = host.toLowerCase();
        if ("localhost".equals(lowerHost) || lowerHost.endsWith(".localhost")) {
            throw new IllegalArgumentException(
                "Requests to localhost are not allowed");
        }

        // Skip DNS resolution when the host still contained a template placeholder -
        // we cannot resolve a templated hostname; the execution layer re-validates
        // the fully substituted URL before any outbound request.
        if (lowerHost.contains(PLACEHOLDER_SUBSTITUTE)) {
            return;
        }

        // Resolve hostname and check all IP addresses
        InetAddress[] addresses = resolveHostWithTimeout(host);

        for (InetAddress addr : addresses) {
            if (isUnsafeAddress(addr)) {
                throw new IllegalArgumentException(
                    "Requests to private/internal network addresses are not allowed: " + host);
            }
        }
    }

    /**
     * Validates URL format without DNS resolution. Checks scheme (http/https),
     * hostname presence, and rejects localhost by name.
     * Use this at registration/save time. Full SSRF validation (with DNS) should
     * be done at execution time via {@link #validateUrl(String)}.
     *
     * @param url the URL to validate
     * @throws IllegalArgumentException if the URL format is invalid
     */
    public static void validateUrlFormat(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL must not be null or blank");
        }

        String trimmed = url.trim();
        String parsable = hasPlaceholder(trimmed) ? substitutePlaceholders(trimmed) : trimmed;

        URI uri;
        try {
            uri = URI.create(parsable);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Malformed URL: " + url);
        }

        // Validate scheme
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException(
                "Only http and https schemes are allowed (got: "
                    + (scheme == null ? "<none>" : scheme)
                    + ") for URL: " + url);
        }

        // Validate host
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL must have a valid hostname");
        }

        // Reject localhost by name
        String lowerHost = host.toLowerCase();
        if ("localhost".equals(lowerHost) || lowerHost.endsWith(".localhost")) {
            throw new IllegalArgumentException(
                "Requests to localhost are not allowed");
        }
    }

    /**
     * Checks whether an IP address belongs to a private, loopback, or link-local range.
     */
    static boolean isUnsafeAddress(InetAddress address) {
        return address.isLoopbackAddress()
            || address.isSiteLocalAddress()
            || address.isLinkLocalAddress()
            || address.isAnyLocalAddress();
    }

    private static InetAddress[] resolveHostWithTimeout(String host) {
        try {
            boolean acquired = DNS_RESOLUTION_PERMITS.tryAcquire(
                    DNS_RESOLUTION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new IllegalArgumentException("DNS resolution capacity exceeded for hostname: " + host);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("DNS resolution interrupted for hostname: " + host);
        }

        var future = DNS_RESOLVER_EXECUTOR.submit(() -> {
            try {
                return dnsResolver.resolve(host);
            } finally {
                DNS_RESOLUTION_PERMITS.release();
            }
        });

        try {
            return future.get(DNS_RESOLUTION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalArgumentException("DNS resolution timed out for hostname: " + host);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("DNS resolution interrupted for hostname: " + host);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof UnknownHostException) {
                throw new IllegalArgumentException("Cannot resolve hostname: " + host);
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalArgumentException("Cannot resolve hostname: " + host);
        }
    }

    static void setDnsResolverForTests(DnsResolver resolver) {
        dnsResolver = resolver == null ? InetAddress::getAllByName : resolver;
    }

    static void resetDnsResolverForTests() {
        dnsResolver = InetAddress::getAllByName;
    }

    @FunctionalInterface
    interface DnsResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private static final class DnsThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "url-safety-dns-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
