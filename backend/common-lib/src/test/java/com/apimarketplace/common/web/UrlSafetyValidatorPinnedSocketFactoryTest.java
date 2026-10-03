package com.apimarketplace.common.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.net.SocketFactory;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LC-073 / LC-075: the connectors that speak TLS could not be pinned by substituting the vetted
 * address for the configured hostname, because certificate identity and SNI are both checked
 * against the name. They kept the name, resolved it a second time inside the client library, and
 * whoever controlled DNS for the configured host decided where the socket went.
 *
 * <p>{@link UrlSafetyValidator.PinnedAddressSocketFactory} separates the two: the library keeps the
 * name, the socket goes to the vetted address. Every test here proves that separation on a REAL
 * socket, never a mock, because the whole claim is about which address a socket actually reaches.
 *
 * <p>Nothing here touches DNS or the outside network: the pinned target is a loopback
 * {@link ServerSocket} this test owns, and the address the caller ASKS for is a fabricated
 * {@link InetAddress} carrying a TEST-NET-3 literal (RFC 5737, guaranteed unroutable), so a socket
 * that ignored the pin would fail rather than reach anything.
 */
@DisplayName("UrlSafetyValidator - pinned socket factory (LC-073)")
class UrlSafetyValidatorPinnedSocketFactoryTest {

    /**
     * The address the CALLER asks for: documentation-only per RFC 5737 and carrying a hostname, so
     * it stands in for "whatever the client library's own second DNS lookup returned".
     */
    private static InetAddress attackerChosenAddress() throws Exception {
        return InetAddress.getByAddress("relay.example.test", new byte[] {(byte) 203, 0, 113, 7});
    }

    private static InetAddress loopback() throws Exception {
        return InetAddress.getByName("127.0.0.1");
    }

    @AfterEach
    void clearEgressSettings() {
        System.clearProperty(UrlSafetyValidator.PRIVATE_EGRESS_ALLOW_LIST_PROPERTY);
        UrlSafetyValidator.invalidateSettingCachesForTests();
    }

    @Nested
    @DisplayName("The socket reaches the pinned address, not the one it was asked for")
    class Pinning {

        @Test
        @DisplayName("an unconnected socket from the factory redirects connect() to the pinned address")
        void unconnectedSocketRedirectsConnect() throws Exception {
            // This is the shape every client here uses: createSocket() with no arguments, then
            // connect() to an InetSocketAddress the library built from the hostname itself.
            try (ServerSocket server = new ServerSocket(0, 1, loopback())) {
                SocketFactory factory = UrlSafetyValidator.pinnedSocketFactory(loopback());

                Socket socket = factory.createSocket();
                assertFalse(socket.isConnected(),
                    "the factory must hand back an UNCONNECTED socket: pgjdbc only connects it"
                        + " itself when isConnected() is false");

                socket.connect(new InetSocketAddress(attackerChosenAddress(), server.getLocalPort()), 2000);

                try (Socket accepted = server.accept(); Socket ignored = socket) {
                    assertNotNull(accepted, "the pinned target should have accepted the connection");
                    assertEquals("127.0.0.1", socket.getInetAddress().getHostAddress(),
                        "the socket must land on the vetted address, not on the address the caller"
                            + " asked for");
                }
            }
        }

        @Test
        @DisplayName("the port the caller asked for is the port that is used")
        void keepsTheRequestedPort() throws Exception {
            // Two servers, one pinned address. Only the port distinguishes them, so a factory that
            // hard-coded a port instead of carrying the caller's would connect to the wrong one.
            try (ServerSocket first = new ServerSocket(0, 1, loopback());
                 ServerSocket second = new ServerSocket(0, 1, loopback())) {
                SocketFactory factory = UrlSafetyValidator.pinnedSocketFactory(loopback());

                Socket socket = factory.createSocket();
                socket.connect(new InetSocketAddress(attackerChosenAddress(), second.getLocalPort()), 2000);

                try (Socket accepted = second.accept(); Socket ignored = socket) {
                    assertEquals(second.getLocalPort(), socket.getPort());
                    assertNotNull(accepted);
                }
            }
        }

        @Test
        @DisplayName("the connect(SocketAddress) overload without a timeout is redirected too")
        void redirectsTheNoTimeoutOverload() throws Exception {
            // Jakarta Mail calls this overload whenever no connectiontimeout is configured. Missing
            // it would leave a silent unpinned path through the same factory.
            try (ServerSocket server = new ServerSocket(0, 1, loopback())) {
                SocketFactory factory = UrlSafetyValidator.pinnedSocketFactory(loopback());

                Socket socket = factory.createSocket();
                socket.connect(new InetSocketAddress(attackerChosenAddress(), server.getLocalPort()));

                try (Socket accepted = server.accept(); Socket ignored = socket) {
                    assertEquals("127.0.0.1", socket.getInetAddress().getHostAddress());
                    assertNotNull(accepted);
                }
            }
        }

        @Test
        @DisplayName("the pre-connecting createSocket(host, port) overload is pinned as well")
        void preConnectingOverloadIsPinned() throws Exception {
            try (ServerSocket server = new ServerSocket(0, 1, loopback())) {
                SocketFactory factory = UrlSafetyValidator.pinnedSocketFactory(loopback());

                try (Socket socket = factory.createSocket("relay.example.test", server.getLocalPort());
                     Socket accepted = server.accept()) {
                    assertEquals("127.0.0.1", socket.getInetAddress().getHostAddress());
                    assertNotNull(accepted);
                }
            }
        }

        @Test
        @DisplayName("an endpoint that is not an IP endpoint is refused, never passed through")
        void refusesANonIpEndpoint() throws Exception {
            // Passing an unknown SocketAddress subtype through to the JDK would be a way around
            // the pin, so it is a refusal rather than a fallback.
            SocketFactory factory = UrlSafetyValidator.pinnedSocketFactory(loopback());
            Socket socket = factory.createSocket();
            SocketAddress unixLike = java.net.UnixDomainSocketAddress.of(Path.of("socket.sock"));

            IOException error = assertThrows(IOException.class, () -> socket.connect(unixLike));

            assertTrue(error.getMessage().contains("can only connect to an IP endpoint"),
                error.getMessage());
            socket.close();
        }
    }

    @Nested
    @DisplayName("The reflective constructor a JDBC driver uses")
    class ReflectiveConstructor {

        /**
         * pgjdbc instantiates {@code socketFactory} by class NAME, trying a (Properties), then a
         * (String), then a no-arg constructor. Only the (String) form can carry the vetted address,
         * so this test performs exactly that reflective route: if the class stops being public, the
         * constructor stops being public, or the class is renamed, every TLS database connection
         * fails at connect time and this test is what says so first.
         */
        @Test
        @DisplayName("the class name in the JDBC URL resolves to a factory pinned to the argument")
        void instantiatesThroughTheSameRouteTheDriverUses() throws Exception {
            String className = UrlSafetyValidator.PinnedAddressSocketFactory.class.getName();

            Class<?> loaded = Class.forName(className);
            Constructor<?> constructor = loaded.getConstructor(String.class);
            Object factory = constructor.newInstance("93.184.216.34");

            assertInstanceOf(SocketFactory.class, factory,
                "pgjdbc casts the instance to javax.net.SocketFactory");
            assertEquals("93.184.216.34",
                ((UrlSafetyValidator.PinnedAddressSocketFactory) factory)
                    .pinnedAddress().getHostAddress());
        }

        @Test
        @DisplayName("a hostname is refused rather than resolved")
        void refusesAHostname() {
            // Resolving here would reintroduce the very lookup this class exists to remove.
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new UrlSafetyValidator.PinnedAddressSocketFactory("db.example.com"));

            assertTrue(error.getMessage().contains("IP literal, not a hostname"), error.getMessage());
        }

        @Test
        @DisplayName("an internal address is refused, and the self-hosted allow-list still reopens it")
        void reappliesTheSharedFilterAndItsOptIn() {
            assertThrows(IllegalArgumentException.class,
                () -> new UrlSafetyValidator.PinnedAddressSocketFactory("10.0.9.5"),
                "the argument is re-judged, so the factory cannot become a way past the guard");

            System.setProperty(UrlSafetyValidator.PRIVATE_EGRESS_ALLOW_LIST_PROPERTY, "10.0.0.0/8");
            UrlSafetyValidator.invalidateSettingCachesForTests();

            assertDoesNotThrow(() -> new UrlSafetyValidator.PinnedAddressSocketFactory("10.0.9.5"),
                "a self-hosted install that opted its range in must still be able to connect");
        }

        @Test
        @DisplayName("the link-local metadata range is refused even with the allow-list open")
        void neverAllowsTheMetadataRange() {
            System.setProperty(UrlSafetyValidator.PRIVATE_EGRESS_ALLOW_LIST_PROPERTY, "0.0.0.0/0");
            UrlSafetyValidator.invalidateSettingCachesForTests();

            assertThrows(IllegalArgumentException.class,
                () -> new UrlSafetyValidator.PinnedAddressSocketFactory("169.254.169.254"));
        }

        @Test
        @DisplayName("a blank or null argument is refused")
        void refusesABlankArgument() {
            assertThrows(IllegalArgumentException.class,
                () -> new UrlSafetyValidator.PinnedAddressSocketFactory((String) null));
            assertThrows(IllegalArgumentException.class,
                () -> new UrlSafetyValidator.PinnedAddressSocketFactory("   "));
            assertThrows(IllegalArgumentException.class,
                () -> new UrlSafetyValidator.PinnedAddressSocketFactory((InetAddress) null));
        }
    }

    @Nested
    @DisplayName("Jakarta Mail wiring")
    class JavaMailWiring {

        @Test
        @DisplayName("the factory is published as an INSTANCE, which is the only form Jakarta Mail reads")
        void publishesAnInstance() throws Exception {
            Properties props = new Properties();

            UrlSafetyValidator.applyJavaMailAddressPinning(props, "mail.smtp", loopback());

            Object factory = props.get("mail.smtp.socketFactory");
            assertInstanceOf(SocketFactory.class, factory,
                "Jakarta Mail tests the property value with instanceof SocketFactory; a class NAME"
                    + " belongs to a different property and would be ignored here");
            assertEquals("127.0.0.1",
                ((UrlSafetyValidator.PinnedAddressSocketFactory) factory)
                    .pinnedAddress().getHostAddress());
        }

        @Test
        @DisplayName("socketFactory.fallback is forced to false, because its default reopens the window")
        void disablesTheFallback() throws Exception {
            // Jakarta Mail's default is true: when creating the socket through the factory fails it
            // retries the whole connection with NO factory, building a plain socket from the
            // hostname, which resolves a second time and dials whatever comes back. That retry is
            // precisely the rebinding window, so the pin is only worth anything with it off.
            Properties props = new Properties();

            UrlSafetyValidator.applyJavaMailAddressPinning(props, "mail.imaps", loopback());

            assertEquals("false", props.get("mail.imaps.socketFactory.fallback"));
        }

        @Test
        @DisplayName("the prefix is honoured, so SMTP and IMAP do not overwrite each other")
        void honoursThePrefix() throws Exception {
            Properties props = new Properties();

            UrlSafetyValidator.applyJavaMailAddressPinning(props, "mail.imap", loopback());

            assertNotNull(props.get("mail.imap.socketFactory"));
            assertEquals("false", props.get("mail.imap.socketFactory.fallback"));
            assertEquals(null, props.get("mail.smtp.socketFactory"));
        }
    }
}
