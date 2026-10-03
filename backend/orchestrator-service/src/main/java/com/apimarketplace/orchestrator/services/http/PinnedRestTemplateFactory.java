package com.apimarketplace.orchestrator.services.http;

import com.apimarketplace.common.web.SafeAddressResolverGroup;
import com.apimarketplace.common.web.UrlSafetyValidator;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * Builds the RestTemplate that {@code core:http_request} (both its default and its per-node
 * timeout override) leaves through, pinned to the address {@link UrlSafetyValidator#validateUrl}
 * already vetted (LC-002 / LC-006, CASA readiness round 3: DNS rebinding).
 *
 * <p>{@code HttpRequestNode} validates the configured URL and then, pre-fix, handed the STRING to
 * a {@code RestTemplate} backed by {@code SimpleClientHttpRequestFactory} (HttpURLConnection),
 * which resolved the name AGAIN through the JVM's own DNS cache when it connected. A name that
 * answers public to the check and private to the connect (DNS rebinding) then reached the cluster
 * carrying whatever auth header or body the workflow author configured. Every RestTemplate built
 * here runs on Reactor Netty with {@link SafeAddressResolverGroup#strict()}, matching the strict,
 * no-egress-allow-list check {@code UrlSafetyValidator.validateUrl} already performs: the resolver
 * and the channel hooks judge the exact address the socket dials, so no second lookup happens
 * between the check and the connect. Redirects are left disabled, same as the factory this
 * replaces: a redirect is a new target the check never saw.
 *
 * <p>Deliberately not the shared, unqualified {@code RestTemplate} bean ({@code RestTemplateConfig
 * .restTemplate()}): that one also carries internal service-to-service calls to private cluster
 * addresses (catalog, credential, conversation...), which this predicate would refuse outright.
 * Only {@code core:http_request} - a user-configured, already-SSRF-checked outbound target - uses
 * a client built here (see {@code RestTemplateConfig.httpRequestNodeRestTemplate} for the bean
 * wired into production, and {@code HttpRequestNode.resolveRestTemplate} for the per-node-timeout
 * cache that also calls {@link #build}).
 */
public final class PinnedRestTemplateFactory {

    /**
     * One pool for every client built here (the per-node-timeout cache builds several), with the
     * limits HttpURLConnection had: large response headers, short idle reuse.
     */
    private static final reactor.netty.resources.ConnectionProvider POOL =
        com.apimarketplace.common.web.OutboundHttpTransport.pool("http-request-node");

    private PinnedRestTemplateFactory() {
    }

    public static RestTemplate build(int connectTimeoutMs, int readTimeoutMs) {
        ReactorClientHttpRequestFactory factory = new ReactorClientHttpRequestFactory(pinnedHttpClient());
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return new RestTemplate(factory);
    }

    /** The Reactor Netty client under every RestTemplate built here. */
    static HttpClient pinnedHttpClient() {
        return com.apimarketplace.common.web.OutboundHttpTransport.client(POOL)
            .followRedirect(false)
            .resolver(SafeAddressResolverGroup.strict())
            .doOnChannelInit((observer, channel, remoteAddress) ->
                SafeAddressResolverGroup.assertRemoteAddressSafe(remoteAddress, UrlSafetyValidator::isUnsafeAddress))
            .doOnConnected(connection ->
                SafeAddressResolverGroup.assertRemoteAddressSafe(
                    connection.channel().remoteAddress(), UrlSafetyValidator::isUnsafeAddress));
    }
}
