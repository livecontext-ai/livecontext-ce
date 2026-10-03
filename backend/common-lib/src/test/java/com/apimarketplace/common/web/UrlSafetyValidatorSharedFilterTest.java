package com.apimarketplace.common.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LC-074 / LC-075 regression suite.
 *
 * <p>LC-074: the Java address filter was weaker than this project's two other outbound guards
 * ({@code crawl_filter.py}, {@code ssrfGuard.ts}). Every range below is one the other two already
 * covered and this one did not, so each test here fails against the pre-fix filter.
 *
 * <p>LC-075: {@link UrlSafetyValidator#assertOutboundHostSafe} did not exist, so the SSH, SFTP,
 * Database, SMTP and IMAP connectors reached an attacker-chosen host and port unchecked.
 *
 * <p>No test in this class needs the network: every literal is parsed by
 * {@link InetAddress#getByName} without a lookup, and the two hostname cases are refused BEFORE
 * resolution or use the injectable resolver.
 */
@DisplayName("UrlSafetyValidator - shared SSRF filter (LC-074, LC-075)")
class UrlSafetyValidatorSharedFilterTest {

    @AfterEach
    void resetState() {
        UrlSafetyValidator.resetDnsResolverForTests();
        UrlSafetyValidator.resetEnvironmentReaderForTests();
        System.clearProperty(UrlSafetyValidator.PRIVATE_EGRESS_ALLOW_LIST_PROPERTY);
        System.clearProperty(UrlSafetyValidator.PRIVATE_EGRESS_HOST_SUFFIX_PROPERTY);
    }

    /** Points every hostname at a public address so name-based rules can be tested in isolation. */
    private void resolveEverythingToPublicAddress() {
        UrlSafetyValidator.setDnsResolverForTests(
            host -> new InetAddress[] {InetAddress.getByName("93.184.216.34")});
    }

    @Nested
    @DisplayName("Address ranges the Python and TypeScript guards covered and Java did not")
    class MissingRanges {

        @ParameterizedTest
        @CsvSource({
            "http://[fd00::1]:8083/,                 IPv6 unique local fc00::/7",
            "http://[fdff:ffff::1]/,                 IPv6 unique local upper half",
            "http://100.64.0.1/,                     CGNAT 100.64.0.0/10",
            "http://100.127.255.254/,                CGNAT upper edge",
            "http://0.1.2.3/,                        this-network 0.0.0.0/8",
            "http://192.0.0.1/,                      IETF protocol assignments 192.0.0.0/24",
            "http://198.19.255.254/,                 benchmarking 198.18.0.0/15",
            "http://255.255.255.255/,                limited broadcast",
            "http://[fec0::1]/,                      deprecated site-local fec0::/10",
            "http://[fe80::1]/,                      IPv6 link-local fe80::/10",
            "http://[::]/,                           IPv6 unspecified",
            "http://[::1]/,                          IPv6 loopback"
        })
        @DisplayName("validateUrl refuses every range the other two guards already refused")
        void validateUrlRefusesRangesTheOtherGuardsCover(String url, String range) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.validateUrl(url),
                "expected " + range + " to be refused");
            assertTrue(error.getMessage().contains("private/internal")
                    || error.getMessage().contains("internal hostnames"),
                "unexpected rejection reason for " + range + ": " + error.getMessage());
        }

        @ParameterizedTest
        @CsvSource({
            "http://[::ffff:169.254.169.254]/,       v4-mapped IMDS",
            "http://[::ffff:10.0.0.1]/,              v4-mapped RFC1918",
            "http://[::a9fe:a9fe]/,                  v4-compatible IMDS (deprecated form)",
            "http://[2002:a9fe:a9fe::]/,             6to4 carrying 169.254.169.254",
            "http://[2002:a00:1::]/,                 6to4 carrying 10.0.0.1",
            "http://[64:ff9b::a00:1]/,               NAT64 carrying 10.0.0.1",
            "http://[64:ff9b::a9fe:a9fe]/,           NAT64 carrying 169.254.169.254"
        })
        @DisplayName("an IPv6 address that CARRIES a blocked IPv4 address is refused too")
        void validateUrlUnwrapsEmbeddedIpv4(String url, String form) {
            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.validateUrl(url),
                "expected " + form + " to be refused");
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "http://metadata.google.internal/computeMetadata/v1/",
            "http://metadata/latest/meta-data/",
            "http://metadata.aws.internal/",
            "http://metadata.azure.com/",
            "http://postgres.default.svc.cluster.local:5432/",
            "http://kubernetes.cluster.local/",
            "http://vault.internal/v1/secret",
            "http://printer.local/",
            "http://box.localdomain/"
        })
        @DisplayName("internal hostnames are refused BEFORE DNS, even when DNS answers publicly")
        void validateUrlRefusesInternalHostnamesBeforeDns(String url) {
            // Split-horizon DNS: the resolver hands back a perfectly public address, and the
            // pre-fix filter therefore let every one of these through.
            resolveEverythingToPublicAddress();

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.validateUrl(url));
            assertTrue(error.getMessage().contains("internal hostnames"),
                "expected a name-based rejection, got: " + error.getMessage());
        }

        @Test
        @DisplayName("an ordinary public URL is still allowed")
        void publicUrlStillAllowed() {
            resolveEverythingToPublicAddress();
            assertDoesNotThrow(() -> UrlSafetyValidator.validateUrl("https://api.example.com/v1/things"));
            assertDoesNotThrow(() -> UrlSafetyValidator.validateUrl("http://93.184.216.34/page"));
        }

        @Test
        @DisplayName("a public host that merely CONTAINS a blocked suffix is not refused")
        void suffixMatchIsLabelBounded() {
            resolveEverythingToPublicAddress();
            // "notlocal" ends with "local" as a string but not as a DNS label.
            assertDoesNotThrow(() -> UrlSafetyValidator.validateUrl("https://notlocal.example.com/"));
            assertDoesNotThrow(() -> UrlSafetyValidator.validateUrl("https://internal-docs.example.com/"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "http://metadata.google.internal./computeMetadata/v1/",
            "http://postgres.default.svc.cluster.local./",
            "http://vault.internal./v1/secret",
            "http://localhost./"
        })
        @DisplayName("a trailing dot does not walk a blocked name past the denylist")
        void trailingDotDoesNotDefeatTheSuffixList(String url) {
            // A trailing dot makes a name FULLY qualified. Every resolver treats
            // "metadata.google.internal." and "metadata.google.internal" as the same name, but
            // endsWith(".internal") is false for the first, so one extra character used to walk
            // straight through the whole hostname denylist, including the suffixes that are
            // deliberately not allow-listable, and reach the resolver. Found by the adversarial
            // audit of the LC-074 fix itself, not by the original report.
            resolveEverythingToPublicAddress();

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.validateUrl(url),
                "a fully qualified form of a blocked name must be refused exactly like the "
                    + "relative form: " + url);
            assertTrue(
                error.getMessage().contains("internal hostnames") || error.getMessage().contains("localhost"),
                "expected a name-based rejection, got: " + error.getMessage());
        }

        @Test
        @DisplayName("a doubled trailing dot is refused too, by the earlier hostname check")
        void doubledTrailingDotIsRefusedByParsing() {
            // Kept separate from the case above on purpose: ".." is not a valid authority, so the
            // URI parser rejects it before the suffix list is ever consulted, and the message is
            // different. Both outcomes are refusals, which is what matters, but asserting the
            // name-based message here would pin a reason the code does not give. The strip loops
            // anyway so that the suffix list stays correct if an earlier parse ever gets laxer.
            resolveEverythingToPublicAddress();

            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.validateUrl("http://printer.local../"));
        }

        @Test
        @DisplayName("a trailing dot on a legitimate public host is still allowed")
        void trailingDotOnAPublicHostIsFine() {
            // The strip must not turn every fully qualified public name into a refusal: a trailing
            // dot is legal and some clients emit it.
            resolveEverythingToPublicAddress();
            assertDoesNotThrow(() -> UrlSafetyValidator.validateUrl("https://api.example.com./v1/things"));
        }
    }

    @Nested
    @DisplayName("validateUrlFormat - the no-DNS half of the same table")
    class RegistrationTimeFormatCheck {

        @ParameterizedTest
        @ValueSource(strings = {
            "http://[fd00::1]:8083/",
            "http://100.64.0.1/",
            "http://169.254.169.254/latest/",
            "http://10.0.0.5/api",
            "http://metadata.google.internal/",
            "http://db.svc.cluster.local:5432/"
        })
        @DisplayName("an internal IP literal or hostname cannot even be REGISTERED")
        void registrationRefusesInternalTargets(String url) {
            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.validateUrlFormat(url));
        }

        @Test
        @DisplayName("a templated host is still accepted, since it cannot be resolved yet")
        void templatedHostStillAccepted() {
            assertDoesNotThrow(() -> UrlSafetyValidator.validateUrlFormat("https://{region}.amazonaws.com/v1"));
            assertDoesNotThrow(() -> UrlSafetyValidator.validateUrlFormat("https://{dc}.api.mailchimp.com/3.0"));
        }

        @Test
        @DisplayName("a public host is still accepted without any DNS lookup")
        void publicHostStillAccepted() {
            assertDoesNotThrow(() -> UrlSafetyValidator.validateUrlFormat("https://api.stripe.com/v1"));
            assertDoesNotThrow(() -> UrlSafetyValidator.validateUrlFormat("https://93.184.216.34/page"));
        }
    }

    @Nested
    @DisplayName("assertOutboundHostSafe - the non-URL connectors (LC-075)")
    class OutboundHostGuard {

        @ParameterizedTest
        @CsvSource({
            "127.0.0.1,        5432",
            "10.0.9.5,         5432",
            "172.20.0.3,       22",
            "192.168.1.10,     25",
            "100.64.0.1,       3306",
            "169.254.169.254,  80",
            "0.0.0.0,          22",
            "fd00::1,          5432",
            "[fd00::1],        5432",
            "fe80::1,          22"
        })
        @DisplayName("refuses a private or internal target by default")
        void refusesPrivateTargetsByDefault(String host, int port) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.assertOutboundHostSafe(host, port));
            assertTrue(error.getMessage().contains("not allowed"), error.getMessage());
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "metadata.google.internal",
            "metadata",
            "db.svc.cluster.local",
            "mail.internal",
            "nas.local",
            "localhost"
        })
        @DisplayName("refuses an internal hostname without resolving it")
        void refusesInternalHostnames(String host) {
            resolveEverythingToPublicAddress();
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.assertOutboundHostSafe(host, 22));
            assertTrue(error.getMessage().contains("internal hostnames"), error.getMessage());
        }

        @Test
        @DisplayName("allows a public target")
        void allowsPublicTarget() {
            resolveEverythingToPublicAddress();
            assertDoesNotThrow(() -> UrlSafetyValidator.assertOutboundHostSafe("db.example.com", 5432));
            assertDoesNotThrow(() -> UrlSafetyValidator.assertOutboundHostSafe("93.184.216.34", 22));
        }

        @Test
        @DisplayName("a host that does not resolve is refused, not allowed through")
        void unresolvableHostIsRefused() {
            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.assertOutboundHostSafe("no-such-host.invalid", 22));
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1, 65536, 99999})
        @DisplayName("refuses an out-of-range port")
        void refusesOutOfRangePort(int port) {
            resolveEverythingToPublicAddress();
            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.assertOutboundHostSafe("db.example.com", port));
        }

        @Test
        @DisplayName("refuses a missing host")
        void refusesMissingHost() {
            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.assertOutboundHostSafe(null, 22));
            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.assertOutboundHostSafe("   ", 22));
        }
    }

    @Nested
    @DisplayName("assertOutboundHostSafe - the self-hosted opt-in allow-list")
    class PrivateEgressAllowList {

        private void allowList(String csv) {
            System.setProperty(UrlSafetyValidator.PRIVATE_EGRESS_ALLOW_LIST_PROPERTY, csv);
        }

        @Test
        @DisplayName("an allow-listed private range becomes reachable")
        void allowListedRangeIsReachable() {
            allowList("10.0.0.0/8,fc00::/7");
            assertDoesNotThrow(() -> UrlSafetyValidator.assertOutboundHostSafe("10.0.9.5", 5432));
            assertDoesNotThrow(() -> UrlSafetyValidator.assertOutboundHostSafe("fd00::1", 5432));
        }

        @Test
        @DisplayName("the allow-list is narrow: a range NOT listed stays refused")
        void nonListedRangeStaysRefused() {
            allowList("10.0.0.0/8");
            assertDoesNotThrow(() -> UrlSafetyValidator.assertOutboundHostSafe("10.0.9.5", 5432));
            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.assertOutboundHostSafe("192.168.1.10", 5432));
            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.assertOutboundHostSafe("172.20.0.3", 5432));
        }

        @ParameterizedTest
        @ValueSource(strings = {"169.254.169.254", "169.254.170.2", "0.0.0.0", "255.255.255.255", "fe80::1"})
        @DisplayName("no allow-list, not even 0.0.0.0/0, unlocks a metadata or unspecified address")
        void neverAllowListableRangesStayRefused(String host) {
            allowList("0.0.0.0/0,::/0");
            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.assertOutboundHostSafe(host, 80));
        }

        @Test
        @DisplayName("a metadata hostname stays refused whatever the allow-list says")
        void metadataHostnameStaysRefused() {
            allowList("0.0.0.0/0,::/0");
            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.assertOutboundHostSafe("metadata.google.internal", 80));
        }

        @Test
        @DisplayName("an unparseable allow-list entry is ignored, the valid ones still apply")
        void unparseableEntriesAreIgnored() {
            allowList("not-a-cidr, 10.0.0.0/99, ,10.0.0.0/8");
            assertDoesNotThrow(() -> UrlSafetyValidator.assertOutboundHostSafe("10.0.9.5", 5432));
            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.assertOutboundHostSafe("192.168.1.10", 5432));
        }

        @Test
        @DisplayName("clearing the allow-list restores the refusal without a restart")
        void clearingTheAllowListRestoresRefusal() {
            allowList("10.0.0.0/8");
            assertDoesNotThrow(() -> UrlSafetyValidator.assertOutboundHostSafe("10.0.9.5", 5432));

            System.clearProperty(UrlSafetyValidator.PRIVATE_EGRESS_ALLOW_LIST_PROPERTY);
            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.assertOutboundHostSafe("10.0.9.5", 5432));
        }

        @Test
        @DisplayName("the allow-list never widens validateUrl, which stays deny-all for private targets")
        void allowListDoesNotWidenValidateUrl() {
            allowList("10.0.0.0/8");
            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.validateUrl("http://10.0.9.5/admin"));
        }

        @Test
        @DisplayName("regression: a hostname in the allow-list is rejected, not resolved into a /32 rule")
        void hostnameEntriesAreRejectedWithoutResolving() {
            // Pre-fix parseCidrList called InetAddress.getByName on every entry, so a typo cost a
            // blocking DNS lookup and, worse, a NAME resolved successfully and became a silent /32
            // allow rule that followed that name's DNS from then on. The injected resolver would
            // answer this name; the parser must not consult it at all.
            UrlSafetyValidator.setDnsResolverForTests(
                host -> new InetAddress[] {InetAddress.getByName("10.0.9.5")});
            allowList("db.internal.example.com");

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.assertOutboundHostSafe("10.0.9.5", 5432));
            assertTrue(error.getMessage().contains("not allowed"), error.getMessage());
        }

        @Test
        @DisplayName("the refusal names the setting that would permit the target")
        void refusalNamesTheRemedy() {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.assertOutboundHostSafe("10.0.9.5", 5432));
            assertTrue(error.getMessage().contains(UrlSafetyValidator.PRIVATE_EGRESS_ALLOW_LIST_ENV),
                "an operator must be able to act on the refusal: " + error.getMessage());
        }
    }

    @Nested
    @DisplayName("assertOutboundHostSafe - the internal-hostname opt-in")
    class PrivateHostSuffixAllowList {

        @AfterEach
        void clearSuffixes() {
            System.clearProperty(UrlSafetyValidator.PRIVATE_EGRESS_HOST_SUFFIX_PROPERTY);
        }

        private void allowSuffixes(String csv) {
            System.setProperty(UrlSafetyValidator.PRIVATE_EGRESS_HOST_SUFFIX_PROPERTY, csv);
        }

        private void allowCidrs(String csv) {
            System.setProperty(UrlSafetyValidator.PRIVATE_EGRESS_ALLOW_LIST_PROPERTY, csv);
        }

        @ParameterizedTest
        @ValueSource(strings = {"nas.local", "mail.internal", "db.svc.cluster.local", "box.localdomain"})
        @DisplayName("regression: a generic private suffix is unlockable, it was refused with no escape hatch")
        void genericSuffixesAreUnlockable(String host) {
            // Pre-fix the whole blocked-suffix set was hard-wired, so a self-hosted SFTP target at
            // nas.local or an SMTP relay at mail.internal could not be reached by ANY configuration,
            // not even the CIDR allow-list. Both opt-ins are needed: the name AND the address.
            UrlSafetyValidator.setDnsResolverForTests(
                h -> new InetAddress[] {InetAddress.getByName("192.168.1.20")});
            allowSuffixes("local,internal,cluster.local,localdomain");
            allowCidrs("192.168.0.0/16");

            assertDoesNotThrow(() -> UrlSafetyValidator.assertOutboundHostSafe(host, 22));
        }

        @Test
        @DisplayName("unlocking the name does NOT unlock the address it resolves to")
        void suffixOptInDoesNotImplyAddressOptIn() {
            UrlSafetyValidator.setDnsResolverForTests(
                h -> new InetAddress[] {InetAddress.getByName("192.168.1.20")});
            allowSuffixes("local");

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.assertOutboundHostSafe("nas.local", 22));
            assertTrue(error.getMessage().contains("private/internal network addresses"),
                "the name passed, the address must still be judged: " + error.getMessage());
        }

        @Test
        @DisplayName("a name with an unlocked suffix on a PUBLIC address needs no CIDR opt-in")
        void unlockedSuffixOnPublicAddressIsEnough() {
            resolveEverythingToPublicAddress();
            allowSuffixes("internal");

            assertDoesNotThrow(() -> UrlSafetyValidator.assertOutboundHostSafe("mail.internal", 25));
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "metadata.google.internal", "metadata.aws.internal", "metadata.azure.com",
            "metadata", "localhost"
        })
        @DisplayName("no suffix opt-in unlocks a metadata hostname or localhost, not even naming it directly")
        void neverAllowListableNamesStayRefused(String host) {
            resolveEverythingToPublicAddress();
            // Both the broad suffix that would cover it AND the exact name, plus a wide-open CIDR
            // list, so nothing about this refusal depends on a narrow allow-list.
            allowSuffixes("internal,com,local,localhost,metadata,metadata.google.internal");
            allowCidrs("0.0.0.0/0,::/0");

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.assertOutboundHostSafe(host, 80));
            assertTrue(error.getMessage().contains("never be permitted"), error.getMessage());
        }

        @Test
        @DisplayName("the suffix refusal names the setting that would permit it")
        void suffixRefusalNamesTheRemedy() {
            resolveEverythingToPublicAddress();
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.assertOutboundHostSafe("nas.local", 22));
            assertTrue(error.getMessage().contains(UrlSafetyValidator.PRIVATE_EGRESS_HOST_SUFFIX_ENV),
                error.getMessage());
        }

        @Test
        @DisplayName("suffix matching stays label-bounded: notlocal.example.com was never blocked")
        void suffixMatchingIsLabelBounded() {
            resolveEverythingToPublicAddress();
            assertDoesNotThrow(() -> UrlSafetyValidator.assertOutboundHostSafe("notlocal.example.com", 443));
        }

        @Test
        @DisplayName("the opt-in never widens validateUrl, which stays deny-all for internal names")
        void suffixOptInDoesNotWidenValidateUrl() {
            resolveEverythingToPublicAddress();
            allowSuffixes("local,internal");

            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.validateUrl("http://nas.local/admin"));
            // Registration-time format checks DO honour the opt-in: a URL registered there is sent
            // through the same egress policy at execution (validateEgressUrl), so refusing it at
            // registration would make a self-hosted LAN custom API impossible to declare.
            assertDoesNotThrow(() -> UrlSafetyValidator.validateUrlFormat("http://mail.internal/admin"));
            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.validateUrlFormat("http://metadata.google.internal/admin"));
        }
    }

    @Nested
    @DisplayName("Where the egress settings are read from")
    class SettingSources {

        @AfterEach
        void clearSources() {
            System.clearProperty(UrlSafetyValidator.PRIVATE_EGRESS_ALLOW_LIST_PROPERTY);
            System.clearProperty(UrlSafetyValidator.PRIVATE_EGRESS_HOST_SUFFIX_PROPERTY);
            UrlSafetyValidator.resetEnvironmentReaderForTests();
        }

        private void environment(String cidrs, String suffixes) {
            UrlSafetyValidator.setEnvironmentReaderForTests(name -> {
                if (UrlSafetyValidator.PRIVATE_EGRESS_ALLOW_LIST_ENV.equals(name)) {
                    return cidrs;
                }
                if (UrlSafetyValidator.PRIVATE_EGRESS_HOST_SUFFIX_ENV.equals(name)) {
                    return suffixes;
                }
                return null;
            });
        }

        @Test
        @DisplayName("the environment variable alone permits a range: it is the only source a container has")
        void environmentVariableBranchWorks() {
            // The system-property branch was the only one under test, yet an operator running the
            // shipped Docker image can only set the environment variable.
            environment("10.0.0.0/8", null);

            assertDoesNotThrow(() -> UrlSafetyValidator.assertOutboundHostSafe("10.0.9.5", 5432));
            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.assertOutboundHostSafe("192.168.1.10", 5432));
        }

        @Test
        @DisplayName("the environment variable alone unlocks a hostname suffix")
        void environmentVariableUnlocksSuffix() {
            resolveEverythingToPublicAddress();
            environment(null, "internal");

            assertDoesNotThrow(() -> UrlSafetyValidator.assertOutboundHostSafe("mail.internal", 25));
        }

        @Test
        @DisplayName("the system property wins over the environment variable")
        void systemPropertyWinsOverEnvironment() {
            environment("10.0.0.0/8", null);
            System.setProperty(UrlSafetyValidator.PRIVATE_EGRESS_ALLOW_LIST_PROPERTY, "192.168.0.0/16");

            assertDoesNotThrow(() -> UrlSafetyValidator.assertOutboundHostSafe("192.168.1.10", 5432));
            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.assertOutboundHostSafe("10.0.9.5", 5432),
                "the property replaces the environment value, it does not add to it");
        }
    }

    @Nested
    @DisplayName("resolveOutboundHostSafe - the address a connector should dial")
    class VettedAddress {

        @Test
        @DisplayName("returns the address it vetted, so a caller need not resolve the name again")
        void returnsTheVettedAddress() throws Exception {
            InetAddress expected = InetAddress.getByName("93.184.216.34");
            UrlSafetyValidator.setDnsResolverForTests(host -> new InetAddress[] {expected});

            InetAddress vetted = UrlSafetyValidator.resolveOutboundHostSafe("db.example.com", 5432);

            assertEquals(expected, vetted);
            assertEquals("93.184.216.34", UrlSafetyValidator.toSocketHost(vetted));
        }

        @Test
        @DisplayName("every address a name resolves to must pass, not just the first")
        void allAddressesMustPass() throws Exception {
            UrlSafetyValidator.setDnsResolverForTests(host -> new InetAddress[] {
                InetAddress.getByName("93.184.216.34"),
                InetAddress.getByName("10.0.9.5")
            });

            assertThrows(IllegalArgumentException.class,
                () -> UrlSafetyValidator.resolveOutboundHostSafe("split.example.com", 5432));
        }

        @Test
        @DisplayName("an IPv6 literal is bracketed for a URL authority and bare for a socket host")
        void ipv6RenderingDependsOnTheTarget() throws Exception {
            // A JDBC URL built from an unbracketed IPv6 literal is unparseable
            // (jdbc:postgresql://2001:db8::1:5432/db), a JSch/JavaMail host string must NOT be
            // bracketed. One helper each, so no caller has to guess.
            InetAddress v6 = InetAddress.getByName("2001:db8::1");

            assertEquals("2001:db8:0:0:0:0:0:1", UrlSafetyValidator.toSocketHost(v6));
            assertEquals("[2001:db8:0:0:0:0:0:1]", UrlSafetyValidator.toUrlHost(v6));
            assertEquals("93.184.216.34",
                UrlSafetyValidator.toUrlHost(InetAddress.getByName("93.184.216.34")));
        }
    }
}
