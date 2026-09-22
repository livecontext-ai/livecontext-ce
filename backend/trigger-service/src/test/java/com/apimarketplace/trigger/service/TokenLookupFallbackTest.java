package com.apimarketplace.trigger.service;

import com.apimarketplace.common.security.CredentialEncryptionService;
import com.apimarketplace.common.security.token.PlaintextTokenBackfill.TableSpec;
import com.apimarketplace.common.security.token.TokenAtRest;
import com.apimarketplace.trigger.domain.StandaloneChatEndpointEntity;
import com.apimarketplace.trigger.domain.StandaloneFormEndpointEntity;
import com.apimarketplace.trigger.domain.StandaloneWebhookEntity;
import com.apimarketplace.trigger.domain.WebhookTokenEntity;
import com.apimarketplace.trigger.repository.ChatEndpointAccessLogRepository;
import com.apimarketplace.trigger.repository.FormSubmissionLogRepository;
import com.apimarketplace.trigger.repository.StandaloneChatEndpointRepository;
import com.apimarketplace.trigger.repository.StandaloneFormEndpointRepository;
import com.apimarketplace.trigger.repository.StandaloneWebhookRepository;
import com.apimarketplace.trigger.repository.WebhookCallLogRepository;
import com.apimarketplace.trigger.repository.WebhookTokenRepository;
import com.apimarketplace.trigger.security.TriggerTokenAtRestBackfill;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The four trigger token lookups after the 2026-09-17 change: a hit goes through the keyed hash,
 * a miss consults the read-only plaintext fallback only while the table may still hold legacy
 * rows, and a service built without the backfill bean (this very test) never NPEs.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Trigger token lookups: hash first, read-only legacy fallback second")
class TokenLookupFallbackTest {

    @BeforeAll
    static void installTokenAtRest() {
        TokenAtRest.install(new CredentialEncryptionService("test-password-123", "0123456789abcdef"));
    }

    @Mock StandaloneWebhookRepository webhookRepository;
    @Mock WebhookCallLogRepository callLogRepository;
    @Mock StandaloneChatEndpointRepository chatRepository;
    @Mock ChatEndpointAccessLogRepository chatLogRepository;
    @Mock StandaloneFormEndpointRepository formRepository;
    @Mock FormSubmissionLogRepository formLogRepository;
    @Mock WebhookTokenRepository webhookTokenRepository;
    @Mock PlanLimitHelper planLimitHelper;
    @Mock CredentialEncryptionService encryptionService;

    /** A backfill double that reports "legacy rows may remain" or "drained" and delegates like the real one. */
    private static TriggerTokenAtRestBackfill backfill(boolean mayHaveLegacyRows) {
        TriggerTokenAtRestBackfill b = mock(TriggerTokenAtRestBackfill.class);
        lenient().when(b.mayHaveLegacyRows(any())).thenReturn(mayHaveLegacyRows);
        lenient().when(b.findLegacy(any(TableSpec.class), anyString(), any())).thenAnswer(inv -> {
            if (!mayHaveLegacyRows) return Optional.empty();
            @SuppressWarnings("unchecked")
            Function<String, Optional<Object>> q = inv.getArgument(2);
            return q.apply(inv.getArgument(1));
        });
        return b;
    }

    @Nested
    @DisplayName("StandaloneWebhookService.findByToken")
    class Webhook {
        private StandaloneWebhookService service() {
            return new StandaloneWebhookService(webhookRepository, callLogRepository, encryptionService, planLimitHelper);
        }

        @Test
        @DisplayName("hit: queries the keyed hash, never the plaintext")
        void hitOnHash() {
            StandaloneWebhookEntity e = new StandaloneWebhookEntity();
            when(webhookRepository.findByTokenHash(TokenAtRest.hash("wh_1"))).thenReturn(Optional.of(e));
            StandaloneWebhookService s = service();
            ReflectionTestUtils.setField(s, "tokenBackfill", backfill(true));

            assertThat(s.findByToken("wh_1")).contains(e);
            verify(webhookRepository, never()).findLegacyPlaintext(anyString());
        }

        @Test
        @DisplayName("miss while legacy rows may remain: the native plaintext fallback resolves the pre-change row")
        void missFallsBackToLegacyPlaintext() {
            StandaloneWebhookEntity legacy = new StandaloneWebhookEntity();
            when(webhookRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());
            when(webhookRepository.findLegacyPlaintext("wh_legacy")).thenReturn(Optional.of(legacy));
            StandaloneWebhookService s = service();
            ReflectionTestUtils.setField(s, "tokenBackfill", backfill(true));

            assertThat(s.findByToken("wh_legacy")).contains(legacy);
        }

        @Test
        @DisplayName("miss on a drained table: no second query, the fallback is skipped")
        void drainedTableSkipsFallback() {
            when(webhookRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());
            StandaloneWebhookService s = service();
            ReflectionTestUtils.setField(s, "tokenBackfill", backfill(false));

            assertThat(s.findByToken("wh_gone")).isEmpty();
            verify(webhookRepository, never()).findLegacyPlaintext(anyString());
        }

        @Test
        @DisplayName("without a backfill bean (unit test wiring) a miss is just a miss")
        void noBackfillBean() {
            when(webhookRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());
            assertThat(service().findByToken("wh_x")).isEmpty();
            verify(webhookRepository, never()).findLegacyPlaintext(anyString());
        }
    }

    @Nested
    @DisplayName("chat / form endpoints and per-trigger webhook tokens")
    class Others {

        @Test
        @DisplayName("chat endpoint: hash hit maps to the DTO with the plaintext token")
        void chatHit() {
            StandaloneChatEndpointEntity e = new StandaloneChatEndpointEntity();
            e.setId(UUID.randomUUID());
            e.setToken("ch_1");
            e.setName("n");
            when(chatRepository.findByTokenHash(TokenAtRest.hash("ch_1"))).thenReturn(Optional.of(e));
            StandaloneChatEndpointService s = new StandaloneChatEndpointService(chatRepository, chatLogRepository, planLimitHelper);

            assertThat(s.findByToken("ch_1")).isPresent().get().extracting("token").isEqualTo("ch_1");
        }

        @Test
        @DisplayName("form endpoint: legacy fallback consulted on a miss")
        void formLegacy() {
            StandaloneFormEndpointEntity e = new StandaloneFormEndpointEntity();
            e.setId(UUID.randomUUID());
            e.setToken("fm_legacy");
            e.setName("n");
            when(formRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());
            when(formRepository.findLegacyPlaintext("fm_legacy")).thenReturn(Optional.of(e));
            StandaloneFormEndpointService s = new StandaloneFormEndpointService(formRepository, formLogRepository, planLimitHelper);
            ReflectionTestUtils.setField(s, "tokenBackfill", backfill(true));

            assertThat(s.findByToken("fm_legacy")).isPresent().get().extracting("token").isEqualTo("fm_legacy");
        }

        @Test
        @DisplayName("workflow webhook token: hash lookup, blank token short-circuits")
        void webhookTokenService() {
            WebhookTokenEntity e = new WebhookTokenEntity();
            when(webhookTokenRepository.findByTokenHash(eq(TokenAtRest.hash("wh_wf")))).thenReturn(Optional.of(e));
            WebhookTokenService s = new WebhookTokenService(webhookTokenRepository);

            assertThat(s.findByToken("wh_wf")).contains(e);
            assertThat(s.findByToken(" ")).isEmpty();
            assertThat(s.findByToken(null)).isEmpty();
        }
    }
}
