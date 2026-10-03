package com.apimarketplace.orchestrator.security;

import com.apimarketplace.common.web.UrlSafetyValidator;
import com.apimarketplace.orchestrator.execution.v2.nodes.DatabaseNode;
import org.eclipse.angus.mail.util.SocketFetcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.net.SocketFactory;
import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LC-073, the half the property-level tests cannot reach.
 *
 * <p>{@code OutboundConnectorSsrfGuardTest} proves the connectors PUBLISH a pinned socket factory:
 * that {@code mail.smtp.socketFactory} holds an instance, that the JDBC URL carries
 * {@code socketFactory} and {@code socketFactoryArg}. None of that is worth anything unless the
 * client library READS what was published, in the exact shape it was published in, and none of it
 * would notice if the library ignored the property and resolved the hostname itself. A suite that
 * only asserts its own output is a suite that certifies nothing.
 *
 * <p>So every test here drives the REAL library on a REAL socket:
 * {@link org.eclipse.angus.mail.util.SocketFetcher} is the single function Jakarta Mail routes
 * every SMTP and IMAP connection through, and the JDBC tests go through {@link DriverManager} to
 * the pgjdbc driver on this module's classpath. Each one has a control that runs the SAME code path
 * with the pin removed and proves the connection then goes somewhere else, so a green result cannot
 * come from the connection having been going to the pinned address all along.
 *
 * <p><b>No DNS and no outside network.</b> The vetted address is a loopback {@link ServerSocket}
 * this test owns. The address the caller ASKS for is {@code 127.0.0.2}: a different address on the
 * same loopback, standing in for whatever a second lookup would have returned, chosen so the
 * control fails in milliseconds instead of waiting out a connect timeout to a black hole.
 */
@DisplayName("Outbound connectors - the client library really dials the pinned address (LC-073)")
class OutboundConnectorAddressPinIntegrationTest {

    /** What the configured NAME resolves to on the client library's own second lookup. */
    private static final String REQUESTED_HOST = "127.0.0.2";

    /** What the shared guard vetted, and the only address any socket here may reach. */
    private static final String PINNED_HOST = "127.0.0.1";

    private ServerSocket vettedTarget;
    private AtomicInteger accepted;
    private Thread accepter;

    /**
     * The production factory minus its constructor-time re-judgement of the literal, which refuses
     * loopback unconditionally. Public with a public String constructor because pgjdbc instantiates
     * it by class name exactly as it does the production one.
     */
    public static class LoopbackPinnedFactory extends UrlSafetyValidator.PinnedAddressSocketFactory {
        public LoopbackPinnedFactory(String literal) throws java.net.UnknownHostException {
            super(InetAddress.getByName(literal));
        }
    }

    @BeforeEach
    void startTheVettedTarget() throws Exception {
        // Both addresses are loopback, which the shared guard refuses by default. Opting 127/8 in
        // is the same self-hosted escape hatch a real install uses, and it keeps the test honest:
        // PinnedAddressSocketFactory re-judges its argument, so without this the pin would be
        // refused at construction and every test below would fail for the wrong reason.
        System.setProperty(UrlSafetyValidator.PRIVATE_EGRESS_ALLOW_LIST_PROPERTY, "127.0.0.0/8");

        vettedTarget = new ServerSocket(0, 8, InetAddress.getByName(PINNED_HOST));
        accepted = new AtomicInteger();
        accepter = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try (Socket client = vettedTarget.accept()) {
                    accepted.incrementAndGet();
                    // Closed at once. A client that speaks a protocol we do not answer then fails
                    // fast on EOF instead of blocking on a read, which is all this test needs: the
                    // claim is about which address was reached, not about what was said.
                } catch (IOException e) {
                    return;
                }
            }
        }, "pinned-target-accepter");
        accepter.setDaemon(true);
        accepter.start();
    }

    @AfterEach
    void stopTheVettedTarget() throws Exception {
        accepter.interrupt();
        vettedTarget.close();
        System.clearProperty(UrlSafetyValidator.PRIVATE_EGRESS_ALLOW_LIST_PROPERTY);
    }

    private int port() {
        return vettedTarget.getLocalPort();
    }

    /** Blocks briefly for the accepter thread to record a connection it has already received. */
    private boolean reachedTheVettedTarget() throws InterruptedException {
        for (int i = 0; i < 100 && accepted.get() == 0; i++) {
            Thread.sleep(20);
        }
        return accepted.get() > 0;
    }

    @Nested
    @DisplayName("Jakarta Mail (SMTP and IMAP)")
    class JakartaMail {

        private Properties pinnedProps(String prefix) throws Exception {
            Properties props = new Properties();
            props.put(prefix + ".host", REQUESTED_HOST);
            props.put(prefix + ".port", String.valueOf(port()));
            props.put(prefix + ".connectiontimeout", "3000");
            props.put(prefix + ".timeout", "3000");
            UrlSafetyValidator.applyJavaMailAddressPinning(
                props, prefix, InetAddress.getByName(PINNED_HOST));
            return props;
        }

        @Test
        @DisplayName("a cleartext connection goes to the pinned address, not to the host it was given")
        void cleartextIsPinned() throws Exception {
            Properties props = pinnedProps("mail.smtp");

            try (Socket socket = SocketFetcher.getSocket(
                    REQUESTED_HOST, port(), props, "mail.smtp", false)) {
                assertEquals(PINNED_HOST, socket.getInetAddress().getHostAddress());
            }

            assertTrue(reachedTheVettedTarget(),
                "the vetted target must be what actually accepted the connection");
        }

        @Test
        @DisplayName("an implicit-TLS connection is pinned too, and is still a TLS socket")
        void implicitTlsIsPinnedAndStaysEncrypted() throws Exception {
            // The branch the whole IMAPS mailbox path and SMTP port 465 depend on, and the one
            // worth doubting: Jakarta Mail looks for "<prefix>.ssl.socketFactory" FIRST when the
            // connection is implicitly encrypted, and only falls through to the plain
            // "<prefix>.socketFactory" this project sets. Had it stopped at the ssl-specific
            // property, every property-level assertion would still pass and no mailbox connection
            // would be pinned at all.
            //
            // The second assertion guards the opposite failure. Handing a library a PLAIN socket
            // factory is a classic way to silently downgrade an encrypted connection to cleartext,
            // which would trade one finding for a worse one. Jakarta Mail instead layers TLS onto
            // the socket the factory produced, using the configured host NAME, which is exactly why
            // certificate identity and SNI survive the pin.
            // The vetted target is a plain ServerSocket that closes at once, so the handshake
            // cannot complete. That is what makes the two claims separable rather than a
            // compromise: the failure is an SSLException raised while negotiating TLS, which can
            // only happen after a socket connected AND was wrapped in TLS, and the target that
            // accepted it is the pinned one. Had the factory been ignored, the call would have
            // failed earlier and differently, connecting to nothing at 127.0.0.2, which is exactly
            // what the control below asserts.
            Properties props = pinnedProps("mail.imaps");
            props.put("mail.imaps.ssl.enable", "true");
            props.put("mail.imaps.ssl.protocols", "TLSv1.2 TLSv1.3");

            IOException failure = assertThrows(IOException.class, () -> SocketFetcher.getSocket(
                REQUESTED_HOST, port(), props, "mail.imaps", true));

            assertInstanceOf(SSLException.class, failure,
                "a plain pinned factory must not downgrade the connection to cleartext: the failure"
                    + " has to come from the TLS layer, proving Jakarta Mail wrapped the pinned"
                    + " socket rather than using it as-is. Got: " + failure);
            assertTrue(reachedTheVettedTarget(),
                "and the encrypted branch has to honour the same plain socketFactory property,"
                    + " which is the thing worth doubting here");
        }

        @Test
        @DisplayName("a write timeout wraps the socket, and the pin survives the wrapper")
        void survivesTheWriteTimeoutWrapper() throws Exception {
            // Not hypothetical: the send path sets mail.smtp.writetimeout, which makes Jakarta Mail
            // wrap the factory's socket in its own WriteTimeoutSocket before connecting it. A
            // wrapper that connected its own underlying socket instead of delegating would dial the
            // requested host and unpin the send, while every property assertion stayed green.
            Properties props = pinnedProps("mail.smtp");
            props.put("mail.smtp.writetimeout", "10000");

            try (Socket socket = SocketFetcher.getSocket(
                    REQUESTED_HOST, port(), props, "mail.smtp", false)) {
                assertEquals(PINNED_HOST, socket.getInetAddress().getHostAddress());
            }

            assertTrue(reachedTheVettedTarget());
        }

        @Test
        @DisplayName("socketFactory.fallback left at its default reaches the requested host instead")
        void theFallbackDefaultIsWhatTheProjectTurnsOff() throws Exception {
            // Why applyJavaMailAddressPinning always writes socketFactory.fallback=false. Jakarta
            // Mail's default is true: when the factory fails, it retries the whole connection with
            // NO factory, resolving the configured host itself and dialling whatever comes back.
            // That retry IS the rebinding window, so leaving the default in place would publish a
            // pin that any factory-side failure removes.
            //
            // The factory here always fails, which is the only way to make the retry observable.
            SocketFactory alwaysFails = new SocketFactory() {
                @Override
                public Socket createSocket() throws IOException {
                    throw new IOException("factory refuses");
                }

                @Override
                public Socket createSocket(String h, int p) throws IOException {
                    throw new IOException("factory refuses");
                }

                @Override
                public Socket createSocket(String h, int p, InetAddress la, int lp) throws IOException {
                    throw new IOException("factory refuses");
                }

                @Override
                public Socket createSocket(InetAddress h, int p) throws IOException {
                    throw new IOException("factory refuses");
                }

                @Override
                public Socket createSocket(InetAddress h, int p, InetAddress la, int lp) throws IOException {
                    throw new IOException("factory refuses");
                }
            };

            Properties withDefault = new Properties();
            withDefault.put("mail.smtp.connectiontimeout", "3000");
            withDefault.put("mail.smtp.socketFactory", alwaysFails);
            // socketFactory.fallback deliberately NOT set, so Jakarta Mail's default of true applies

            try (Socket socket = SocketFetcher.getSocket(
                    PINNED_HOST, port(), withDefault, "mail.smtp", false)) {
                assertTrue(socket.isConnected(),
                    "with the default fallback, a failing factory does not stop the connection: it"
                        + " is retried with no factory at all");
            }
            assertTrue(reachedTheVettedTarget(),
                "and that retry dials the host it was given, which is the window being closed");

            Properties pinned = new Properties();
            pinned.put("mail.smtp.connectiontimeout", "3000");
            pinned.put("mail.smtp.socketFactory", alwaysFails);
            pinned.put("mail.smtp.socketFactory.fallback", "false");

            assertThrows(IOException.class,
                () -> SocketFetcher.getSocket(PINNED_HOST, port(), pinned, "mail.smtp", false),
                "with the value the project sets, the same failure is a refusal rather than an"
                    + " unpinned connection");
        }

        @Test
        @DisplayName("control: with no factory published, both shapes reach the requested host")
        void withoutThePinTheRequestedHostIsDialled() throws Exception {
            Properties unpinned = new Properties();
            unpinned.put("mail.smtp.connectiontimeout", "2000");

            IOException cleartext = assertThrows(IOException.class,
                () -> SocketFetcher.getSocket(REQUESTED_HOST, port(), unpinned, "mail.smtp", false),
                "nothing listens on the requested address; this is the pre-fix behaviour");
            assertEquals(0, accepted.get(),
                "and the vetted target never saw it, which is what makes the tests above"
                    + " discriminating rather than tautological");

            Properties unpinnedTls = new Properties();
            unpinnedTls.put("mail.imaps.connectiontimeout", "2000");
            unpinnedTls.put("mail.imaps.ssl.enable", "true");

            IOException tls = assertThrows(IOException.class,
                () -> SocketFetcher.getSocket(REQUESTED_HOST, port(), unpinnedTls, "mail.imaps", true));

            assertEquals(0, accepted.get());
            // The encrypted control fails at CONNECT, not in the TLS layer. That is what separates
            // it from the pinned case above, whose failure was an SSLException: same call, same
            // properties minus the factory, and a categorically different outcome.
            assertTrue(unwrap(cleartext) instanceof ConnectException
                    && unwrap(tls) instanceof ConnectException,
                "unpinned, neither shape gets far enough to negotiate anything. Got: "
                    + unwrap(cleartext) + " / " + unwrap(tls));
            assertTrue(!(unwrap(tls) instanceof SSLException));
        }

        /**
         * Jakarta Mail wraps a connect failure in its own SocketConnectException, so the cause is
         * where the distinction between "never connected" and "connected then failed TLS" lives.
         */
        private Throwable unwrap(IOException e) {
            return e.getCause() != null ? e.getCause() : e;
        }
    }

    @Nested
    @DisplayName("PostgreSQL JDBC")
    class PostgresJdbc {

        /**
         * The URL the node really emits, plus a short connect timeout so the control below fails in
         * milliseconds. The timeout is the only addition; the pinning parameters are whatever
         * {@code DatabaseNode.buildPinnedJdbcUrl} produced.
         */
        private String nodeUrl(boolean ssl) throws Exception {
            String url = databaseNodeUrl("buildPinnedJdbcUrl",
                new Class<?>[] {String.class, String.class, int.class, String.class, boolean.class, InetAddress.class},
                "postgresql", REQUESTED_HOST, port(), "appdb", ssl, InetAddress.getByName(PINNED_HOST));
            // Loopback is never allow-listable (CASA readiness), so the production factory's
            // String constructor refuses 127.0.0.1 by design, before any socket exists. The driver
            // is pointed at a test subclass that differs ONLY in skipping that re-judgement, so
            // what is exercised is still pgjdbc honouring socketFactory + socketFactoryArg and the
            // production factory's socket pinning. factoryUrlNamesTheProductionClass pins the
            // production class name separately.
            String productionFactory = UrlSafetyValidator.PinnedAddressSocketFactory.class.getName();
            assertTrue(url.contains("socketFactory=" + productionFactory), url);
            url = url.replace("socketFactory=" + productionFactory,
                "socketFactory=" + LoopbackPinnedFactory.class.getName());
            return url + (url.indexOf('?') < 0 ? '?' : '&') + "connectTimeout=3&loginTimeout=3";
        }

        @Test
        @DisplayName("the production factory's String constructor refuses loopback, accepts an allow-listed range")
        void productionFactoryRejudgesItsArgument() {
            assertThrows(IllegalArgumentException.class,
                () -> new UrlSafetyValidator.PinnedAddressSocketFactory("127.0.0.1"),
                "loopback is never allow-listable, not even with 127.0.0.0/8 configured");
            System.setProperty(UrlSafetyValidator.PRIVATE_EGRESS_ALLOW_LIST_PROPERTY, "10.0.0.0/8");
            assertEquals("10.0.0.5",
                new UrlSafetyValidator.PinnedAddressSocketFactory("10.0.0.5").pinnedAddress().getHostAddress());
        }

        @Test
        @DisplayName("the driver instantiates the factory from the URL and dials the pinned address")
        void cleartextJdbcIsPinned() throws Exception {
            // This is the test that says whether the JDBC half of LC-073 works AT ALL, and equally
            // whether it is an outage. pgjdbc resolves socketFactory by class NAME and passes
            // socketFactoryArg to a single-String constructor; if the class were not public, the
            // constructor not public, the parameter misspelled, or the '$' of the nested class name
            // mangled by URL decoding, every Database node would fail to connect. A URL-string
            // assertion cannot tell any of that apart from success.
            String url = nodeUrl(false);
            assertTrue(url.contains("//" + REQUESTED_HOST + ":"),
                "the configured host must still be the URL authority: the pin must not have been"
                    + " implemented by rewriting it");

            assertThrows(SQLException.class, () -> DriverManager.getConnection(url, "u", "p"),
                "the target speaks no Postgres protocol, so the handshake fails after connecting");

            assertTrue(reachedTheVettedTarget(),
                "the socket has to have landed on the vetted address, which is only possible if"
                    + " pgjdbc really honoured socketFactory + socketFactoryArg");
        }

        @Test
        @DisplayName("a TLS connection is pinned too, and keeps the hostname in the URL")
        void tlsJdbcIsPinnedAndKeepsTheHostname() throws Exception {
            // The branch that was previously left open, on the ground that an IP literal in the URL
            // would break certificate verification. That ground was real and the conclusion was
            // not: the name stays in the authority (so sslmode=verify-full and SNI still work) and
            // only the socket is pinned.
            String url = nodeUrl(true);
            assertTrue(url.contains("//" + REQUESTED_HOST + ":") && url.contains("ssl=true"),
                "both halves have to hold at once, or the test is not about this fix");

            assertThrows(SQLException.class, () -> DriverManager.getConnection(url, "u", "p"));

            assertTrue(reachedTheVettedTarget());
        }

        @Test
        @DisplayName("control: the unpinned URL shape dials the host in the authority")
        void withoutThePinTheAuthorityIsDialled() throws Exception {
            String unpinned = databaseNodeUrl("buildJdbcUrl",
                    new Class<?>[] {String.class, String.class, int.class, String.class, boolean.class},
                    "postgresql", REQUESTED_HOST, port(), "appdb", false)
                + "?connectTimeout=3&loginTimeout=3";

            assertThrows(SQLException.class, () -> DriverManager.getConnection(unpinned, "u", "p"));
            assertEquals(0, accepted.get(),
                "pre-fix, the driver resolved the URL host itself and the vetted address was never"
                    + " reached; that is the window the socket factory closes");
        }
    }

    /**
     * DatabaseNode's JDBC URL builders, which are package-private. This test lives outside the
     * orchestrator `execution` package on purpose: that package runs in CI's parallel lane, which
     * only takes tests that open no JDBC connection, and these drive the real JDBC driver (against a
     * socket of their own). So the builders are reached by reflection rather than made public.
     */
    private static String databaseNodeUrl(String method, Class<?>[] types, Object... args) throws Exception {
        java.lang.reflect.Method builder = DatabaseNode.class.getDeclaredMethod(method, types);
        builder.setAccessible(true);
        return (String) builder.invoke(null, args);
    }
}
