package com.apimarketplace.common.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Validates URLs to prevent Server-Side Request Forgery (SSRF) attacks.
 *
 * Rejects:
 * - Every range in the shared CIDR tables below: RFC 1918 private, loopback, link-local
 *   (cloud metadata), CGNAT 100.64/10, "this" network 0.0.0.0/8, IETF assignments 192.0.0.0/24,
 *   benchmarking 198.18.0.0/15, broadcast, IPv6 unique-local fc00::/7, fe80::/10, fec0::/10,
 *   and any IPv6 form that CARRIES one of those IPv4 addresses (v4-mapped, v4-compatible,
 *   6to4, NAT64)
 * - Internal hostnames by name: localhost, metadata endpoints, cluster.local, .local, .internal
 * - Non-HTTP(S) schemes (ftp, file, etc.)
 *
 * This project has three outbound guards, one per language: this class, {@code ssrfGuard.ts}
 * (frontend proxy) and {@code crawl_filter.py} (websearch-service). Their tables must stay
 * identical in meaning; the Java one used to be the weakest of the three, which is what let a
 * bracketed {@code fd00::} address through (LC-074, security audit 2026-08-13). There is no
 * codegen spanning Java, Python and TypeScript in this repo, so the tables are kept aligned by
 * hand and each range has a test naming it.
 *
 * Template placeholders like {region} or {account} are substituted with a safe literal before
 * URI parsing, so a templated URL still parses. What happens next depends on WHERE the
 * placeholder is, and on which entry point you call:
 *
 * - {@link #validateUrl} is the CONNECT-time check. A placeholder left in the HOST makes it
 *   throw: nothing legitimate connects to a templated hostname, and returning success there
 *   used to be the hole (an unresolved authority passed the only SSRF gate, then got filled in
 *   from the caller's own credential data on the way to the request). A placeholder in the path
 *   or query is fine, since the authority is concrete and gets resolved and checked.
 * - {@link #validateUrlFormat} is format only, no DNS. For a URL that genuinely cannot be
 *   resolved yet.
 * - {@link #validateRegistrationUrl} picks between the two: resolving for a concrete URL,
 *   format-only for one whose host is templated. Registration-time callers want this.
 *
 * An earlier version of this comment promised that "the fully resolved URL is re-validated at
 * execution time". It was not, anywhere. Catalog-service now routes every outbound call through
 * one checked helper, which is what makes the claim true (LC-006 / LC-007, security audit
 * 2026-08-13).
 *
 * Used by HttpRequestNode, DownloadFileNode, RssNode, and catalog-service.
 */
public final class UrlSafetyValidator {

    private static final Logger logger = LoggerFactory.getLogger(UrlSafetyValidator.class);

    /**
     * One CIDR entry: the network address bytes plus a prefix length in bits.
     *
     * <p>These tables are the single definition of "internal" for the Java side. They are the
     * UNION of what this project's two other outbound guards already covered, because three
     * guards disagreeing about the word "internal" was the finding, not any single missing range
     * (LC-074, security audit 2026-08-13). Ranges that came only from the TypeScript guard
     * ({@code 0.0.0.0/8}, {@code 192.0.0.0/24}, {@code 198.18.0.0/15}, the broadcast address,
     * the embedded-IPv4 unwrapping below) and only from the Python guard (the internal hostname
     * suffixes) are now enforced here too.
     */
    private record Cidr(byte[] base, int prefixBits) {

        boolean contains(byte[] candidate) {
            if (candidate.length != base.length) {
                return false;
            }
            int fullBytes = prefixBits / 8;
            int remainingBits = prefixBits % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != base[i]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            return (candidate[fullBytes] & mask) == (base[fullBytes] & mask);
        }
    }

    /**
     * IPv4 ranges that must never be reached by an outbound request built from user input.
     * Mirrors {@code ssrfGuard.ts#isBlockedIpv4} and {@code crawl_filter.py#BLOCKED_NETWORKS}.
     */
    private static final List<Cidr> BLOCKED_V4 = List.of(
        cidr("0.0.0.0", 8),            // "this" network (0.0.0.0/8, not just the bare 0.0.0.0)
        cidr("10.0.0.0", 8),           // RFC 1918 private
        cidr("100.64.0.0", 10),        // RFC 6598 CGNAT / shared address space
        cidr("127.0.0.0", 8),          // loopback
        cidr("169.254.0.0", 16),       // link-local, incl. the 169.254.169.254 IMDS endpoint
        cidr("172.16.0.0", 12),        // RFC 1918 private
        cidr("192.0.0.0", 24),         // IETF protocol assignments
        cidr("192.168.0.0", 16),       // RFC 1918 private
        cidr("198.18.0.0", 15),        // RFC 2544 benchmarking
        cidr("224.0.0.0", 4),          // multicast
        cidr("255.255.255.255", 32));  // limited broadcast

    /**
     * IPv6 ranges that must never be reached. {@code fc00::/7} is the one that made a bracketed
     * {@code fd00::} address pass every "validated" call site: {@link InetAddress#isSiteLocalAddress()}
     * only matches the DEPRECATED {@code fec0::/10} for IPv6.
     */
    private static final List<Cidr> BLOCKED_V6 = List.of(
        cidr("::", 128),               // unspecified
        cidr("::1", 128),              // loopback
        cidr("fc00::", 7),             // unique local
        cidr("fe80::", 10),            // link-local
        cidr("fec0::", 10),            // deprecated site-local
        cidr("ff00::", 8));            // multicast

    /**
     * The subset that no configuration (explicit or edition default) may unlock, see
     * {@link #assertOutboundHostSafe}. Link-local carries the cloud metadata endpoint, loopback is
     * the process's own host (the app's actuator, the sidecars), and the unspecified, broadcast and
     * multicast addresses are never a legitimate connection target for anything.
     */
    private static final List<Cidr> NEVER_ALLOWLISTABLE_V4 = List.of(
        cidr("0.0.0.0", 8),
        cidr("127.0.0.0", 8),
        cidr("169.254.0.0", 16),
        cidr("224.0.0.0", 4),
        cidr("255.255.255.255", 32));

    private static final List<Cidr> NEVER_ALLOWLISTABLE_V6 = List.of(
        cidr("::", 128),
        cidr("::1", 128),
        cidr("fe80::", 10),
        cidr("ff00::", 8));

    /**
     * Hostnames and hostname suffixes refused BEFORE any DNS lookup. Mirrors
     * {@code crawl_filter.py#BLOCKED_HOSTNAME_SUFFIXES} and {@code ssrfGuard.ts#BLOCKED_HOSTNAMES}.
     * Checking the name as well as the address is what survives split-horizon DNS, where the same
     * name answers with a public address from outside the mesh and a private one from inside it.
     */
    private static final Set<String> BLOCKED_HOST_SUFFIXES = Set.of(
        "localhost",
        "metadata",
        "metadata.google.internal",
        "metadata.aws.internal",
        "metadata.azure.com",
        "cluster.local",
        "svc.cluster.local",
        "pod.cluster.local",
        "local",
        "internal",
        "localdomain");

    /**
     * The hostname subset that no configuration may unlock, the name-side counterpart of
     * {@link #NEVER_ALLOWLISTABLE_V4}. These name the cloud metadata endpoints and the pod's own
     * loopback; nothing a workflow legitimately connects to is called any of them, in any topology.
     *
     * <p>Everything ELSE in {@link #BLOCKED_HOST_SUFFIXES} ({@code .local}, {@code .internal},
     * {@code .localdomain}, {@code cluster.local} and friends) IS unlockable, through
     * {@link #PRIVATE_EGRESS_HOST_SUFFIX_PROPERTY}. Those are generic private-DNS suffixes:
     * {@code .local} is mDNS/Bonjour and {@code .internal} is the commonest private-DNS suffix, so
     * a self-hosted SFTP target at {@code nas.local} or an SMTP relay at {@code mail.internal} are
     * exactly the legitimate cases the opt-in exists to serve. Refusing them with no escape hatch
     * at all was the pre-fix behaviour, and a guard that refuses every real deployment gets turned
     * off wholesale, which is worse than the finding.
     *
     * <p>Unlocking {@code internal} does NOT unlock {@code metadata.google.internal}: this set is
     * consulted first and unconditionally.
     */
    private static final Set<String> NEVER_ALLOWLISTABLE_HOST_SUFFIXES = Set.of(
        "localhost",
        "metadata",
        "metadata.google.internal",
        "metadata.aws.internal",
        "metadata.azure.com");

    /**
     * Comma-separated CIDR list of private ranges that {@link #assertOutboundHostSafe} may reach.
     *
     * <p><b>How to set it.</b> This is read as a JVM system property first, then as the
     * {@code LIVECONTEXT_EGRESS_ALLOWED_PRIVATE_CIDRS} environment variable. It is deliberately NOT
     * a Spring property: this class is a static utility with no application context, so putting the
     * dotted name in a YAML configuration file has NO effect. Container deployments set the
     * environment variable on the app container. The shipped Docker Compose files and Helm chart
     * do NOT pass it through today: an operator who wants a non-default value adds it to the app
     * service's environment themselves (see the project docs).
     *
     * <p>Unset or blank means the EDITION default: nothing on a cloud edition,
     * {@link #SELF_HOSTED_DEFAULT_PRIVATE_CIDRS} on a self-hosted one. {@code none} means nothing.
     */
    public static final String PRIVATE_EGRESS_ALLOW_LIST_PROPERTY = "livecontext.egress.allowed-private-cidrs";

    /** Environment-variable form of {@link #PRIVATE_EGRESS_ALLOW_LIST_PROPERTY}. */
    public static final String PRIVATE_EGRESS_ALLOW_LIST_ENV = "LIVECONTEXT_EGRESS_ALLOWED_PRIVATE_CIDRS";

    /**
     * Comma-separated list of internal hostname suffixes that {@link #assertOutboundHostSafe} may
     * reach, for example {@code local,internal}. Same two sources and the same "no Spring binding"
     * caveat as {@link #PRIVATE_EGRESS_ALLOW_LIST_PROPERTY}.
     *
     * <p>It unlocks the NAME only. The address the name resolves to still has to be public or
     * covered by {@link #PRIVATE_EGRESS_ALLOW_LIST_PROPERTY}, so reaching {@code nas.local} on
     * {@code 192.168.1.20} needs both settings. Two independent opt-ins is the point: a permissive
     * private-DNS suffix must not become a way to reach any private address.
     */
    public static final String PRIVATE_EGRESS_HOST_SUFFIX_PROPERTY =
        "livecontext.egress.allowed-private-host-suffixes";

    /** Environment-variable form of {@link #PRIVATE_EGRESS_HOST_SUFFIX_PROPERTY}. */
    public static final String PRIVATE_EGRESS_HOST_SUFFIX_ENV =
        "LIVECONTEXT_EGRESS_ALLOWED_PRIVATE_HOST_SUFFIXES";

    /** Setting text plus the value parsed from it, swapped as one reference so they never diverge. */
    private record ParsedSetting<T>(String raw, T value) {}

    private static volatile ParsedSetting<List<Cidr>> cachedAllowList = new ParsedSetting<>(null, List.of());
    private static volatile ParsedSetting<Set<String>> cachedHostSuffixes = new ParsedSetting<>(null, Set.of());

    /** Seam for the environment-variable branch, which a test cannot set through the JDK. */
    private static volatile UnaryOperator<String> environmentReader = System::getenv;

    /**
     * Value of either egress setting that means "nothing", used by a self-hosted operator who wants
     * the cloud posture (refuse every private target) instead of the self-hosted default.
     */
    public static final String EGRESS_SETTING_NONE = "none";

    /**
     * Private ranges a SELF-HOSTED install (CE Free or Self-Hosted Enterprise) may reach when the
     * operator has not configured {@link #PRIVATE_EGRESS_ALLOW_LIST_ENV}. On a self-hosted install
     * the operator owns both ends: a database on {@code 10.0.0.5}, an SMTP relay on the LAN or a
     * service on the Docker network ({@code host.docker.internal}) is the normal case, and refusing
     * it would break every existing install on upgrade. Loopback, link-local (metadata) and the
     * other never-allow-listable ranges stay refused regardless.
     */
    static final String SELF_HOSTED_DEFAULT_PRIVATE_CIDRS =
        "10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,100.64.0.0/10,fc00::/7";

    /** Host-suffix counterpart of {@link #SELF_HOSTED_DEFAULT_PRIVATE_CIDRS}. */
    static final String SELF_HOSTED_DEFAULT_PRIVATE_HOST_SUFFIXES =
        "local,internal,localdomain,cluster.local";

    /**
     * Whether this JVM runs a self-hosted edition. Set once at startup by
     * {@link AppEditionAutoConfiguration} from {@link AppEditionProvider#isSelfHosted()}; false
     * (the cloud posture) until then, and in every unit test that does not set it.
     */
    private static volatile boolean selfHostedEdition = false;

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
        String substituted = PLACEHOLDER.matcher(url).replaceAll(PLACEHOLDER_SUBSTITUTE);
        // A template whose FIRST segment is a variable carries the scheme and host inside that
        // variable ("{instance_url}/api/v1"). Substituting a bare token leaves a string with no
        // scheme, which this validator then rejects, so that shape could not be declared at all.
        // Give the leading variable a scheme so the FORM can be checked here. Nothing is loosened:
        // the host is still a placeholder, which validateUrl refuses, so the real URL has to be
        // validated again once the variable is resolved.
        if (url.stripLeading().startsWith("{") && substituted.startsWith(PLACEHOLDER_SUBSTITUTE)) {
            substituted = "https://" + substituted;
        }
        return substituted;
    }

    private static boolean hasPlaceholder(String s) {
        return s != null && PLACEHOLDER.matcher(s).find();
    }

    /**
     * Validates that the given URL is safe to fetch. Strict: every private range and internal
     * hostname is refused, whatever the edition and the egress settings say.
     *
     * @param url the URL to validate
     * @throws IllegalArgumentException if the URL is unsafe or malformed
     */
    public static void validateUrl(String url) {
        validateUrl(url, false);
    }

    /**
     * Same check as {@link #validateUrl(String)}, except that the private-egress opt-ins
     * ({@link #PRIVATE_EGRESS_ALLOW_LIST_ENV}, {@link #PRIVATE_EGRESS_HOST_SUFFIX_ENV}, and the
     * self-hosted edition default) are honoured, exactly as {@link #assertOutboundHostSafe} honours
     * them. For outbound calls to a target a user CONFIGURED (a custom catalog API, a connector), where
     * a self-hosted install legitimately points at its own LAN. On the cloud edition with no setting
     * this is identical to {@link #validateUrl(String)}. Loopback, link-local (cloud metadata) and the
     * metadata/localhost names stay refused in every configuration.
     *
     * @return the vetted addresses the host resolved to (never empty)
     */
    public static InetAddress[] validateEgressUrl(String url) {
        return validateUrl(url, true);
    }

    private static InetAddress[] validateUrl(String url, boolean egressPolicy) {
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
        String lowerHost = normalizeHost(host);
        if ("localhost".equals(lowerHost) || lowerHost.endsWith(".localhost")) {
            throw new IllegalArgumentException(
                "Requests to localhost are not allowed");
        }

        // Reject every other internal hostname suffix BEFORE DNS. Same list the Python crawl
        // filter has always applied; the Java guard used to check only "localhost", so
        // metadata.google.internal, anything under cluster.local and any .internal / .local name
        // reached the resolver and passed whenever split-horizon DNS answered with a public
        // address (LC-074, security audit 2026-08-13).
        if (isBlockedHostname(lowerHost) && !(egressPolicy && isAllowListedPrivateHostName(lowerHost))) {
            throw new IllegalArgumentException(
                "Requests to internal hostnames are not allowed: " + host);
        }

        // A placeholder left in the AUTHORITY means this is not a resolvable target, and it
        // used to return successfully here "because the execution layer re-validates the fully
        // substituted URL". No such re-validation existed, so a caller could keep a placeholder
        // in the host, pass validation, and have the value substituted from its own credential
        // data on the way to the request (LC-007, security audit 2026-08-13).
        //
        // Refusing is safe: a templated hostname is never a legitimate thing to CONNECT to. The
        // caller that legitimately holds a template must validate again after substitution,
        // which is now enforced by routing every outbound call through one checked helper.
        // Placeholders in the path or query are untouched by this: only the host is inspected.
        if (lowerHost.contains(PLACEHOLDER_SUBSTITUTE)) {
            throw new IllegalArgumentException(
                "Host still contains an unresolved template placeholder; "
                    + "validate the substituted URL instead: " + host);
        }

        // Resolve hostname and check all IP addresses
        InetAddress[] addresses = resolveHostWithTimeout(lowerHost);
        if (addresses == null || addresses.length == 0) {
            throw new UnresolvableHostException("Cannot resolve hostname: " + host);
        }
        List<Cidr> allowList = egressPolicy ? privateEgressAllowList() : List.of();

        for (InetAddress addr : addresses) {
            if (isUnsafeAddress(addr, allowList)) {
                throw new IllegalArgumentException(
                    "Requests to private/internal network addresses are not allowed: " + host);
            }
        }
        return addresses;
    }

    /**
     * Registration-time check that is as strict as it can be for the URL it is given.
     *
     * <p>A catalog base URL may legitimately carry a placeholder in its HOST (169 shipped APIs
     * do: {@code {region}.amazonaws.com}, {@code {dc}.api.mailchimp.com}). Such a URL cannot be
     * resolved, so only the format can be checked. Every OTHER URL is concrete and gets the full
     * resolving check, which is what stops {@code http://169.254.169.254} being registered.
     *
     * <p>The private-egress opt-ins apply (see {@link #validateEgressUrl}), because a URL
     * registered here is later sent through that same policy at execution time.
     */
    public static void validateRegistrationUrl(String url) {
        if (url != null && hasPlaceholder(url)) {
            String host = hostOf(url);
            if (host != null && host.contains(PLACEHOLDER_SUBSTITUTE)) {
                validateUrlFormat(url);
                return;
            }
        }
        validateUrl(url, true);
    }

    /** @return the (placeholder-substituted) host, or null when the URL is unparseable. */
    private static String hostOf(String url) {
        try {
            String parsable = hasPlaceholder(url) ? substitutePlaceholders(url.trim()) : url.trim();
            return URI.create(parsable).getHost();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Validates URL format without DNS resolution: scheme (http/https), hostname presence, the
     * internal-hostname denylist, and IP literals against the address tables. Use this at
     * registration/save time for a URL that cannot be resolved yet. Full SSRF validation (with
     * DNS) must be done on the FINAL URL at execution time via {@link #validateUrl(String)}.
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
        String lowerHost = normalizeHost(host);
        if ("localhost".equals(lowerHost) || lowerHost.endsWith(".localhost")) {
            throw new IllegalArgumentException(
                "Requests to localhost are not allowed");
        }

        // The name-based half of the shared filter applies at registration time too: a URL that
        // can never be safe to fetch should not be storable in the first place. The egress
        // opt-ins apply, since a self-hosted install may register a service on its own LAN.
        if (isBlockedHostname(lowerHost) && !isAllowListedPrivateHostName(lowerHost)) {
            throw new IllegalArgumentException(
                "Requests to internal hostnames are not allowed: " + host);
        }

        // An IP literal needs no DNS, so the address half of the shared filter applies here as
        // well. Skipping it let http://[fd00::1]:8083 and http://100.64.0.1 be REGISTERED and
        // only fail later, if the execution path happened to re-validate at all.
        if (!lowerHost.contains(PLACEHOLDER_SUBSTITUTE)) {
            InetAddress literal = parseIpLiteral(lowerHost);
            if (literal != null && isUnsafeAddress(literal, privateEgressAllowList())) {
                throw new IllegalArgumentException(
                    "Requests to private/internal network addresses are not allowed: " + host);
            }
        }
    }

    /** True when an internal NAME is unlocked by the egress opt-in (never a metadata/localhost name). */
    private static boolean isAllowListedPrivateHostName(String lowerHost) {
        return !matchesSuffix(lowerHost, NEVER_ALLOWLISTABLE_HOST_SUFFIXES)
            && matchesSuffix(lowerHost, allowedPrivateHostSuffixes());
    }

    /**
     * @return the address when {@code host} is an IP literal, or null when it is a name. Never
     *         performs a DNS lookup: a name is returned as null rather than resolved.
     */
    private static InetAddress parseIpLiteral(String host) {
        boolean looksNumericV4 = host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        boolean looksV6 = host.indexOf(':') >= 0;
        if (!looksNumericV4 && !looksV6) {
            return null;
        }
        try {
            return InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /**
     * Checks whether an IP address belongs to a private, loopback, link-local, shared or
     * otherwise internal range, using the shared CIDR tables above. No allow-list applies here:
     * this is the predicate behind {@link #validateUrl}, where a private target is never
     * legitimate, and it is the predicate an outbound HTTP connector should re-apply to the
     * address it actually connected to.
     */
    public static boolean isUnsafeAddress(InetAddress address) {
        return isUnsafeAddress(address, List.of());
    }

    /**
     * {@link #isUnsafeAddress(InetAddress)} with the private-egress policy applied (explicit
     * setting or edition default). The connect-time predicate for user-configured targets.
     */
    public static boolean isUnsafeEgressAddress(InetAddress address) {
        return isUnsafeAddress(address, privateEgressAllowList());
    }

    /**
     * @param allowList private ranges the caller is permitted to reach. Never applies to
     *                  {@link #NEVER_ALLOWLISTABLE_V4} / {@link #NEVER_ALLOWLISTABLE_V6}.
     */
    private static boolean isUnsafeAddress(InetAddress address, List<Cidr> allowList) {
        byte[] bytes = unwrapEmbeddedIpv4(address.getAddress());
        boolean v4 = bytes.length == 4;

        if (matchesAny(bytes, v4 ? NEVER_ALLOWLISTABLE_V4 : NEVER_ALLOWLISTABLE_V6)) {
            return true;
        }

        boolean blocked = matchesAny(bytes, v4 ? BLOCKED_V4 : BLOCKED_V6)
            || address.isLoopbackAddress()
            || address.isSiteLocalAddress()
            || address.isLinkLocalAddress()
            || address.isAnyLocalAddress()
            || address.isMulticastAddress();

        if (!blocked) {
            return false;
        }
        return !matchesAny(bytes, allowList);
    }

    private static boolean matchesAny(byte[] addressBytes, List<Cidr> ranges) {
        for (Cidr range : ranges) {
            if (range.contains(addressBytes)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reduces an IPv6 address that CARRIES an IPv4 address to those four bytes, so the IPv4 table
     * applies to it. Covers {@code ::ffff:a.b.c.d} (v4-mapped), {@code ::a.b.c.d} (deprecated
     * v4-compatible), {@code 2002::/16} (6to4) and {@code 64:ff9b::/96} (NAT64). Without this,
     * {@code ::ffff:169.254.169.254} and {@code 2002:a9fe:a9fe::} reach the metadata endpoint
     * through a filter that only ever compared IPv6 prefixes.
     *
     * <p>{@code ::} and {@code ::1} stay in IPv6 form so the {@code ::/128} and {@code ::1/128}
     * table entries remain the rules that match them.
     */
    private static byte[] unwrapEmbeddedIpv4(byte[] bytes) {
        if (bytes.length != 16) {
            return bytes;
        }

        boolean firstTenZero = true;
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                firstTenZero = false;
                break;
            }
        }
        if (firstTenZero) {
            boolean mapped = (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF;
            boolean compatible = bytes[10] == 0 && bytes[11] == 0;
            if (mapped || compatible) {
                byte[] embedded = {bytes[12], bytes[13], bytes[14], bytes[15]};
                boolean unspecifiedOrLoopback = compatible
                    && embedded[0] == 0 && embedded[1] == 0 && embedded[2] == 0
                    && (embedded[3] == 0 || embedded[3] == 1);
                if (!unspecifiedOrLoopback) {
                    return embedded;
                }
                return bytes;
            }
        }

        // 2002::/16 - 6to4 carries the IPv4 address in bytes 2..5.
        if ((bytes[0] & 0xFF) == 0x20 && (bytes[1] & 0xFF) == 0x02) {
            return new byte[] {bytes[2], bytes[3], bytes[4], bytes[5]};
        }

        // 64:ff9b::/96 - NAT64 carries the IPv4 address in the low 32 bits.
        if ((bytes[0] & 0xFF) == 0x00 && (bytes[1] & 0xFF) == 0x64
            && (bytes[2] & 0xFF) == 0xFF && (bytes[3] & 0xFF) == 0x9B) {
            boolean middleZero = true;
            for (int i = 4; i < 12; i++) {
                if (bytes[i] != 0) {
                    middleZero = false;
                    break;
                }
            }
            if (middleZero) {
                return new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]};
            }
        }

        return bytes;
    }

    /**
     * True when the hostname itself is internal, before any DNS lookup.
     *
     * @param lowerHost a lowercased hostname with brackets and any zone id already stripped
     */
    static boolean isBlockedHostname(String lowerHost) {
        return matchesSuffix(lowerHost, BLOCKED_HOST_SUFFIXES);
    }

    /** Label-bounded suffix match: {@code notlocal.example.com} is not under {@code local}. */
    private static boolean matchesSuffix(String lowerHost, Set<String> suffixes) {
        if (lowerHost == null || lowerHost.isBlank() || suffixes.isEmpty()) {
            return false;
        }
        for (String suffix : suffixes) {
            if (lowerHost.equals(suffix) || lowerHost.endsWith("." + suffix)) {
                return true;
            }
        }
        return false;
    }

    /** Lowercases a host and strips IPv6 brackets plus any {@code %zone} suffix. */
    private static String normalizeHost(String host) {
        String cleaned = host.trim();
        if (cleaned.startsWith("[") && cleaned.endsWith("]") && cleaned.length() > 2) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        int zone = cleaned.indexOf('%');
        if (zone > 0) {
            cleaned = cleaned.substring(0, zone);
        }
        // Strip the root label. A trailing dot makes a name FULLY qualified, and every resolver
        // treats "metadata.google.internal." and "metadata.google.internal" as the same name, but
        // endsWith(".internal") is false for the first one. Without this, one extra character
        // walks straight through the entire hostname denylist, including the suffixes that are
        // deliberately not allow-listable, and reaches the resolver. Trailing dots are stripped in
        // a loop rather than once because ".." is not rejected earlier and a single strip would
        // leave a name that still evades the suffix test.
        while (cleaned.endsWith(".")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned.toLowerCase(Locale.ROOT);
    }

    /**
     * Connect-time guard for the outbound connectors that are NOT URL-shaped: the SSH, SFTP,
     * Database, SMTP and IMAP nodes each take a host and a port straight from workflow input or a
     * stored credential and hand them to a socket, with the egress NetworkPolicy already open on
     * exactly the data-plane ports they need (LC-075, security audit 2026-08-13).
     *
     * <p><b>Policy: refuse by default, opt in by CIDR.</b> Not a blanket refusal, and not an
     * unconditional allow.
     * <ul>
     *   <li>On a multi-tenant cloud install a private-range target is either unreachable anyway
     *       (there is no peering to a tenant's own network) or it IS the platform's own
     *       infrastructure, so refusing costs a legitimate tenant nothing and is the entire point.</li>
     *   <li>On a self-hosted install the operator owns both ends, and a database on
     *       {@code 10.0.0.5} or an SFTP server on a container network is the normal case. Those
     *       installs opt in by listing the CIDRs they accept in
     *       {@link #PRIVATE_EGRESS_ALLOW_LIST_PROPERTY} (or the {@code LIVECONTEXT_EGRESS_ALLOWED_PRIVATE_CIDRS}
     *       environment variable), for example
     *       {@code 10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,fc00::/7}.</li>
     *   <li>The default follows the EDITION ({@link #configureEditionDefaults}, called at startup
     *       from {@link AppEditionAutoConfiguration}): empty on a cloud edition, the RFC 1918 /
     *       CGNAT / ULA ranges and the {@code .local}/{@code .internal} suffixes on a self-hosted
     *       one, so an existing self-hosted install whose connectors point at its LAN keeps
     *       working on upgrade. An explicit setting replaces the default; {@code none} restores
     *       the cloud posture. The shipped Compose files and Helm chart do not pass the variables
     *       through, so an operator adds them to the app container's environment.</li>
     *   <li><b>Residual on a multi-user self-hosted install.</b> With the self-hosted default,
     *       every user of the install can point a connector or a custom API at any LAN address,
     *       including the install's own sidecars (Postgres, Redis, MinIO) on the Docker network.
     *       An operator who does not trust every user sets the variables to {@code none} or to the
     *       exact ranges needed (the project docs).</li>
     *   <li>Link-local (metadata endpoints), the unspecified range and the broadcast address are
     *       refused no matter what the allow-list says, because no data-plane connector has a
     *       legitimate reason to reach them in any topology. The same applies by NAME to
     *       {@code localhost} and the metadata hostnames, see
     *       {@link #NEVER_ALLOWLISTABLE_HOST_SUFFIXES}. Every OTHER internal hostname suffix
     *       ({@code .local}, {@code .internal}, {@code cluster.local}, ...) is unlocked by
     *       {@link #PRIVATE_EGRESS_HOST_SUFFIX_PROPERTY}.</li>
     * </ul>
     *
     * <p><b>On its own this is a check-then-connect guard.</b> It resolves the name, judges the
     * answer and returns it; whether the connector then connects to THAT address or resolves the
     * name a second time is up to the connector. An attacker who controls DNS for a host they
     * configure can answer public here and private on the second lookup. Callers close that window
     * by dialling the returned {@link InetAddress} instead of the name, which is what
     * {@link #resolveOutboundHostSafe} exists for.
     *
     * <p><b>A TLS connector must NOT close it by substituting the address into the hostname.</b>
     * An IP literal in place of the name loses certificate-identity verification and SNI, which is
     * a worse trade than the window it shuts. The correct shape keeps the NAME everywhere the
     * client library uses it for identity and controls only the ADDRESS the socket dials, through
     * {@link #pinnedSocketFactory}. See {@link PinnedAddressSocketFactory} for which client
     * libraries accept that and which do not.
     *
     * <p><b>Every shipped connector calls {@link #resolveOutboundHostSafe} instead</b>, because all
     * five now need the vetted address and not only the verdict. This overload is the same policy
     * with the address dropped, kept as the readable statement of that policy for a caller that
     * genuinely has nothing to dial, and it is what the policy tests assert against. Reaching for
     * it in a new connector is the wrong choice: it leaves the second lookup to the client library.
     *
     * @param host hostname or IP literal, as configured on the node or in the credential
     * @param port TCP port
     * @throws IllegalArgumentException when the target is missing, malformed, or internal
     */
    public static void assertOutboundHostSafe(String host, int port) {
        resolveOutboundHostSafe(host, port);
    }

    /**
     * The {@link #assertOutboundHostSafe} check, returning the vetted address so a caller can
     * connect to it instead of re-resolving the name. Every address the name resolves to has to
     * pass; the first one is returned.
     *
     * @return the vetted address to connect to
     * @throws IllegalArgumentException when the target is missing, malformed, or internal
     */
    public static InetAddress resolveOutboundHostSafe(String host, int port) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Outbound host must not be null or blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(
                "Outbound port must be between 1 and 65535 (got: " + port + ")");
        }
        if (host.indexOf('%') >= 0) {
            // The check has to reject rather than tolerate, because normalizeHost DISCARDS
            // everything from the '%' onwards (it is the IPv6 zone separator) and what this method
            // judges is therefore the truncated name. A caller that keeps the ORIGINAL string - the
            // Database node concatenates it into a JDBC URL - carries the discarded tail into a
            // delimited string that was never vetted, so "93.184.216.34%?socketFactory=evil"
            // resolves and passes as "93.184.216.34" while the driver reads a property the guard
            // never saw. That is the same non-injective mapping as a signature over concatenated
            // fields: the vetted value and the used value must be the same string.
            //
            // Nothing legitimate is lost. A '%' is invalid in a DNS name and in an IPv4 literal,
            // and the only shape it has here is an IPv6 zone id, whose zone is stripped before the
            // socket is dialled anyway - so a link-local target with a zone never worked, it
            // failed later and less clearly.
            throw new IllegalArgumentException(
                "Outbound host must not contain '%': " + host
                    + " (the '%' zone separator truncates the name this guard checks, so the rest"
                    + " of the string would reach the connection unvetted)");
        }

        String normalized = normalizeHost(host);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Outbound host must not be null or blank");
        }
        if (matchesSuffix(normalized, NEVER_ALLOWLISTABLE_HOST_SUFFIXES)) {
            throw new IllegalArgumentException(
                "Connections to internal hostnames are not allowed: " + host
                    + " (this name can never be permitted; it identifies the host itself or a "
                    + "cloud metadata endpoint)");
        }
        if (isBlockedHostname(normalized) && !matchesSuffix(normalized, allowedPrivateHostSuffixes())) {
            throw new IllegalArgumentException(
                "Connections to internal hostnames are not allowed: " + host
                    + " (to permit this suffix on a self-hosted install, set "
                    + PRIVATE_EGRESS_HOST_SUFFIX_ENV + ")");
        }

        List<Cidr> allowList = privateEgressAllowList();
        InetAddress[] addresses = resolveHostWithTimeout(normalized);
        if (addresses.length == 0) {
            throw new UnresolvableHostException("Cannot resolve hostname: " + host);
        }
        for (InetAddress address : addresses) {
            if (isUnsafeAddress(address, allowList)) {
                throw new IllegalArgumentException(
                    "Connections to private/internal network addresses are not allowed: "
                        + host + ":" + port
                        + " (to permit this range on a self-hosted install, set "
                        + PRIVATE_EGRESS_ALLOW_LIST_ENV + ")");
            }
        }
        return addresses[0];
    }

    /**
     * Renders a vetted address as a bare host string for an API that takes a host and a port
     * separately (a socket, a JSch session, a JavaMail property). IPv6 keeps its colons and loses
     * any {@code %zone}; it is NOT bracketed, because those APIs are not parsing a URL.
     */
    public static String toSocketHost(InetAddress address) {
        String literal = address.getHostAddress();
        int zone = literal.indexOf('%');
        return zone > 0 ? literal.substring(0, zone) : literal;
    }

    /**
     * Renders a vetted address for the authority component of a URL. Same as
     * {@link #toSocketHost} except an IPv6 literal is bracketed, without which
     * {@code jdbc:postgresql://fd00::1:5432/db} is unparseable.
     */
    public static String toUrlHost(InetAddress address) {
        String literal = toSocketHost(address);
        return address instanceof Inet6Address ? "[" + literal + "]" : literal;
    }

    /**
     * A socket factory that dials {@code vetted} and nothing else.
     *
     * @param vetted an address that has already passed {@link #resolveOutboundHostSafe}
     * @see PinnedAddressSocketFactory
     */
    public static SocketFactory pinnedSocketFactory(InetAddress vetted) {
        return new PinnedAddressSocketFactory(vetted);
    }

    /**
     * Property suffix Jakarta Mail reads to decide whether a socket-factory failure may be retried
     * WITHOUT the factory. It defaults to {@code true}, and that default is a hole: the retry
     * builds a plain socket from the hostname, which resolves a second time and lands wherever DNS
     * now points. {@link #applyJavaMailAddressPinning} always sets it to {@code false}.
     */
    private static final String JAVAMAIL_SOCKET_FACTORY_FALLBACK_SUFFIX = ".socketFactory.fallback";

    /**
     * Points a Jakarta Mail session at a vetted address without touching the hostname it uses for
     * TLS.
     *
     * <p>Jakarta Mail accepts a {@link SocketFactory} <b>instance</b> under
     * {@code mail.<protocol>.socketFactory} (a class name is a different property, and an instance
     * cannot be expressed in a properties file). It creates the socket from that factory, connects
     * it, and only then layers TLS on top with
     * {@code SSLSocketFactory.createSocket(socket, host, port, true)} using the configured HOST
     * NAME, so SNI and the server-identity check are unaffected by the pin. That holds for both
     * shapes this project uses: implicit TLS ({@code ssl.enable}, ports 465 and 993) and STARTTLS.
     *
     * <p>The companion {@code .socketFactory.fallback=false} is not optional. Left at its default
     * of {@code true}, a failure inside the factory makes Jakarta Mail retry the connection with no
     * factory at all, resolving the name again and dialling whatever comes back, which is exactly
     * the window being closed.
     *
     * @param props           the session properties, mutated in place
     * @param protocolPrefix  {@code "mail.smtp"}, {@code "mail.imap"}, {@code "mail.imaps"}, ...
     * @param vetted          an address that has already passed {@link #resolveOutboundHostSafe}
     */
    public static void applyJavaMailAddressPinning(Properties props, String protocolPrefix,
                                                   InetAddress vetted) {
        props.put(protocolPrefix + ".socketFactory", pinnedSocketFactory(vetted));
        props.put(protocolPrefix + JAVAMAIL_SOCKET_FACTORY_FALLBACK_SUFFIX, "false");
    }

    /**
     * Connects every socket it hands out to ONE already-vetted address, whatever hostname the
     * client library asks for.
     *
     * <p><b>Why this exists.</b> {@link #resolveOutboundHostSafe} resolves a name and judges the
     * answer, but a client library that is then given the NAME resolves it again, and the second
     * answer is the one the socket obeys. Substituting an IP literal for the name closes that
     * window and breaks TLS: certificate identity and SNI are both checked against the name. This
     * factory separates the two. The library keeps the name for identity; the socket goes where the
     * guard said it may go. A wasted second lookup still happens inside the library, its answer is
     * simply discarded.
     *
     * <p><b>Which clients accept it, verified against the versions this project builds against.</b>
     * <ul>
     *   <li><b>Jakarta Mail (Angus Mail 2.0.3)</b>, SMTP and IMAP, both TLS shapes: yes, as an
     *       instance, see {@link #applyJavaMailAddressPinning}.</li>
     *   <li><b>PostgreSQL JDBC (pgjdbc 42.7.x)</b>: yes, by CLASS NAME. {@code socketFactory} names
     *       the class and {@code socketFactoryArg} carries one String, which is why this class has
     *       a public single-String constructor: pgjdbc instantiates through a {@code (Properties)},
     *       then {@code (String)}, then no-arg constructor, in that order. pgjdbc creates the
     *       socket from the factory and upgrades it to TLS in place against the URL's host name,
     *       so {@code sslmode=verify-full} still verifies the name.</li>
     *   <li><b>MySQL Connector/J</b>: NOT through this class. Its {@code socketFactory} property
     *       wants the vendor interface {@code com.mysql.cj.protocol.SocketFactory}, not
     *       {@code javax.net.SocketFactory}, so an implementation has to compile against the
     *       driver. The driver is not a dependency of any module here.</li>
     *   <li><b>Microsoft SQL Server JDBC</b>: its {@code socketFactoryClass} plus
     *       {@code socketFactoryConstructorArg} (driver 9.2 and later) would take this class
     *       unchanged, and {@code hostNameInCertificate} is a second, narrower option. Neither is
     *       wired, because that driver is not a dependency of any module here either.</li>
     * </ul>
     *
     * <p><b>Availability.</b> Pinning does not narrow what a connector may reach: the libraries
     * above already dial a single address (they build an {@code InetSocketAddress} from the name,
     * which keeps one answer), so this changes WHICH resolution decides, not how many targets are
     * reachable. The self-hosted opt-ins on {@link #assertOutboundHostSafe} keep working unchanged,
     * and the single-String constructor re-applies them, so a pinned address is refused here too if
     * it is internal and not allow-listed.
     */
    public static class PinnedAddressSocketFactory extends SocketFactory {

        private final InetAddress pinned;

        public PinnedAddressSocketFactory(InetAddress vetted) {
            if (vetted == null) {
                throw new IllegalArgumentException("A pinned socket factory needs a vetted address");
            }
            this.pinned = vetted;
        }

        /**
         * Reflective entry point for a client that takes a socket-factory CLASS NAME plus one
         * String argument (pgjdbc's {@code socketFactoryArg}).
         *
         * <p>An IP literal only. A hostname is refused rather than resolved: resolving here would
         * turn the one component whose entire job is to stop following DNS into another place that
         * follows it.
         */
        public PinnedAddressSocketFactory(String vettedIpLiteral) {
            this(requireVettedLiteral(vettedIpLiteral));
        }

        private static InetAddress requireVettedLiteral(String literal) {
            InetAddress parsed = literal == null || literal.isBlank()
                ? null
                : parseIpLiteral(normalizeHost(literal));
            if (parsed == null) {
                throw new IllegalArgumentException(
                    "A pinned socket factory takes an IP literal, not a hostname: " + literal);
            }
            if (isUnsafeAddress(parsed, privateEgressAllowList())) {
                throw new IllegalArgumentException(
                    "Connections to private/internal network addresses are not allowed: " + literal
                        + " (to permit this range on a self-hosted install, set "
                        + PRIVATE_EGRESS_ALLOW_LIST_ENV + ")");
            }
            return parsed;
        }

        /** The address every socket from this factory connects to. */
        public InetAddress pinnedAddress() {
            return pinned;
        }

        @Override
        public Socket createSocket() {
            return new PinnedSocket(pinned);
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return connected(port, null, 0);
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localAddress, int localPort)
                throws IOException {
            return connected(port, localAddress, localPort);
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return connected(port, null, 0);
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
                throws IOException {
            return connected(port, localAddress, localPort);
        }

        /**
         * The requested host is deliberately ignored in every overload above: this factory exists
         * because the requested host is the thing that must not decide where the socket goes. Only
         * the port travels through.
         */
        private Socket connected(int port, InetAddress localAddress, int localPort) throws IOException {
            Socket socket = new PinnedSocket(pinned);
            try {
                if (localAddress != null) {
                    socket.bind(new InetSocketAddress(localAddress, localPort));
                }
                socket.connect(new InetSocketAddress(pinned, port));
            } catch (IOException | RuntimeException e) {
                socket.close();
                throw e;
            }
            return socket;
        }

        @Override
        public String toString() {
            return "PinnedAddressSocketFactory[" + toSocketHost(pinned) + "]";
        }
    }

    /**
     * The socket the factory hands out. Both {@code connect} overloads are redirected, because
     * every client here creates an UNCONNECTED socket from the factory and connects it itself, from
     * an {@link InetSocketAddress} built out of the hostname. Redirecting at that point is what
     * makes the pin hold no matter which of the two overloads the library calls.
     */
    private static final class PinnedSocket extends Socket {

        private final InetAddress pinned;

        private PinnedSocket(InetAddress pinned) {
            this.pinned = pinned;
        }

        @Override
        public void connect(SocketAddress endpoint) throws IOException {
            super.connect(pin(endpoint));
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            super.connect(pin(endpoint), timeout);
        }

        /**
         * Keeps the port the caller asked for and replaces the address. An endpoint that is not an
         * IP endpoint is refused rather than passed through: passing it through would be a silent
         * way around the pin.
         */
        private SocketAddress pin(SocketAddress endpoint) throws IOException {
            if (!(endpoint instanceof InetSocketAddress requested)) {
                throw new IOException(
                    "A pinned socket can only connect to an IP endpoint, got: " + endpoint);
            }
            return new InetSocketAddress(pinned, requested.getPort());
        }
    }

    /**
     * Records the deployment edition so the egress settings can pick their default. Called once at
     * startup by {@link AppEditionAutoConfiguration}. Cloud editions keep an empty default (refuse
     * every private target); self-hosted editions default to
     * {@link #SELF_HOSTED_DEFAULT_PRIVATE_CIDRS} / {@link #SELF_HOSTED_DEFAULT_PRIVATE_HOST_SUFFIXES}.
     * An explicit setting always wins over the edition default.
     */
    public static void configureEditionDefaults(boolean selfHosted) {
        if (selfHostedEdition != selfHosted) {
            selfHostedEdition = selfHosted;
            invalidateSettingCachesForTests();
            logger.info("[egress] {} edition: private egress default = {}",
                selfHosted ? "self-hosted" : "cloud",
                selfHosted ? SELF_HOSTED_DEFAULT_PRIVATE_CIDRS : "none");
        }
    }

    private static List<Cidr> privateEgressAllowList() {
        String raw = readSetting(PRIVATE_EGRESS_ALLOW_LIST_PROPERTY, PRIVATE_EGRESS_ALLOW_LIST_ENV,
            SELF_HOSTED_DEFAULT_PRIVATE_CIDRS);
        ParsedSetting<List<Cidr>> cached = cachedAllowList;
        if (!raw.equals(cached.raw())) {
            cached = new ParsedSetting<>(raw, parseCidrList(raw));
            cachedAllowList = cached;
        }
        return cached.value();
    }

    private static Set<String> allowedPrivateHostSuffixes() {
        String raw = readSetting(PRIVATE_EGRESS_HOST_SUFFIX_PROPERTY, PRIVATE_EGRESS_HOST_SUFFIX_ENV,
            SELF_HOSTED_DEFAULT_PRIVATE_HOST_SUFFIXES);
        ParsedSetting<Set<String>> cached = cachedHostSuffixes;
        if (!raw.equals(cached.raw())) {
            cached = new ParsedSetting<>(raw, parseHostSuffixList(raw));
            cachedHostSuffixes = cached;
        }
        return cached.value();
    }

    /**
     * System property first, then environment variable. A blank value counts as unset (the shipped
     * Compose files pass the variable through with an empty default), so the edition default
     * applies; {@link #EGRESS_SETTING_NONE} explicitly selects "nothing".
     */
    private static String readSetting(String propertyName, String envName, String selfHostedDefault) {
        String value = System.getProperty(propertyName);
        if (value == null || value.isBlank()) {
            value = environmentReader.apply(envName);
        }
        if (value == null || value.isBlank()) {
            return selfHostedEdition ? selfHostedDefault : "";
        }
        String trimmed = value.trim();
        return EGRESS_SETTING_NONE.equalsIgnoreCase(trimmed) ? "" : trimmed;
    }

    /**
     * Parses the suffix opt-in. Entries are normalised the way a host is (lowercase, leading dot
     * dropped) and anything naming a never-allow-listable host is discarded, so
     * {@code metadata.google.internal} cannot be smuggled in as its own suffix.
     */
    private static Set<String> parseHostSuffixList(String raw) {
        if (raw.isBlank()) {
            return Set.of();
        }
        Set<String> parsed = new LinkedHashSet<>();
        for (String entry : raw.split(",")) {
            String trimmed = entry.trim().toLowerCase(Locale.ROOT);
            while (trimmed.startsWith(".")) {
                trimmed = trimmed.substring(1);
            }
            if (trimmed.isEmpty()) {
                continue;
            }
            if (NEVER_ALLOWLISTABLE_HOST_SUFFIXES.contains(trimmed)) {
                logger.warn("Ignoring entry '{}' in {}: that hostname can never be permitted",
                    trimmed, PRIVATE_EGRESS_HOST_SUFFIX_PROPERTY);
                continue;
            }
            parsed.add(trimmed);
        }
        return Set.copyOf(parsed);
    }

    private static List<Cidr> parseCidrList(String raw) {
        if (raw.isBlank()) {
            return List.of();
        }
        List<Cidr> parsed = new ArrayList<>();
        for (String entry : raw.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                int slash = trimmed.indexOf('/');
                String literal = slash < 0 ? trimmed : trimmed.substring(0, slash);
                // IP literals ONLY. InetAddress.getByName resolves anything else, so a typo used
                // to cost a blocking DNS lookup and, worse, a HOSTNAME entry resolved fine and
                // became a silent /32 allow rule that followed that name's DNS from then on.
                InetAddress base = parseIpLiteral(normalizeHost(literal));
                if (base == null) {
                    throw new IllegalArgumentException("not an IP literal");
                }
                int maxBits = base.getAddress().length * 8;
                int bits = slash < 0 ? maxBits : Integer.parseInt(trimmed.substring(slash + 1).trim());
                if (bits < 0 || bits > maxBits) {
                    throw new IllegalArgumentException("prefix out of range: " + bits);
                }
                parsed.add(new Cidr(base.getAddress(), bits));
            } catch (Exception e) {
                logger.warn("Ignoring unparseable entry '{}' in {}: {}",
                    trimmed, PRIVATE_EGRESS_ALLOW_LIST_PROPERTY, e.getMessage());
            }
        }
        return List.copyOf(parsed);
    }

    private static Cidr cidr(String literal, int prefixBits) {
        try {
            return new Cidr(InetAddress.getByName(literal).getAddress(), prefixBits);
        } catch (UnknownHostException e) {
            // Only ever called with IP literals, which never hit DNS.
            throw new IllegalStateException("Invalid CIDR base in the SSRF table: " + literal, e);
        }
    }

    private static InetAddress[] resolveHostWithTimeout(String host) {
        // An IP literal is judged as written, never handed to a resolver.
        InetAddress literal = parseIpLiteral(host);
        if (literal != null) {
            return new InetAddress[] {literal};
        }
        try {
            boolean acquired = DNS_RESOLUTION_PERMITS.tryAcquire(
                    DNS_RESOLUTION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new UrlResolutionException("DNS resolution capacity exceeded for hostname: " + host);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UrlResolutionException("DNS resolution interrupted for hostname: " + host);
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
            throw new UrlResolutionException("DNS resolution timed out for hostname: " + host);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UrlResolutionException("DNS resolution interrupted for hostname: " + host);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof UnknownHostException) {
                throw new UnresolvableHostException("Cannot resolve hostname: " + host);
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new UnresolvableHostException("Cannot resolve hostname: " + host);
        }
    }

    static void setDnsResolverForTests(DnsResolver resolver) {
        dnsResolver = resolver == null ? InetAddress::getAllByName : resolver;
    }

    static void resetDnsResolverForTests() {
        dnsResolver = InetAddress::getAllByName;
    }

    /**
     * Replaces the environment lookup used by the two egress settings. The environment-variable
     * branch is the ONLY one an operator in a container can use, and the JDK offers no way to set
     * a real variable from a test, so it needs this seam to be covered at all.
     */
    static void setEnvironmentReaderForTests(UnaryOperator<String> reader) {
        environmentReader = reader == null ? System::getenv : reader;
        invalidateSettingCachesForTests();
    }

    static void resetEnvironmentReaderForTests() {
        environmentReader = System::getenv;
        invalidateSettingCachesForTests();
    }

    /**
     * Drops the parsed-setting caches. Needed because the caches key on the setting TEXT: swapping
     * the environment reader can produce the same text from a different source, and a test would
     * otherwise read a value the previous test parsed.
     */
    /** Restores the cloud posture set before {@link #configureEditionDefaults} ever runs. */
    static void resetEditionDefaultsForTests() {
        selfHostedEdition = false;
        invalidateSettingCachesForTests();
    }

    static void invalidateSettingCachesForTests() {
        cachedAllowList = new ParsedSetting<>(null, List.of());
        cachedHostSuffixes = new ParsedSetting<>(null, Set.of());
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
