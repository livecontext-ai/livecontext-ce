package com.apimarketplace.catalog.service.http;

import com.apimarketplace.common.web.OutboundHttpTransport;
import com.apimarketplace.common.web.SafeAddressResolverGroup;
import com.apimarketplace.common.web.UrlSafetyValidator;
import com.apimarketplace.sse.SseStreamConsumer;
import com.apimarketplace.sse.WebClientSseConsumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

/**
 * The transport every catalog tool execution leaves through, with the connection PINNED to the
 * address the SSRF policy vetted (LC-073 / LC-006, CASA readiness).
 *
 * <p>{@code HttpExecutionService.validatedTarget} resolves and judges the final URL, but the
 * shared {@code RestTemplate} (HttpURLConnection) and the shared WebClient then resolved the name
 * AGAIN, through the JVM DNS cache or Reactor Netty's own resolver. A name answering public to the
 * check and private to the connect reached the cluster with the caller's credential headers. Both
 * clients here run on Reactor Netty with {@link SafeAddressResolverGroup#egress()}: the answer the
 * socket is dialled with is the answer that is vetted, and a channel hook re-checks the connected
 * peer for IP literals (which bypass any resolver).
 *
 * <p>Deliberately NOT registered as {@code RestTemplate} / {@code SseStreamConsumer} beans: the
 * shared {@code restTemplate} bean also carries internal service-to-service calls to private
 * addresses, and in the CE monolith a second bean of those types would change what every other
 * module's by-type injection resolves to. Consumers take this component and fall back to their
 * constructor-injected transport only in unit tests.
 *
 * <p>Redirects are never followed (same as the shared factory): a redirect is a new target the
 * check never saw.
 */
@Component
public class OutboundHttpClients {

    /** Matches CatalogWebClientConfig, which the streaming consumer used before. */
    private static final int MAX_IN_MEMORY_BYTES = 50 * 1024 * 1024;

    private final RestTemplate restTemplate;
    private final SseStreamConsumer sseStreamConsumer;

    public OutboundHttpClients(@Value("${http.client.connect-timeout:5000}") int connectTimeoutMs,
                               @Value("${http.client.read-timeout:30000}") int readTimeoutMs,
                               ObjectMapper objectMapper) {
        HttpClient pinned = pinnedHttpClient();

        ReactorClientHttpRequestFactory factory = new ReactorClientHttpRequestFactory(pinned);
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restTemplate = new RestTemplate(factory);

        WebClient.Builder sseBuilder = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(pinned))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BYTES));
        this.sseStreamConsumer = new WebClientSseConsumer(sseBuilder, objectMapper);
    }

    /**
     * A pool of its own ({@link OutboundHttpTransport#pool}): catalog traffic neither shares
     * connections with nor exhausts the global pool every other Netty client of the JVM uses.
     */
    static final ConnectionProvider POOL = OutboundHttpTransport.pool("catalog-outbound");

    /** The transport limits, without the address pinning (which a loopback test cannot pass). */
    static HttpClient tunedHttpClient() {
        return OutboundHttpTransport.client(POOL);
    }

    /** Reactor Netty client that resolves through, and connects to, the vetted address only. */
    static HttpClient pinnedHttpClient() {
        SafeAddressResolverGroup resolver = SafeAddressResolverGroup.egress();
        return tunedHttpClient()
                .followRedirect(false)
                .resolver(resolver)
                .doOnChannelInit((observer, channel, remoteAddress) ->
                        SafeAddressResolverGroup.assertRemoteAddressSafe(
                                remoteAddress, UrlSafetyValidator::isUnsafeEgressAddress))
                .doOnConnected(connection ->
                        SafeAddressResolverGroup.assertRemoteAddressSafe(
                                connection.channel().remoteAddress(), UrlSafetyValidator::isUnsafeEgressAddress));
    }

    public RestTemplate restTemplate() {
        return restTemplate;
    }

    public SseStreamConsumer sseStreamConsumer() {
        return sseStreamConsumer;
    }
}
