package com.apimarketplace.orchestrator.services.http;

import com.apimarketplace.common.web.SafeAddressResolverGroup;
import com.apimarketplace.common.web.UrlSafetyValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LC-002 / LC-006, CASA readiness round 3: {@code core:http_request} validated the configured URL
 * with {@code UrlSafetyValidator.validateUrl}, then handed the STRING to a RestTemplate backed by
 * {@code SimpleClientHttpRequestFactory} (HttpURLConnection), which resolved the name AGAIN through
 * the JVM's own DNS cache at connect time. A name that answers public to the check and private to
 * the connect (DNS rebinding) then reached the cluster.
 *
 * <p>Every test here drives a REAL socket, never a mock, mirroring
 * {@code WebClientFileDownloaderTest}'s connect-time pinning suite (LC-073) which this class'
 * production wiring reuses (Reactor Netty + {@link SafeAddressResolverGroup}).
 */
@DisplayName("PinnedRestTemplateFactory - the transport is pinned to the vetted address (LC-002/LC-006)")
class PinnedRestTemplateFactoryTest {

    private ServerSocket internalServer;
    private final AtomicInteger accepted = new AtomicInteger();
    private Thread accepter;

    @BeforeEach
    void startAnInternalTarget() throws Exception {
        internalServer = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        accepter = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try (Socket client = internalServer.accept()) {
                    accepted.incrementAndGet();
                } catch (java.io.IOException e) {
                    return;
                }
            }
        }, "internal-target-accepter");
        accepter.setDaemon(true);
        accepter.start();
    }

    @AfterEach
    void stopTheInternalTarget() throws Exception {
        accepter.interrupt();
        internalServer.close();
    }

    /**
     * The rebinding scenario itself: whatever a pre-flight check approved, the transport this
     * factory builds must refuse a target that resolves (or, as here, simply IS) an internal
     * address - the pre-flight is bypassed entirely, so only the transport stands between the
     * request and the internal peer.
     */
    @Test
    @DisplayName("regression: an internal peer is refused at connect, whatever the pre-flight approved")
    void refusesAnInternalPeerRegardlessOfPreflight() {
        RestTemplate pinned = PinnedRestTemplateFactory.build(2000, 2000);
        int port = internalServer.getLocalPort();

        assertThrows(Exception.class,
            () -> pinned.getForEntity(URI.create("http://127.0.0.1:" + port + "/"), String.class),
            "the plain HttpURLConnection RestTemplate this replaced would have connected");
        assertEquals(0, accepted.get(), "the internal target must never receive the request");
    }

    @Test
    @DisplayName("control: the same request through an unpinned RestTemplate reaches the server")
    void controlUnpinnedClientConnects() {
        // The accepter is a raw socket, not an HTTP server: it never writes a response, so the
        // client sees "unexpected end of file" once connected - and HttpURLConnection retries
        // that failure once on its own, so more than one connection can land. Both are expected
        // and irrelevant here - the control's whole claim is that the TCP connection is REACHED
        // AT ALL, which is exactly the 0-vs-something contrast with the pinned test above.
        try {
            new RestTemplate().getForEntity(
                URI.create("http://127.0.0.1:" + internalServer.getLocalPort() + "/"), String.class);
        } catch (org.springframework.web.client.ResourceAccessException expected) {
            // ignored - see above
        }
        assertTrue(accepted.get() >= 1);
    }

    @Test
    @DisplayName("regression: large response headers and a dedicated pool, as HttpURLConnection had (not Netty's 8 KB default)")
    void carriesTheOutboundTransportLimits() {
        reactor.netty.http.client.HttpClient client = PinnedRestTemplateFactory.pinnedHttpClient();

        org.assertj.core.api.Assertions.assertThat(client.configuration().decoder().maxHeaderSize())
            .isEqualTo(com.apimarketplace.common.web.OutboundHttpTransport.MAX_RESPONSE_HEADER_BYTES);
        org.assertj.core.api.Assertions.assertThat(client.configuration().connectionProvider())
            .isSameAs(PinnedRestTemplateFactory.pinnedHttpClient().configuration().connectionProvider())
            .isNotSameAs(reactor.netty.http.client.HttpClient.create().configuration().connectionProvider());
    }

    @Test
    @DisplayName("the built RestTemplate runs on Reactor Netty, never HttpURLConnection")
    void runsOnReactorNetty() {
        assertInstanceOf(ReactorClientHttpRequestFactory.class,
            PinnedRestTemplateFactory.build(2000, 2000).getRequestFactory());
    }

    /**
     * The pure resolver claim, independent of any socket: a name that answers with one public and
     * one private address is a rebinding attempt, refused whichever address a caller happened to
     * check first. Mirrors {@code WebClientFileDownloaderTest#pinningResolverRefusesARebindingAnswer}.
     */
    @Test
    @DisplayName("the pinning resolver refuses a name that resolves to ANY internal address")
    void pinningResolverRefusesARebindingAnswer() throws Exception {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
            () -> SafeAddressResolverGroup.vet(
                "rebind.example.com",
                UrlSafetyValidator::isUnsafeAddress,
                host -> new InetAddress[] {
                    InetAddress.getByName("93.184.216.34"),
                    InetAddress.getByName("10.0.0.7")}));

        assertEquals(true, refused.getMessage().contains(SafeAddressResolverGroup.INTERNAL_TARGET_MESSAGE),
            refused.getMessage());
    }

    @Test
    @DisplayName("the channel hook refuses an internal peer and ignores an unresolved one")
    void channelHookJudgesThePeer() {
        assertThrows(IllegalArgumentException.class, () -> SafeAddressResolverGroup.assertRemoteAddressSafe(
            new InetSocketAddress("169.254.169.254", 80), UrlSafetyValidator::isUnsafeAddress));

        // Must not throw: an unresolved address carries no InetAddress to judge yet.
        SafeAddressResolverGroup.assertRemoteAddressSafe(
            InetSocketAddress.createUnresolved("example.com", 80), UrlSafetyValidator::isUnsafeAddress);
    }
}
