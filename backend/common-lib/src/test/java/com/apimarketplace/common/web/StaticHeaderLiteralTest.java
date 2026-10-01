package com.apimarketplace.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StaticHeaderLiteral - the one static-header rule both registration routes share")
class StaticHeaderLiteralTest {

    @Test
    @DisplayName("REGRESSION MCP 406: the media-range list 'application/json, text/event-stream' is a literal")
    void mediaRangeListIsLiteral() {
        assertThat(StaticHeaderLiteral.isLiteral("application/json, text/event-stream")).isTrue();
    }

    @Test
    @DisplayName("the media-type parameter form and plain literals are accepted")
    void existingLiteralsAccepted() {
        assertThat(StaticHeaderLiteral.isLiteral("application/vnd.heroku+json; version=3")).isTrue();
        assertThat(StaticHeaderLiteral.isLiteral("2023-06-01")).isTrue();
    }

    @Test
    @DisplayName("prose, templates, blanks, over-long values and badly spaced lists are refused")
    void nonLiteralsRefused() {
        assertThat(StaticHeaderLiteral.isLiteral("Required if machine is leased")).isFalse();
        assertThat(StaticHeaderLiteral.isLiteral("Bearer {token}")).isFalse();
        assertThat(StaticHeaderLiteral.isLiteral("{{api_key}}")).isFalse();
        assertThat(StaticHeaderLiteral.isLiteral("a,  b")).isFalse();
        assertThat(StaticHeaderLiteral.isLiteral("a , b")).isFalse();
        assertThat(StaticHeaderLiteral.isLiteral("application/json ; version=1")).isFalse();
        assertThat(StaticHeaderLiteral.isLiteral("   ")).isFalse();
        assertThat(StaticHeaderLiteral.isLiteral(null)).isFalse();
        assertThat(StaticHeaderLiteral.isLiteral("x".repeat(StaticHeaderLiteral.MAX_LENGTH + 1))).isFalse();
    }
}
