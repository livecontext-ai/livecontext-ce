package com.apimarketplace.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression tests for LC-007 (security audit 2026-08-13).
 *
 * {@code validateUrl} returned SUCCESSFULLY without resolving DNS whenever the host still
 * contained a template placeholder, on the written assumption that "the execution layer
 * re-validates the fully substituted URL". No such re-validation existed. A caller could
 * therefore keep a placeholder in the HOST, pass validation, and have the value substituted
 * from its own credential data on the way to the request, giving an ordinary authenticated user
 * a response-reflecting GET into the cluster.
 *
 * <p>The fix has two halves, and both are asserted here because they are easy to get wrong in
 * opposite directions: {@code validateUrl} (the connect-time check) must REFUSE a templated
 * host, while {@code validateUrlFormat} (the registration-time check) must still ACCEPT one.
 * 158 shipped catalog APIs declare a base URL such as {@code https://{account}.snowflakecomputing.com},
 * so a resolving check at registration would reject every one of them.
 */
@DisplayName("UrlSafetyValidator placeholder handling")
class UrlSafetyValidatorPlaceholderTest {

    @Nested
    @DisplayName("validateUrl (connect time)")
    class ConnectTime {

        @ParameterizedTest(name = "refuses a placeholder in the host: {0}")
        @ValueSource(strings = {
                "https://{account}.snowflakecomputing.com/api/v2",
                "https://{api_host}",
                "http://{your-coolify-host}:8000/api/v1",
                "https://{dc}.api.mailchimp.com/3.0",
        })
        void refusesTemplatedHost(String templated) {
            // Nothing legitimate connects to a templated hostname. Returning success here was
            // the bug: it let an unresolved authority through the only SSRF gate.
            assertThatThrownBy(() -> UrlSafetyValidator.validateUrl(templated))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("placeholder");
        }

        @Test
        @DisplayName("a placeholder in the PATH or QUERY is not the host's problem")
        void toleratesPlaceholdersOutsideTheAuthority() {
            // These resolve normally: the authority is concrete, so the DNS + IP check runs and
            // decides. Only the host is inspected for placeholders.
            assertThatCode(() -> UrlSafetyValidator.validateUrl("https://example.com/v1/{id}/messages"))
                    .doesNotThrowAnyException();
            assertThatCode(() -> UrlSafetyValidator.validateUrl("https://example.com/v1?q={term}"))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("private address ranges")
    class PrivateRanges {

        @Test
        @DisplayName("refuses IPv6 unique-local fc00::/7, which isSiteLocalAddress does not cover")
        void refusesIpv6UniqueLocal() throws Exception {
            // isSiteLocalAddress() only matches the DEPRECATED fec0::/10 for IPv6, so fd00::/8 -
            // what a dual-stack cluster and AWS IMDS over IPv6 actually use - passed every check.
            assertThat(UrlSafetyValidator.isUnsafeAddress(
                    java.net.InetAddress.getByName("fd00:ec2::254"))).isTrue();
            assertThat(UrlSafetyValidator.isUnsafeAddress(
                    java.net.InetAddress.getByName("fc00::1"))).isTrue();
        }

        @Test
        @DisplayName("refuses IPv4 shared address space 100.64.0.0/10 (RFC 6598)")
        void refusesCarrierGradeNat() throws Exception {
            assertThat(UrlSafetyValidator.isUnsafeAddress(
                    java.net.InetAddress.getByName("100.64.0.1"))).isTrue();
            assertThat(UrlSafetyValidator.isUnsafeAddress(
                    java.net.InetAddress.getByName("100.127.255.254"))).isTrue();
        }

        @Test
        @DisplayName("still refuses the ranges it always did")
        void refusesTheClassicRanges() throws Exception {
            assertThat(UrlSafetyValidator.isUnsafeAddress(
                    java.net.InetAddress.getByName("127.0.0.1"))).isTrue();
            assertThat(UrlSafetyValidator.isUnsafeAddress(
                    java.net.InetAddress.getByName("10.0.9.5"))).isTrue();
            assertThat(UrlSafetyValidator.isUnsafeAddress(
                    java.net.InetAddress.getByName("169.254.169.254"))).isTrue();
        }

        @Test
        @DisplayName("does not over-block a public address")
        void allowsPublicAddresses() throws Exception {
            // The widened ranges must not swallow ordinary traffic: 100.63.x and 100.128.x sit
            // just OUTSIDE 100.64.0.0/10 and are ordinary public space.
            assertThat(UrlSafetyValidator.isUnsafeAddress(
                    java.net.InetAddress.getByName("100.63.255.255"))).isFalse();
            assertThat(UrlSafetyValidator.isUnsafeAddress(
                    java.net.InetAddress.getByName("100.128.0.1"))).isFalse();
            assertThat(UrlSafetyValidator.isUnsafeAddress(
                    java.net.InetAddress.getByName("8.8.8.8"))).isFalse();
            assertThat(UrlSafetyValidator.isUnsafeAddress(
                    java.net.InetAddress.getByName("2001:4860:4860::8888"))).isFalse();
        }
    }

    @Nested
    @DisplayName("validateRegistrationUrl (strictest check the URL allows)")
    class Registration {

        @Test
        @DisplayName("a concrete URL gets the RESOLVING check, so an internal address is refused")
        void concreteUrlIsResolved() {
            // The regression this prevents: switching registration to format-only for every URL
            // silently let a non-custom API register http://169.254.169.254.
            assertThatThrownBy(() -> UrlSafetyValidator.validateRegistrationUrl("http://169.254.169.254/latest"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a templated host falls back to format-only, so the catalog still imports")
        void templatedHostIsFormatChecked() {
            assertThatCode(() -> UrlSafetyValidator.validateRegistrationUrl(
                    "https://{account}.snowflakecomputing.com/api/v2")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a templated PATH still gets the resolving check on its concrete host")
        void templatedPathStillResolves() {
            assertThatThrownBy(() -> UrlSafetyValidator.validateRegistrationUrl("http://127.0.0.1/{id}"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("validateUrlFormat (registration time)")
    class RegistrationTime {

        @ParameterizedTest(name = "still accepts {0}")
        @ValueSource(strings = {
                "https://{account}.snowflakecomputing.com/api/v2",
                "https://{applicationId}.algolia.net",
                "https://{companyDomain}.bamboohr.com/api/v1",
        })
        void acceptsTemplatedHost(String templated) {
            // The catalog cannot be imported otherwise. This is the half a naive fix breaks.
            assertThatCode(() -> UrlSafetyValidator.validateUrlFormat(templated))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("still refuses a non-http scheme and localhost, template or not")
        void keepsItsOwnRules() {
            assertThatThrownBy(() -> UrlSafetyValidator.validateUrlFormat("file:///etc/passwd"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> UrlSafetyValidator.validateUrlFormat("http://localhost/{x}"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
