package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentWidgetConfigEntity;
import com.apimarketplace.agent.repository.AgentWidgetConfigRepository;
import com.apimarketplace.agent.security.AgentTokenAtRestBackfill;
import com.apimarketplace.common.security.CredentialEncryptionService;
import com.apimarketplace.common.security.token.PlaintextTokenBackfill.TableSpec;
import com.apimarketplace.common.security.token.TokenAtRest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Widget token lookups after the 2026-09-17 change: keyed hash first, read-only plaintext
 * fallback for a pre-change row while the table may hold one, and the ACTIVE variant must
 * apply its filter on the fallback too (an inactive legacy widget must not embed).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentWidgetConfigService token lookups")
class AgentWidgetConfigServiceLookupTest {

    @BeforeAll
    static void installTokenAtRest() {
        TokenAtRest.install(new CredentialEncryptionService("test-password-123", "0123456789abcdef"));
    }

    @Mock AgentWidgetConfigRepository repository;
    private AgentWidgetConfigService service;

    @BeforeEach
    void setUp() {
        service = new AgentWidgetConfigService(repository);
        AgentTokenAtRestBackfill backfill = mock(AgentTokenAtRestBackfill.class);
        lenient().when(backfill.mayHaveLegacyRows(any())).thenReturn(true);
        lenient().when(backfill.findLegacy(any(TableSpec.class), anyString(), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Function<String, Optional<Object>> q = inv.getArgument(2);
            return q.apply(inv.getArgument(1));
        });
        ReflectionTestUtils.setField(service, "tokenBackfill", backfill);
    }

    private static AgentWidgetConfigEntity widget(boolean active) {
        AgentWidgetConfigEntity w = new AgentWidgetConfigEntity(UUID.randomUUID());
        w.setWidgetToken("wg_1");
        w.setIsActive(active);
        return w;
    }

    @Test
    @DisplayName("findByWidgetToken hits through the hash")
    void hitOnHash() {
        AgentWidgetConfigEntity w = widget(true);
        when(repository.findByWidgetTokenHash(TokenAtRest.hash("wg_1"))).thenReturn(Optional.of(w));

        assertThat(service.findByWidgetToken("wg_1")).contains(w);
        verify(repository, never()).findLegacyPlaintext(anyString());
    }

    @Test
    @DisplayName("findByWidgetToken falls back to the legacy plaintext row on a miss")
    void legacyFallback() {
        AgentWidgetConfigEntity w = widget(false);
        when(repository.findByWidgetTokenHash(anyString())).thenReturn(Optional.empty());
        when(repository.findLegacyPlaintext("wg_1")).thenReturn(Optional.of(w));

        assertThat(service.findByWidgetToken("wg_1")).contains(w);
    }

    @Test
    @DisplayName("findActiveByWidgetToken applies the active filter to the legacy fallback: an inactive legacy widget stays hidden")
    void activeFilterOnFallback() {
        when(repository.findByWidgetTokenHashAndIsActiveTrue(anyString())).thenReturn(Optional.empty());
        when(repository.findLegacyPlaintext("wg_1")).thenReturn(Optional.of(widget(false)));
        assertThat(service.findActiveByWidgetToken("wg_1")).isEmpty();

        when(repository.findLegacyPlaintext("wg_1")).thenReturn(Optional.of(widget(true)));
        assertThat(service.findActiveByWidgetToken("wg_1")).isPresent();
    }

    @Test
    @DisplayName("null and blank tokens resolve to nothing without a query")
    void blank() {
        assertThat(service.findByWidgetToken(null)).isEmpty();
        assertThat(service.findActiveByWidgetToken(" ")).isEmpty();
        verify(repository, never()).findByWidgetTokenHash(anyString());
    }
}
