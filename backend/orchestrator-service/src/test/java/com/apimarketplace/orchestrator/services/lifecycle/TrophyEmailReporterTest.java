package com.apimarketplace.orchestrator.services.lifecycle;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.common.web.AppEditionProvider;
import com.apimarketplace.orchestrator.services.badge.BadgeCatalog;
import com.apimarketplace.orchestrator.services.badge.BadgeDefinition;
import com.apimarketplace.orchestrator.services.badge.BadgeTier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("TrophyEmailReporter - three kinds of trophy email, one per pass, never twice")
class TrophyEmailReporterTest {

    private static final Executor SAME_THREAD = Runnable::run;
    private static final String TENANT = "42";

    private final AuthClient authClient = mock(AuthClient.class);

    private static AppEditionProvider edition(boolean selfHosted) {
        AppEditionProvider p = mock(AppEditionProvider.class);
        when(p.isSelfHosted()).thenReturn(selfHosted);
        return p;
    }

    private static List<BadgeDefinition> badges(String... codes) {
        return Arrays.stream(codes).map(BadgeCatalog::byCode).toList();
    }

    private static Optional<TrophyEmailReporter.TrophyEmail> select(List<BadgeDefinition> now, String... held) {
        return TrophyEmailReporter.select(now, Set.of(held));
    }

    @AfterEach
    void clearSync() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // --- The three rules ---

    @Test
    @DisplayName("popularity: any tier of the POPULARITY family, bronze included")
    void popularityAnyTier() {
        Optional<TrophyEmailReporter.TrophyEmail> email = select(badges("popularity_1"));

        assertThat(email).isPresent();
        assertThat(email.get().kind()).isEqualTo("popularity");
        assertThat(email.get().payload()).isEqualTo(Map.of("kind", "popularity", "badge_code", "popularity_1",
                "tier", "BRONZE", "family", "POPULARITY"));
    }

    @Test
    @DisplayName("first_publication: the first PUBLISHER badge when none was held before")
    void firstPublication() {
        assertThat(select(badges("publisher_1")).map(TrophyEmailReporter.TrophyEmail::kind))
                .contains("first_publication");
    }

    @Test
    @DisplayName("a later publisher badge (silver) is NOT a first publication and not a top tier: no email")
    void laterPublisherBadgeIsNoEmail() {
        assertThat(select(badges("publisher_3"), "publisher_1")).isEmpty();
    }

    @Test
    @DisplayName("first_publication picks the LOWEST publisher badge when a pass unlocks several")
    void firstPublicationPicksTheFirstBadge() {
        Optional<TrophyEmailReporter.TrophyEmail> email = select(badges("publisher_1", "publisher_3"));

        assertThat(email.get().kind()).isEqualTo("first_publication");
        assertThat(email.get().badge().code()).isEqualTo("publisher_1");
    }

    @Test
    @DisplayName("top_tier: a GOLD or a PLATINUM badge of any other family")
    void goldAndPlatinum() {
        assertThat(select(badges("builder_50")).map(e -> e.kind() + ":" + e.badge().tier()))
                .contains("top_tier:GOLD");
        assertThat(select(badges("operator_50000"), "operator_5000").map(e -> e.kind() + ":" + e.badge().tier()))
                .contains("top_tier:PLATINUM");
        // A later GOLD publisher badge is a top tier, not a second "first publication".
        assertThat(select(badges("publisher_25"), "publisher_1", "publisher_3").map(TrophyEmailReporter.TrophyEmail::kind))
                .contains("top_tier");
    }

    @Test
    @DisplayName("bronze, silver and DIAMOND badges outside the two families send nothing (the founder badge is diamond)")
    void otherTiersSendNothing() {
        assertThat(select(badges("builder_1", "builder_5", "builder_10"))).isEmpty();
        assertThat(select(badges("founder_2026"))).isEmpty();
        assertThat(select(badges("builder_300"), "builder_100")).isEmpty();
        assertThat(BadgeCatalog.byCode("founder_2026").tier()).isEqualTo(BadgeTier.DIAMOND);
    }

    @Test
    @DisplayName("one email per pass: popularity beats first publication beats top tier, then the highest tier")
    void onePerPassByPriority() {
        assertThat(select(badges("builder_50", "publisher_1", "popularity_1")).get().kind()).isEqualTo("popularity");
        assertThat(select(badges("builder_50", "publisher_1")).get().kind()).isEqualTo("first_publication");
        assertThat(select(badges("builder_50", "operator_50000"), "operator_5000").get().badge().code())
                .isEqualTo("operator_50000");
        assertThat(select(badges("popularity_1", "popularity_10")).get().badge().code()).isEqualTo("popularity_10");
    }

    @Test
    @DisplayName("an empty pass sends nothing")
    void emptyPass() {
        assertThat(select(List.of())).isEmpty();
    }

    // --- Delivery ---

    @Test
    @DisplayName("the chosen email is sent as badge.unlocked with {kind, badge_code, tier, family}")
    void sendsTheEvent() {
        when(authClient.emitLifecycleEvent(anyString(), anyString(), anyMap()))
                .thenReturn(AuthClient.LifecycleEventResult.ACCEPTED);
        new TrophyEmailReporter(authClient, SAME_THREAD, edition(false))
                .badgesUnlocked(TENANT, badges("popularity_10"), Set.of("popularity_1"));

        verify(authClient).emitLifecycleEvent(TENANT, "badge.unlocked",
                Map.of("kind", "popularity", "badge_code", "popularity_10", "tier", "SILVER", "family", "POPULARITY"));
    }

    @Test
    @DisplayName("a pass worth no email makes no call")
    void nothingWorthAnEmail() {
        new TrophyEmailReporter(authClient, SAME_THREAD, edition(false))
                .badgesUnlocked(TENANT, badges("builder_1"), Set.of());

        verifyNoInteractions(authClient);
    }

    @Test
    @DisplayName("a self-hosted edition (CE) sends no trophy email")
    void selfHostedIsNoOp() {
        new TrophyEmailReporter(authClient, SAME_THREAD, edition(true))
                .badgesUnlocked(TENANT, badges("popularity_1"), Set.of());

        verifyNoInteractions(authClient);
    }

    @Test
    @DisplayName("inside a transaction the call waits for the commit; a rollback sends nothing")
    void afterCommitOnly() {
        TransactionSynchronizationManager.initSynchronization();
        TrophyEmailReporter reporter = new TrophyEmailReporter(authClient, SAME_THREAD, edition(false));

        reporter.badgesUnlocked(TENANT, badges("popularity_1"), Set.of());
        verify(authClient, never()).emitLifecycleEvent(anyString(), anyString(), anyMap());
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
        verify(authClient, never()).emitLifecycleEvent(anyString(), anyString(), anyMap());
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCommit();
        }
        verify(authClient).emitLifecycleEvent(eq(TENANT), eq("badge.unlocked"), anyMap());
    }

    @Test
    @DisplayName("auth-service down, a full queue or a throwing client never reaches the unlock")
    void failuresNeverThrow() {
        when(authClient.emitLifecycleEvent(anyString(), anyString(), any()))
                .thenReturn(AuthClient.LifecycleEventResult.RETRY_LATER);
        assertThatCode(() -> new TrophyEmailReporter(authClient, SAME_THREAD, edition(false))
                .badgesUnlocked(TENANT, badges("popularity_1"), Set.of())).doesNotThrowAnyException();

        Executor full = task -> {
            throw new RejectedExecutionException("full");
        };
        assertThatCode(() -> new TrophyEmailReporter(authClient, full, edition(false))
                .badgesUnlocked(TENANT, badges("popularity_1"), Set.of())).doesNotThrowAnyException();

        Executor broken = task -> {
            throw new IllegalStateException("boom");
        };
        assertThatCode(() -> new TrophyEmailReporter(authClient, broken, edition(false))
                .badgesUnlocked(TENANT, badges("popularity_1"), Set.of())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a blank tenant makes no call")
    void blankTenant() {
        new TrophyEmailReporter(authClient, SAME_THREAD, edition(false)).badgesUnlocked(" ", badges("popularity_1"), Set.of());

        verifyNoInteractions(authClient);
    }
}
