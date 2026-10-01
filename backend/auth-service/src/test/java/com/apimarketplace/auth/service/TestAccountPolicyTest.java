package com.apimarketplace.auth.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TestAccountPolicy")
class TestAccountPolicyTest {

    private static final String PATTERN = "signup\\.canary(\\+[a-z0-9-]+)?@example\\.test";

    @Test
    @DisplayName("unset pattern: no account is a test account, so every deletion keeps its grace period")
    void unsetPatternMatchesNothing() {
        assertThat(new TestAccountPolicy("").isTestAccount("signup.canary@example.test")).isFalse();
        assertThat(new TestAccountPolicy(null).isTestAccount("signup.canary@example.test")).isFalse();
    }

    @Test
    @DisplayName("matches the whole address, case-insensitively, including a plus tag")
    void matchesWholeAddressCaseInsensitive() {
        TestAccountPolicy policy = new TestAccountPolicy(PATTERN);
        assertThat(policy.isTestAccount("signup.canary@example.test")).isTrue();
        assertThat(policy.isTestAccount(" Signup.Canary+daily@Example.TEST ")).isTrue();
    }

    @Test
    @DisplayName("a real address that merely contains the test address is not a test account")
    void partialMatchIsNotATestAccount() {
        TestAccountPolicy policy = new TestAccountPolicy(PATTERN);
        assertThat(policy.isTestAccount("x.signup.canary@example.test")).isFalse();
        assertThat(policy.isTestAccount("signup.canary@example.test.evil.io")).isFalse();
        assertThat(policy.isTestAccount(null)).isFalse();
    }

    @Test
    @DisplayName("prod's two-family pattern: each family matches, the owner's REAL address never does")
    void prodPatternSparesTheRealAccount() {
        TestAccountPolicy policy = new TestAccountPolicy(
                "signup\\.canary(\\+[a-z0-9-]+)?@example\\.test|owner\\.name\\+lctest[a-z0-9-]*@example\\.test");
        assertThat(policy.isTestAccount("signup.canary@example.test")).isTrue();
        assertThat(policy.isTestAccount("owner.name+lctest7@example.test")).isTrue();
        // The alternation must not let either branch match a mere part of the address.
        assertThat(policy.isTestAccount("owner.name@example.test")).isFalse();
        assertThat(policy.isTestAccount("owner.name+other@example.test")).isFalse();
        assertThat(policy.isTestAccount("xsignup.canary@example.test")).isFalse();
    }

    @Test
    @DisplayName("a pattern broad enough to match an ordinary address is refused at startup")
    void overlyBroadPatternIsRefused() {
        assertThatThrownBy(() -> new TestAccountPolicy(".*"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("real accounts");
        assertThatThrownBy(() -> new TestAccountPolicy(".*@gmail\\.com"))
                .isInstanceOf(IllegalStateException.class);
        // Every plus-address, and a whole other provider: the slips the first guard let through.
        assertThatThrownBy(() -> new TestAccountPolicy(".*\\+.*@gmail\\.com"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TestAccountPolicy(".*@yahoo\\.com"))
                .isInstanceOf(IllegalStateException.class);
    }
}
