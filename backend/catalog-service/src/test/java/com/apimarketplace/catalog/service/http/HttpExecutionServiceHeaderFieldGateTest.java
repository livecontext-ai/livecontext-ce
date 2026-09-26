package com.apimarketplace.catalog.service.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate deciding whether a variant's declared header fields still need sending.
 *
 * <p>It was `headerFields >= 2`, on the reasoning that a lone header field is already sent
 * as the primary injection. That holds only while the primary IS a header. When the primary
 * slot goes to a query or url_variable field, the lone header is sent by nobody: Ghost is
 * the proof, its admin_api_key sits behind a url_variable primary and production shows
 * ghost-list-posts-admin failing 9 calls out of 9. Firebase, Looker, Snowflake and Stream
 * Chat carry the same shape.
 *
 * <p>Both directions are silent failures, which is why every combination is pinned here:
 * too narrow and a credential header is never sent; too wide and the loop overwrites a
 * primary Authorization header carrying a prefix it knows nothing about.
 */
class HttpExecutionServiceHeaderFieldGateTest {

    @Test
    @DisplayName("a lone header behind a url_variable primary must be sent: nobody else sends it")
    void loneHeaderBehindUrlVariablePrimary() {
        assertThat(HttpExecutionService.shouldApplyHeaderFields("url_variable", 1)).isTrue();
    }

    @Test
    @DisplayName("a lone header behind a query primary must be sent")
    void loneHeaderBehindQueryPrimary() {
        assertThat(HttpExecutionService.shouldApplyHeaderFields("query", 1)).isTrue();
    }

    @Test
    @DisplayName("a lone header whose primary IS a header stays with the primary")
    void loneHeaderBehindHeaderPrimaryIsLeftAlone() {
        // The primary may carry a prefix ("Bearer ", "Token ") that this loop does not know,
        // so re-setting the same header from the raw field value would strip it.
        assertThat(HttpExecutionService.shouldApplyHeaderFields("header", 1)).isFalse();
    }

    @Test
    @DisplayName("two or more header fields behave exactly as before, whatever the primary is")
    void multipleHeaderFieldsUnchanged() {
        assertThat(HttpExecutionService.shouldApplyHeaderFields("header", 2)).isTrue();
        assertThat(HttpExecutionService.shouldApplyHeaderFields("query", 2)).isTrue();
        assertThat(HttpExecutionService.shouldApplyHeaderFields("url_variable", 3)).isTrue();
    }

    @Test
    @DisplayName("no header field declared is always a no-op")
    void noHeaderFields() {
        assertThat(HttpExecutionService.shouldApplyHeaderFields("header", 0)).isFalse();
        assertThat(HttpExecutionService.shouldApplyHeaderFields("query", 0)).isFalse();
        assertThat(HttpExecutionService.shouldApplyHeaderFields(null, 0)).isFalse();
    }

    @Test
    @DisplayName("a null or odd primary type does not exclude a lone header")
    void nullPrimaryTypeStillSendsTheLoneHeader() {
        // A row with no primary type recorded is exactly the case where nothing else will
        // send the header, so excluding it would reproduce the bug on the oldest rows.
        assertThat(HttpExecutionService.shouldApplyHeaderFields(null, 1)).isTrue();
        assertThat(HttpExecutionService.shouldApplyHeaderFields("body_field", 1)).isTrue();
    }

    @Test
    @DisplayName("the header primary check ignores case, as the metadata is free text")
    void primaryTypeComparisonIsCaseInsensitive() {
        assertThat(HttpExecutionService.shouldApplyHeaderFields("HEADER", 1)).isFalse();
    }
}
