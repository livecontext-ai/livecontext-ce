package com.apimarketplace.auth.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SsoDomainDnsVerifier")
class SsoDomainDnsVerifierTest {

    private static SsoDomainDnsVerifier withRecords(List<String> values) {
        return new SsoDomainDnsVerifier() {
            @Override
            protected List<String> lookupTxt(String name) {
                assertThat(name).isEqualTo("_livecontext-sso.acme.com");
                return values;
            }
        };
    }

    @Test
    @DisplayName("the record lives on a dedicated label, never on the apex where SPF sits")
    void recordNameIsDedicatedLabel() {
        assertThat(new SsoDomainDnsVerifier().recordName("acme.com")).isEqualTo("_livecontext-sso.acme.com");
    }

    @Test
    @DisplayName("verified when one TXT value is exactly the expected one, among others")
    void matchesExactValueAmongOthers() {
        SsoDomainDnsVerifier v = withRecords(List.of("v=spf1 -all", "livecontext-sso-verification=abc"));

        assertThat(v.isVerified("acme.com", "abc")).isTrue();
    }

    @Test
    @DisplayName("another workspace's token, a prefix or a suffix does not verify")
    void rejectsNearMisses() {
        assertThat(withRecords(List.of("livecontext-sso-verification=other")).isVerified("acme.com", "abc")).isFalse();
        assertThat(withRecords(List.of("livecontext-sso-verification=abcX")).isVerified("acme.com", "abc")).isFalse();
        assertThat(withRecords(List.of("xlivecontext-sso-verification=abc")).isVerified("acme.com", "abc")).isFalse();
        assertThat(withRecords(List.of()).isVerified("acme.com", "abc")).isFalse();
    }

    @Test
    @DisplayName("JNDI quoting is removed and split strings are joined")
    void normalizesQuotedAndSplitValues() {
        assertThat(SsoDomainDnsVerifier.normalize("\"livecontext-sso-verification=abc\""))
                .isEqualTo("livecontext-sso-verification=abc");
        assertThat(SsoDomainDnsVerifier.normalize("\"livecontext-sso-\" \"verification=abc\""))
                .isEqualTo("livecontext-sso-verification=abc");
        assertThat(SsoDomainDnsVerifier.normalize("  plain  ")).isEqualTo("plain");
    }
}
