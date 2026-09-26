package com.apimarketplace.publication.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RemoteMarketplaceConfig - cloud-link.pending-auth-ttl parsing")
class RemoteMarketplaceConfigPendingAuthTtlTest {

    @Test
    @DisplayName("Simple (2h, 90m) and ISO-8601 (PT45M) forms are accepted")
    void acceptsSimpleAndIsoForms() {
        assertThat(RemoteMarketplaceConfig.parsePendingAuthTtl("2h")).isEqualTo(Duration.ofHours(2));
        assertThat(RemoteMarketplaceConfig.parsePendingAuthTtl(" 90m ")).isEqualTo(Duration.ofMinutes(90));
        assertThat(RemoteMarketplaceConfig.parsePendingAuthTtl("PT45M")).isEqualTo(Duration.ofMinutes(45));
    }

    @Test
    @DisplayName("Blank or unparseable values yield null (the service's 2h default) instead of failing boot")
    void blankOrInvalidFallsBackToServiceDefault() {
        assertThat(RemoteMarketplaceConfig.parsePendingAuthTtl(null)).isNull();
        assertThat(RemoteMarketplaceConfig.parsePendingAuthTtl("  ")).isNull();
        assertThat(RemoteMarketplaceConfig.parsePendingAuthTtl("two hours")).isNull();
    }
}
