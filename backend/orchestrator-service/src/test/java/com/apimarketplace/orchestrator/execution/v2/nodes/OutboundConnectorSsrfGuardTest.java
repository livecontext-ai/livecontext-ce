package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.common.web.UrlSafetyValidator;
import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.CredentialSummaryDto;
import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * LC-075 regression suite: the SSH, SFTP, Database, SMTP and IMAP nodes each take a host and a
 * port from workflow input or a stored credential and hand them straight to a socket. SSRF
 * controls were attached only to URL-shaped inputs, so none of these five was checked at all, and
 * the egress NetworkPolicy deliberately permits the data-plane ports they need.
 *
 * <p>Every test here fails against the pre-fix nodes, where the same target produced a connection
 * attempt (and, on the audited cluster, a reachable internal service) instead of a refusal.
 *
 * <p>Nothing here connects: the guard runs before the socket is opened, which is the point. The
 * targets are IP literals and internal names, so no test needs the network either.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Outbound connector nodes - SSRF guard (LC-075)")
class OutboundConnectorSsrfGuardTest {

    @Mock
    private WorkflowPlan mockPlan;

    @Mock
    private CredentialClient mockCredentialClient;

    @Mock
    private ServiceRegistry mockServiceRegistry;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        context = ExecutionContext.create(
            "run-1", "workflow-run-1", "tenant-1", "item-0", 0, Map.of(), mockPlan);
        when(mockServiceRegistry.getCredentialClient()).thenReturn(mockCredentialClient);
    }

    @AfterEach
    void clearAllowList() {
        System.clearProperty(UrlSafetyValidator.PRIVATE_EGRESS_ALLOW_LIST_PROPERTY);
    }

    private void assertRefused(NodeExecutionResult result) {
        assertFalse(result.isSuccess(), "the node should have refused the target");
        assertTrue(result.errorMessage().isPresent(), "a refusal must carry a reason");
        String message = result.errorMessage().get();
        assertTrue(message.contains("not allowed"),
            "the reason should name the refusal, got: " + message);
    }

    private CredentialSummaryDto credential(String integration, String host, int port) {
        Map<String, Object> data = new HashMap<>();
        data.put("host", host);
        data.put("port", port);
        data.put("username", "user");
        data.put("password", "pass");
        data.put("use_tls", "true");
        data.put("use_ssl", "true");
        data.put("from_email", "from@example.com");
        CredentialSummaryDto dto = new CredentialSummaryDto();
        dto.setId(1L);
        dto.setName(integration);
        dto.setIntegration(integration);
        dto.setStatus("active");
        dto.setDefault(true);
        dto.setCredentialData(data);
        return dto;
    }

    /**
     * The four targets that matter most on the audited topology: the cloud metadata endpoint, an
     * in-cluster service address, the pod's own loopback, and an IPv6 unique-local address (the
     * form that used to pass every guard in this codebase).
     */
    private static final String[] INTERNAL_HOSTS = {
        "169.254.169.254", "10.0.9.5", "127.0.0.1", "fd00::1", "metadata.google.internal"
    };

    @Nested
    @DisplayName("SSH")
    class Ssh {

        private NodeExecutionResult run(String host, int port) {
            Core.SshConfig config = new Core.SshConfig(
                host, port, "user", "password", "pass", null, "cat /etc/passwd", null, null);
            return new SshNode("core:ssh", config).execute(context);
        }

        @ParameterizedTest
        @ValueSource(strings = {"169.254.169.254", "10.0.9.5", "127.0.0.1", "fd00::1", "metadata.google.internal"})
        @DisplayName("refuses an internal host before opening a session")
        void refusesInternalHost(String host) {
            NodeExecutionResult result = run(host, 22);

            assertRefused(result);
            assertTrue(result.errorMessage().get().startsWith("SSH: "),
                "the refusal should say which node refused: " + result.errorMessage().get());
        }

        @Test
        @DisplayName("refuses an out-of-range port")
        void refusesOutOfRangePort() {
            NodeExecutionResult result = run("ssh.example.com", 0);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("port must be between 1 and 65535"),
                result.errorMessage().orElse(""));
        }

        @Test
        @DisplayName("an allow-listed private range is reachable again, so a self-hoster is not broken")
        void allowListedRangeIsReachable() {
            System.setProperty(UrlSafetyValidator.PRIVATE_EGRESS_ALLOW_LIST_PROPERTY, "10.0.0.0/8");

            // Asserted on the guard itself, POSITIVELY. The previous version of this test ran the
            // node against 10.0.9.5:22 with a 200ms timeout and asserted only that the message did
            // NOT say "not allowed" - a negative that any unrelated failure also satisfies, paid
            // for with the one real TCP connect in this suite. Nothing here touches the network.
            assertDoesNotThrow(
                () -> UrlSafetyValidator.assertOutboundHostSafe("10.0.9.5", 22),
                "the allow-list must reopen the range a self-hosted install needs");
        }

        @Test
        @DisplayName("without the allow-list the SSH node itself refuses that same range")
        void withoutTheAllowListTheNodeRefuses() {
            // The other half of the pair: the node really does consult the guard, so the two
            // assertions together cover "allow-listed passes" and "node enforces" with no socket.
            assertRefused(run("10.0.9.5", 22));
        }
    }

    @Nested
    @DisplayName("SFTP")
    class Sftp {

        private NodeExecutionResult run(String host, int port) {
            Core.SftpConfig config = new Core.SftpConfig(
                host, port, "user", "password", "pass", null, "list", "/", null, null, null, null);
            return new SftpNode("core:sftp", config).execute(context);
        }

        @ParameterizedTest
        @ValueSource(strings = {"169.254.169.254", "10.0.9.5", "127.0.0.1", "fd00::1", "metadata.google.internal"})
        @DisplayName("refuses an internal host before opening a session")
        void refusesInternalHost(String host) {
            NodeExecutionResult result = run(host, 22);

            assertRefused(result);
            assertTrue(result.errorMessage().get().startsWith("SFTP: "));
        }
    }

    @Nested
    @DisplayName("Database")
    class Database {

        private NodeExecutionResult run(String host, int port) {
            Core.DatabaseConfig config = new Core.DatabaseConfig(
                "postgresql", host, port, "postgres", "user", "pass", false,
                "SELECT 1", List.of(), "select", null, null);
            return new DatabaseNode("core:database", config).execute(context);
        }

        @ParameterizedTest
        @ValueSource(strings = {"169.254.169.254", "10.0.9.5", "127.0.0.1", "fd00::1", "metadata.google.internal"})
        @DisplayName("refuses an internal host before building the JDBC URL")
        void refusesInternalHost(String host) {
            NodeExecutionResult result = run(host, 5432);

            assertRefused(result);
            assertTrue(result.errorMessage().get().startsWith("Database: "));
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "x;serverName=10.0.0.5",
            "x?socketFactory=evil",
            "x&host=10.0.0.5",
            "db/../other",
            "db\\share",
            "db#frag"
        })
        @DisplayName("regression: a database name cannot smuggle a driver property past the guard")
        void databaseNameCannotCarryDriverProperties(String databaseName) {
            // The guard vets host and port, then the JDBC URL is assembled by concatenation. For
            // SQL Server that URL is "...;databaseName=" + name, so "x;serverName=10.0.0.5" appends
            // a driver property that redirects the connection to a host the guard never saw; '?'
            // and '&' do the same on the Postgres and MySQL URLs. Whoever sets the host also sets
            // the database name, so this is inside the guard's threat model.
            Core.DatabaseConfig config = new Core.DatabaseConfig(
                "mssql", "93.184.216.34", 1433, databaseName, "u", "p", false,
                "SELECT 1", List.of(), "select", null, null);
            NodeExecutionResult result = new DatabaseNode("core:database", config).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("'databaseName' must not contain"),
                result.errorMessage().orElse(""));
        }

        /**
         * The JDBC URL this node would hand a driver, captured before any socket is opened.
         *
         * <p>Capturing rather than connecting is what lets these tests state what the PRE-FIX code
         * did: with no host guard the node built the URL and only a live database would have shown
         * what was in it.
         */
        private String capturedJdbcUrlFor(String host) {
            AtomicReference<String> url = new AtomicReference<>();
            Core.DatabaseConfig config = new Core.DatabaseConfig(
                "postgresql", host, 5432, "appdb", "u", "p", false,
                "SELECT 1", List.of(), "select", null, null);
            new DatabaseNode("core:database", config) {
                @Override
                java.sql.Connection openConnection(String jdbcUrl, String username, String password) {
                    url.set(jdbcUrl);
                    throw new IllegalStateException("stop before the socket");
                }
            }.execute(context);
            return url.get();
        }

        @Test
        @DisplayName("LC-073: a '%' in the host smuggles a driver property past the guard, so it is refused")
        void hostCannotCarryDriverPropertiesBehindAZoneSeparator() {
            // THE PRE-FIX BYPASS, exactly. The shared guard normalises a host by discarding
            // everything from the IPv6 zone separator onwards, so it resolved and judged
            // "93.184.216.34" - a public literal, which passes - while this node concatenated the
            // ORIGINAL string into the URL authority. The driver therefore read a socketFactory
            // property the guard never saw, which is the one property the address pin depends on.
            // Nothing filtered it: '?' alone fails to resolve, '%' does not.
            String host = "93.184.216.34%?socketFactory=evil&socketFactoryArg=10.0.9.5";

            NodeExecutionResult result = new DatabaseNode("core:database", new Core.DatabaseConfig(
                "postgresql", host, 5432, "appdb", "u", "p", false,
                "SELECT 1", List.of(), "select", null, null)).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("'host' must not contain '%'"),
                result.errorMessage().orElse(""));
            assertNull(capturedJdbcUrlFor(host),
                "the refusal must happen before any URL reaches a driver");
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "93.184.216.34%?socketFactory=evil",
            "93.184.216.34%;serverName=10.0.0.5",
            "93.184.216.34?socketFactory=evil",
            "93.184.216.34&host=10.0.0.5",
            "93.184.216.34;serverName=10.0.0.5",
            "93.184.216.34/../other",
            "93.184.216.34#frag",
            "u@93.184.216.34",
            "93.184.216.34,10.0.9.5"
        })
        @DisplayName("LC-073: every character that re-delimits the URL is refused in the host, not only in the database name")
        void hostIsHeldToTheSameRuleAsTheDatabaseName(String host) {
            NodeExecutionResult result = new DatabaseNode("core:database", new Core.DatabaseConfig(
                "postgresql", host, 5432, "appdb", "u", "p", false,
                "SELECT 1", List.of(), "select", null, null)).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("'host' must not contain"),
                result.errorMessage().orElse(""));
        }

        @Test
        @DisplayName("LC-073: the shared guard itself refuses a '%' host, so every connector is covered, not only this node")
        void theSharedGuardRefusesAZoneSeparatorForEveryConnector() {
            // The node-local check above is the second barrier. This is the first, and it is the
            // one that covers SSH, SFTP, SMTP and IMAP too: any future caller that keeps the
            // configured string instead of the vetted address inherits the same truncation.
            IllegalArgumentException refused = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> UrlSafetyValidator.resolveOutboundHostSafe("93.184.216.34%?socketFactory=evil", 5432));

            assertTrue(refused.getMessage().contains("must not contain '%'"), refused.getMessage());
        }

        @Test
        @DisplayName("LC-073: an ordinary host, including a bracketed IPv6 literal, still connects")
        void ordinaryHostsStillWork() {
            // Anti-outage half. Over-rejecting here would break every Database node, and the
            // bracketed IPv6 form is a shape this node is actually configured with.
            assertNull(DatabaseNode.firstJdbcHostMetacharacter("db.example.com"));
            assertNull(DatabaseNode.firstJdbcHostMetacharacter("93.184.216.34"));
            assertNull(DatabaseNode.firstJdbcHostMetacharacter("[2606:2800:220:1::248:1893]"));
            assertNull(DatabaseNode.firstJdbcHostMetacharacter("my-db_replica-2.eu-west-1.rds.example"));
            assertNull(DatabaseNode.firstJdbcHostMetacharacter(null));
            assertEquals("'%'", DatabaseNode.firstJdbcHostMetacharacter("a%b"));
            assertEquals("'@'", DatabaseNode.firstJdbcHostMetacharacter("u@h"));
            // CASA readiness round 3: a comma-separated host list is how pgjdbc and Connector/J
            // read MULTIPLE failover targets, so "vetted,10.0.9.5" would smuggle a second,
            // never-checked host into the authority the guard just approved.
            assertEquals("','", DatabaseNode.firstJdbcHostMetacharacter("93.184.216.34,10.0.9.5"));

            assertTrue(capturedJdbcUrlFor("93.184.216.34").startsWith("jdbc:postgresql://93.184.216.34:5432/appdb"),
                "a plain public literal must still produce a URL");
        }

        @Test
        @DisplayName("an ordinary database name is untouched, including a space and an '=' ")
        void ordinaryDatabaseNamesStillWork() {
            // Over-rejecting turns a hardening fix into an outage for whoever already runs a
            // database with a space in its name, so only URL-structural characters are refused.
            assertNull(DatabaseNode.firstJdbcMetacharacter("my database"));
            assertNull(DatabaseNode.firstJdbcMetacharacter("reporting_db-2026"));
            assertNull(DatabaseNode.firstJdbcMetacharacter("weird=name"));
            assertNull(DatabaseNode.firstJdbcMetacharacter(null));
            assertEquals("';'", DatabaseNode.firstJdbcMetacharacter("a;b"));
        }

        @Test
        @DisplayName("the refusal still carries the resolved parameters for the inspector")
        void refusalKeepsResolvedParams() {
            NodeExecutionResult result = run("10.0.9.5", 5432);

            assertRefused(result);
            Object resolved = result.output().get("resolved_params");
            assertTrue(resolved instanceof Map, "resolved_params should survive the refusal");
            assertTrue(((Map<?, ?>) resolved).containsKey("host"));
            assertFalse(((Map<?, ?>) resolved).containsKey("password"),
                "the refusal path must not start leaking the connection password");
        }
    }

    @Nested
    @DisplayName("SMTP (send email)")
    class Smtp {

        private NodeExecutionResult run(String host, int port) {
            when(mockCredentialClient.getDefaultCredential(anyString(), eq("smtp")))
                .thenReturn(Optional.of(credential("smtp", host, port)));

            Core.SendEmailConfig config = new Core.SendEmailConfig(
                null, 587, null, null, true, null, null, "to@example.com",
                null, null, "Subject", "Body", false, null, null, null, null);
            SendEmailNode node = new SendEmailNode("core:send_email", config);
            node.acceptServices(mockServiceRegistry);
            return node.execute(context);
        }

        @ParameterizedTest
        @ValueSource(strings = {"169.254.169.254", "10.0.9.5", "127.0.0.1", "fd00::1", "metadata.google.internal"})
        @DisplayName("refuses an internal relay before building the mail session")
        void refusesInternalRelay(String host) {
            assertRefused(run(host, 25));
        }
    }

    @Nested
    @DisplayName("IMAP (email inbox)")
    class Imap {

        private NodeExecutionResult run(String host, int port) {
            when(mockCredentialClient.getDefaultCredential(anyString(), eq("imap")))
                .thenReturn(Optional.of(credential("imap", host, port)));

            Core.EmailInboxConfig config = new Core.EmailInboxConfig(
                null, "INBOX", false, 10, false, 0, "none", null, null, null, null, null,
                false, 0, false, false);
            EmailInboxNode node = new EmailInboxNode("core:email_inbox", config);
            node.acceptServices(mockServiceRegistry);
            return node.execute(context);
        }

        @ParameterizedTest
        @ValueSource(strings = {"169.254.169.254", "10.0.9.5", "127.0.0.1", "fd00::1", "metadata.google.internal"})
        @DisplayName("refuses an internal mailbox host before opening the store")
        void refusesInternalMailboxHost(String host) {
            assertRefused(run(host, 993));
        }
    }

    /**
     * The check-to-connect window (LC-073). {@link UrlSafetyValidator#assertOutboundHostSafe}
     * resolves the name, judges the answer and discards it; if the connector then hands the NAME to
     * its own client, that client resolves independently and whoever controls DNS for the
     * configured host answers public to the guard and private to the client.
     *
     * <p>There are two ways to close it and the connectors need both. Where nothing verifies an
     * identity against the name (SSH, SFTP) the vetted ADDRESS simply replaces it. Where TLS does
     * (Database, SMTP, IMAP) replacing the name would break certificate verification and SNI, so
     * the name stays and only the socket's destination is pinned, through
     * {@link UrlSafetyValidator#pinnedSocketFactory}. An earlier revision of this suite asserted
     * that the TLS connectors keep the name and left it there, which recorded the residual instead
     * of closing it.
     *
     * <p>Each test drives a real {@code execute()} with a PUBLIC IPv6 literal and intercepts the
     * connector at its single dial point, so nothing opens a socket. The literal is written in the
     * bracketed URL form, which is what makes the assertion discriminating: the canonical form the
     * guard produces is textually different, so "kept the name" and "pinned the address" can never
     * be confused for one another.
     */
    @Nested
    @DisplayName("The vetted address is what gets dialled, not the configured name")
    class VettedAddressIsDialled {

        /** A globally routable IPv6 address, so the guard permits it, in bracketed URL form. */
        private static final String PUBLIC_V6_BRACKETED = "[2606:2800:220:1::248:1893]";

        private String canonicalPublicV6() throws Exception {
            return InetAddress.getByName("2606:2800:220:1::248:1893").getHostAddress();
        }

        /** Asserts that {@code props} carry a socket factory pinned to the vetted address. */
        private void assertPinnedTo(java.util.Properties props, String prefix, String expectedAddress) {
            Object factory = props.get(prefix + ".socketFactory");
            assertTrue(factory instanceof UrlSafetyValidator.PinnedAddressSocketFactory,
                "Jakarta Mail only honours a SocketFactory INSTANCE here, got: " + factory);
            assertEquals(expectedAddress,
                ((UrlSafetyValidator.PinnedAddressSocketFactory) factory)
                    .pinnedAddress().getHostAddress(),
                "the socket must be pinned to the address the guard vetted");
            assertEquals("false", props.get(prefix + ".socketFactory.fallback"),
                "left at its default of true, a factory failure makes Jakarta Mail retry with no"
                    + " factory, resolving the name again: that retry is the window");
        }

        @Test
        @DisplayName("SSH hands JSch the vetted address")
        void sshDialsTheVettedAddress() throws Exception {
            AtomicReference<String> dialled = new AtomicReference<>();
            Core.SshConfig config = new Core.SshConfig(
                PUBLIC_V6_BRACKETED, 22, "user", "password", "pass", null, "id", 1000, null);
            SshNode node = new SshNode("core:ssh", config) {
                @Override
                Session openSession(JSch jsch, String username, String connectHost, int port) {
                    dialled.set(connectHost);
                    throw new IllegalStateException("stop before the socket");
                }
            };

            node.execute(context);

            assertEquals(canonicalPublicV6(), dialled.get(),
                "JSch must be given the address the guard vetted, not the configured string");
            assertNotEquals(PUBLIC_V6_BRACKETED, dialled.get());
        }

        @Test
        @DisplayName("SFTP hands JSch the vetted address")
        void sftpDialsTheVettedAddress() throws Exception {
            AtomicReference<String> dialled = new AtomicReference<>();
            Core.SftpConfig config = new Core.SftpConfig(
                PUBLIC_V6_BRACKETED, 22, "user", "password", "pass", null, "list", "/",
                null, null, null, null);
            SftpNode node = new SftpNode("core:sftp", config) {
                @Override
                Session openSession(JSch jsch, String username, String connectHost, int port) {
                    dialled.set(connectHost);
                    throw new IllegalStateException("stop before the socket");
                }
            };

            node.execute(context);

            assertEquals(canonicalPublicV6(), dialled.get());
            assertNotEquals(PUBLIC_V6_BRACKETED, dialled.get());
        }

        /** The URL fragment pgjdbc reads to build the socket, spelled the way the node emits it. */
        private String pinnedJdbcParams(String vettedAddress) {
            return "socketFactory=" + UrlSafetyValidator.PinnedAddressSocketFactory.class.getName()
                + "&socketFactoryArg=" + vettedAddress;
        }

        private String jdbcUrlFor(String dbType, String host, boolean ssl) {
            AtomicReference<String> url = new AtomicReference<>();
            Core.DatabaseConfig config = new Core.DatabaseConfig(
                dbType, host, 5432, "appdb", "u", "p", ssl,
                "SELECT 1", List.of(), "select", null, null);
            DatabaseNode node = new DatabaseNode("core:database", config) {
                @Override
                java.sql.Connection openConnection(String jdbcUrl, String username, String password) {
                    url.set(jdbcUrl);
                    throw new IllegalStateException("stop before the socket");
                }
            };
            node.execute(context);
            return url.get();
        }

        @Test
        @DisplayName("a cleartext JDBC connection keeps the host and pins the socket to the vetted address")
        void databaseWithoutTlsPinsTheSocket() throws Exception {
            assertEquals(
                "jdbc:postgresql://" + PUBLIC_V6_BRACKETED + ":5432/appdb?"
                    + pinnedJdbcParams(canonicalPublicV6()),
                jdbcUrlFor("postgresql", PUBLIC_V6_BRACKETED, false),
                "the driver is given the vetted address to dial through the socket factory, so the"
                    + " authority no longer has to be rewritten");
        }

        @Test
        @DisplayName("a TLS JDBC connection ALSO pins, without giving up certificate identity")
        void databaseWithTlsPinsTheSocketAndKeepsTheHostname() throws Exception {
            // This is the case the earlier revision left open. pgjdbc takes a socket factory by
            // class name plus one String argument, creates the socket from it, and only then
            // upgrades that socket to TLS against the host in the URL. So the name still drives
            // certificate verification and SNI (which managed providers route on) while the socket
            // goes where the guard said. Both halves are asserted here: the authority is unchanged
            // AND the pinned address is present.
            String url = jdbcUrlFor("postgresql", PUBLIC_V6_BRACKETED, true);

            assertEquals(
                "jdbc:postgresql://" + PUBLIC_V6_BRACKETED + ":5432/appdb?ssl=true&sslmode=require&"
                    + pinnedJdbcParams(canonicalPublicV6()),
                url);
            assertTrue(url.contains("//" + PUBLIC_V6_BRACKETED + ":5432/"),
                "the configured name must still be the URL authority: TLS identity depends on it");
        }

        @Test
        @DisplayName("MySQL and SQL Server keep the older shape, which is the honest residual")
        void otherDriversNeverConnectUnpinned() throws Exception {
            // Neither driver supports the shared pinning socket factory (Connector/J needs its own
            // vendor SocketFactory interface; mssql-jdbc is not a dependency here). Without TLS the
            // vetted literal replaces the host, which pins it. With TLS the only alternative was to
            // keep the NAME and let the driver resolve it again: that branch is now REFUSED.
            assertEquals(
                "jdbc:mysql://[" + canonicalPublicV6() + "]:5432/appdb",
                jdbcUrlFor("mysql", PUBLIC_V6_BRACKETED, false),
                "with no TLS the vetted literal still replaces the host, which pins it");
            assertNull(jdbcUrlFor("mssql", PUBLIC_V6_BRACKETED, true),
                "regression: a TLS SQL Server connection used to keep the name and connect unpinned");
            assertNull(jdbcUrlFor("mysql", PUBLIC_V6_BRACKETED, true));
            assertThrows(IllegalArgumentException.class, () -> DatabaseNode.buildPinnedJdbcUrl(
                "mssql", "db.example.com", 1433, "appdb", true, java.net.InetAddress.getByName("93.184.216.34")));
            assertThrows(IllegalStateException.class, () -> DatabaseNode.assertAuthorityIsVettedLiteral(
                "jdbc:mysql://db.example.com:3306/appdb", java.net.InetAddress.getByName("93.184.216.34")));
        }

        private java.util.Properties smtpSessionProperties(String host, int port, boolean useTls) {
            AtomicReference<java.util.Properties> captured = new AtomicReference<>();
            Map<String, Object> data = new HashMap<>();
            data.put("host", host);
            data.put("port", port);
            data.put("use_tls", String.valueOf(useTls));
            data.put("from_email", "from@example.com");
            CredentialSummaryDto dto = new CredentialSummaryDto();
            dto.setId(1L);
            dto.setName("smtp");
            dto.setIntegration("smtp");
            dto.setStatus("active");
            dto.setDefault(true);
            dto.setCredentialData(data);
            when(mockCredentialClient.getDefaultCredential(anyString(), eq("smtp")))
                .thenReturn(Optional.of(dto));

            SendEmailNode node = new SendEmailNode("core:send_email", new Core.SendEmailConfig(
                null, 587, null, null, useTls, null, null, "to@example.com", null, null,
                "S", "B", false, null, null, null, null)) {
                @Override
                void sendMessage(jakarta.mail.Session session, jakarta.mail.internet.MimeMessage message) {
                    captured.set(session.getProperties());
                    throw new IllegalStateException("stop before the socket");
                }
            };
            node.acceptServices(mockServiceRegistry);
            node.execute(context);
            return captured.get();
        }

        @Test
        @DisplayName("a cleartext SMTP send pins the socket to the vetted address")
        void smtpWithoutTlsPinsTheSocket() throws Exception {
            // Port 2525 is neither 587 nor 465 and use_tls is false, the only shape with no TLS.
            java.util.Properties props = smtpSessionProperties(PUBLIC_V6_BRACKETED, 2525, false);

            assertEquals(PUBLIC_V6_BRACKETED, props.get("mail.smtp.host"),
                "the relay name is no longer rewritten, on either branch");
            assertPinnedTo(props, "mail.smtp", canonicalPublicV6());
        }

        @Test
        @DisplayName("a TLS SMTP send pins the socket AND keeps the hostname for the certificate")
        void smtpWithTlsPinsTheSocketAndKeepsTheHostname() throws Exception {
            // The case the earlier revision recorded as a residual. Jakarta Mail connects the
            // socket the factory produced and then layers TLS on it with the configured host name,
            // so identity verification and SNI are untouched by the pin.
            java.util.Properties props = smtpSessionProperties(PUBLIC_V6_BRACKETED, 587, true);

            assertEquals(PUBLIC_V6_BRACKETED, props.get("mail.smtp.host"),
                "JavaMail verifies the server certificate against this value");
            assertEquals("true", props.get("mail.smtp.starttls.enable"),
                "the assertion is only meaningful on a branch that really negotiates TLS");
            assertPinnedTo(props, "mail.smtp", canonicalPublicV6());
        }

        @Test
        @DisplayName("an IMAPS mailbox pins the socket AND keeps the hostname for the certificate")
        void imapOverSslPinsTheSocketAndKeepsTheHostname() throws Exception {
            // Email Inbox was the connector with no cleartext branch at all, so before this it was
            // the one where nothing could be pinned. Both of its shapes negotiate TLS, and both
            // now go through the factory.
            AtomicReference<java.util.Properties> captured = new AtomicReference<>();
            when(mockCredentialClient.getDefaultCredential(anyString(), eq("imap")))
                .thenReturn(Optional.of(credential("imap", PUBLIC_V6_BRACKETED, 993)));

            EmailInboxNode node = new EmailInboxNode("core:email_inbox", new Core.EmailInboxConfig(
                null, "INBOX", false, 10, false, 0, "none", null, null, null, null, null,
                false, 0, false, false)) {
                @Override
                jakarta.mail.Store openStore(jakarta.mail.Session session, String protocol) {
                    captured.set(session.getProperties());
                    throw new IllegalStateException("stop before the socket");
                }
            };
            node.acceptServices(mockServiceRegistry);

            node.execute(context);

            java.util.Properties props = captured.get();
            assertEquals(PUBLIC_V6_BRACKETED, props.get("mail.imaps.host"),
                "the mailbox name still drives certificate identity and SNI");
            assertEquals("true", props.get("mail.imaps.ssl.enable"));
            assertPinnedTo(props, "mail.imaps", canonicalPublicV6());
        }

        @Test
        @DisplayName("the plain-IMAP STARTTLS branch is pinned under its own property prefix")
        void imapWithStarttlsPinsTheSocket() throws Exception {
            // The sibling branch of the one above, and the one a prefix mistake would silently
            // skip: the pinning properties are keyed by protocol ("mail.imap" here, "mail.imaps"
            // there), so a pin published under the wrong prefix is a property Jakarta Mail never
            // reads and a mailbox that is not pinned at all. Reached by a credential that opts out
            // of implicit SSL, which is the only difference from the test above.
            AtomicReference<java.util.Properties> captured = new AtomicReference<>();
            CredentialSummaryDto cleartextCredential = credential("imap", PUBLIC_V6_BRACKETED, 143);
            cleartextCredential.getCredentialData().put("use_ssl", "false");
            when(mockCredentialClient.getDefaultCredential(anyString(), eq("imap")))
                .thenReturn(Optional.of(cleartextCredential));

            EmailInboxNode node = new EmailInboxNode("core:email_inbox", new Core.EmailInboxConfig(
                null, "INBOX", false, 10, false, 0, "none", null, null, null, null, null,
                false, 0, false, false)) {
                @Override
                jakarta.mail.Store openStore(jakarta.mail.Session session, String protocol) {
                    captured.set(session.getProperties());
                    throw new IllegalStateException("stop before the socket");
                }
            };
            node.acceptServices(mockServiceRegistry);

            node.execute(context);

            java.util.Properties props = captured.get();
            assertEquals(PUBLIC_V6_BRACKETED, props.get("mail.imap.host"));
            assertEquals("true", props.get("mail.imap.starttls.enable"),
                "this branch still negotiates TLS, so it still has a name to protect");
            assertPinnedTo(props, "mail.imap", canonicalPublicV6());
            assertEquals(null, props.get("mail.imaps.socketFactory"),
                "and it must not have been published under the implicit-SSL prefix instead");
        }
    }

    @Test
    @DisplayName("all five connectors share ONE guard, so a new range is covered everywhere at once")
    void allFiveConnectorsRefuseTheSameNewRange() {
        // 100.64.0.1 is CGNAT: not RFC 1918, not loopback, and the range several managed
        // Kubernetes offerings hand to pods. It is in the shared table, so every connector
        // inherits it without a per-node change.
        String cgnat = "100.64.0.1";

        assertRefused(new SshNode("core:ssh", new Core.SshConfig(
            cgnat, 22, "u", "password", "p", null, "id", null, null)).execute(context));
        assertRefused(new SftpNode("core:sftp", new Core.SftpConfig(
            cgnat, 22, "u", "password", "p", null, "list", "/", null, null, null, null)).execute(context));
        assertRefused(new DatabaseNode("core:database", new Core.DatabaseConfig(
            "postgresql", cgnat, 5432, "db", "u", "p", false, "SELECT 1", List.of(),
            "select", null, null)).execute(context));

        when(mockCredentialClient.getDefaultCredential(anyString(), eq("smtp")))
            .thenReturn(Optional.of(credential("smtp", cgnat, 25)));
        SendEmailNode smtp = new SendEmailNode("core:send_email", new Core.SendEmailConfig(
            null, 587, null, null, true, null, null, "to@example.com", null, null,
            "S", "B", false, null, null, null, null));
        smtp.acceptServices(mockServiceRegistry);
        assertRefused(smtp.execute(context));

        when(mockCredentialClient.getDefaultCredential(anyString(), eq("imap")))
            .thenReturn(Optional.of(credential("imap", cgnat, 993)));
        EmailInboxNode imap = new EmailInboxNode("core:email_inbox", new Core.EmailInboxConfig(
            null, "INBOX", false, 10, false, 0, "none", null, null, null, null, null,
            false, 0, false, false));
        imap.acceptServices(mockServiceRegistry);
        assertRefused(imap.execute(context));

        // Referenced so the shared list stays the documented source of the cases above.
        assertTrue(INTERNAL_HOSTS.length == 5);
    }
}
