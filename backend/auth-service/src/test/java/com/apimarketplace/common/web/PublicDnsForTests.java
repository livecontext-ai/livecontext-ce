package com.apimarketplace.common.web;

import java.net.InetAddress;

/**
 * Test-only access to {@link UrlSafetyValidator}'s package-private resolver seam, for auth-service
 * tests whose fixtures name placeholder provider hosts ({@code 123-ABC-456.mktorest.com},
 * {@code custom.example.com}). The use-time OAuth2 endpoint check now REQUIRES the host to resolve;
 * this answers every hostname with a public documentation address so those tests stay hermetic,
 * while IP literals keep their real value (so {@code 10.x} / {@code 169.254.x} are still refused).
 */
public final class PublicDnsForTests {

    private PublicDnsForTests() {
    }

    public static void install() {
        UrlSafetyValidator.setDnsResolverForTests(host -> {
            if (host.matches("[0-9.]+") || host.contains(":")) {
                return InetAddress.getAllByName(host);
            }
            return new InetAddress[]{InetAddress.getByAddress(host, new byte[]{93, (byte) 184, (byte) 216, 34})};
        });
    }

    public static void reset() {
        UrlSafetyValidator.resetDnsResolverForTests();
    }
}
