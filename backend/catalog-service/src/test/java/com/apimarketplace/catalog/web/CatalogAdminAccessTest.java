package com.apimarketplace.catalog.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CatalogAdminAccess")
class CatalogAdminAccessTest {

    private final CatalogAdminAccess access = new CatalogAdminAccess("s3cret");

    @Test
    @DisplayName("admits a caller whose gateway-injected roles contain ADMIN")
    void admitsAdminRole() {
        assertThat(access.isAdmin("USER,ADMIN", null)).isTrue();
        assertThat(access.denyIfNotAdmin("ADMIN", null)).isNull();
    }

    @Test
    @DisplayName("admits a caller presenting the configured admin token, whitespace-tolerant")
    void admitsMatchingToken() {
        assertThat(access.isAdmin(null, "s3cret")).isTrue();
        assertThat(access.isAdmin(null, " s3cret\n")).isTrue();
    }

    @Test
    @DisplayName("refuses a plain user, a missing token and a wrong token with 403")
    void refusesEveryoneElse() {
        assertThat(access.isAdmin("USER", null)).isFalse();
        assertThat(access.isAdmin(null, "")).isFalse();
        assertThat(access.isAdmin("USER", "s3cre")).isFalse();
        assertThat(access.denyIfNotAdmin("USER", "nope").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("a blank configured token disables the token path instead of opening it")
    void blankConfiguredTokenNeverMatches() {
        CatalogAdminAccess unconfigured = new CatalogAdminAccess("  ");

        assertThat(unconfigured.isAdmin(null, "")).isFalse();
        assertThat(unconfigured.isAdmin(null, "  ")).isFalse();
        assertThat(unconfigured.isAdmin(null, null)).isFalse();
        assertThat(new CatalogAdminAccess(null).isAdmin(null, null)).isFalse();
    }

    @Test
    @DisplayName("role matching is exact, so a role merely containing ADMIN is not enough")
    void roleMatchIsExact() {
        assertThat(access.isAdmin("SUPERADMIN,NOT_ADMIN", null)).isFalse();
    }
}
