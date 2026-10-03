package com.apimarketplace.common.web;

import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.InetNameResolver;
import io.netty.resolver.InetSocketAddressResolver;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/**
 * Connect-time SSRF pin for Reactor Netty clients (LC-073).
 *
 * <p>A pre-flight check such as {@link UrlSafetyValidator#validateUrl} resolves a name and judges
 * the answer, then the HTTP client resolves the SAME name again through its own resolver and
 * cache and connects wherever that second answer points. A name that answers public to the check
 * and private to the connect (DNS rebinding) therefore reaches the cluster. This resolver group
 * judges the very answer the socket is dialled with: it resolves through the JDK resolver and
 * fails the resolution when ANY address is internal, so the address that is checked is the
 * address that is connected to.
 *
 * <p>An IP literal never reaches a resolver, so a client using this group should also call
 * {@link #assertRemoteAddressSafe} from a channel hook ({@code doOnChannelInit} /
 * {@code doOnConnected}).
 *
 * <p>Use {@link #strict()} for targets that must never be private (HTTP Request, Download File)
 * and {@link #egress()} for user-configured targets that honour the self-hosted private-egress
 * policy (catalog tool execution).
 */
public final class SafeAddressResolverGroup extends AddressResolverGroup<InetSocketAddress> {

    /** Error text used when a hostname resolves to an internal address. Pinned by tests. */
    public static final String INTERNAL_TARGET_MESSAGE = "resolves to a private/internal network address";

    /** Resolution seam, so a test can simulate a rebinding answer. */
    @FunctionalInterface
    public interface Lookup {
        InetAddress[] lookup(String host) throws UnknownHostException;
    }

    private final Predicate<InetAddress> unsafeAddress;
    private final Lookup lookup;

    public SafeAddressResolverGroup(Predicate<InetAddress> unsafeAddress) {
        this(unsafeAddress, InetAddress::getAllByName);
    }

    public SafeAddressResolverGroup(Predicate<InetAddress> unsafeAddress, Lookup lookup) {
        this.unsafeAddress = unsafeAddress;
        this.lookup = lookup;
    }

    /** Every private/internal address refused, whatever the edition. */
    public static SafeAddressResolverGroup strict() {
        return new SafeAddressResolverGroup(UrlSafetyValidator::isUnsafeAddress);
    }

    /** The self-hosted private-egress policy applies; loopback and metadata stay refused. */
    public static SafeAddressResolverGroup egress() {
        return new SafeAddressResolverGroup(UrlSafetyValidator::isUnsafeEgressAddress);
    }

    public Predicate<InetAddress> unsafeAddress() {
        return unsafeAddress;
    }

    @Override
    protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
        return new InetSocketAddressResolver(executor, new SafeNameResolver(executor, unsafeAddress, lookup));
    }

    /**
     * Resolves and vets. Refuses when ANY answer is internal: a name that answers with one public
     * and one private address is a rebinding attempt, not a dual-homed host worth accommodating.
     */
    public static List<InetAddress> vet(String host, Predicate<InetAddress> unsafeAddress, Lookup lookup)
            throws UnknownHostException {
        InetAddress[] resolved = lookup.lookup(host);
        if (resolved == null || resolved.length == 0) {
            throw new UnknownHostException(host);
        }
        for (InetAddress address : resolved) {
            if (unsafeAddress.test(address)) {
                throw new IllegalArgumentException(
                    host + " " + INTERNAL_TARGET_MESSAGE + ": " + address.getHostAddress());
            }
        }
        return Arrays.asList(resolved);
    }

    /**
     * Refuses a socket whose peer is internal. An unresolved address is left alone: nothing is
     * connected yet, and the resolver above sees it.
     */
    public static void assertRemoteAddressSafe(SocketAddress remoteAddress, Predicate<InetAddress> unsafeAddress) {
        if (!(remoteAddress instanceof InetSocketAddress inet)) {
            return;
        }
        InetAddress address = inet.getAddress();
        if (address != null && unsafeAddress.test(address)) {
            throw new IllegalArgumentException(
                "Refusing to connect to a private/internal network address: " + address.getHostAddress());
        }
    }

    private static final class SafeNameResolver extends InetNameResolver {

        private final Predicate<InetAddress> unsafeAddress;
        private final Lookup lookup;

        private SafeNameResolver(EventExecutor executor, Predicate<InetAddress> unsafeAddress, Lookup lookup) {
            super(executor);
            this.unsafeAddress = unsafeAddress;
            this.lookup = lookup;
        }

        @Override
        protected void doResolve(String inetHost, Promise<InetAddress> promise) {
            try {
                promise.setSuccess(vet(inetHost, unsafeAddress, lookup).get(0));
            } catch (Exception e) {
                promise.setFailure(e);
            }
        }

        @Override
        protected void doResolveAll(String inetHost, Promise<List<InetAddress>> promise) {
            try {
                promise.setSuccess(vet(inetHost, unsafeAddress, lookup));
            } catch (Exception e) {
                promise.setFailure(e);
            }
        }
    }
}
