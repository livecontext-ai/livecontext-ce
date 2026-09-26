package com.apimarketplace.auth.lifecycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("ExternalLifecycleEventService - allow-list, validation, localized payload")
class ExternalLifecycleEventServiceTest {

    private final LifecycleEmailService emails = mock(LifecycleEmailService.class);
    private final ExternalLifecycleEventService service = new ExternalLifecycleEventService(emails, new LifecycleLabels());

    @SuppressWarnings("unchecked")
    private Function<String, Map<String, Object>> captured(String event, boolean bulk) {
        ArgumentCaptor<Function<String, Map<String, Object>>> fn = ArgumentCaptor.forClass(Function.class);
        verify(emails).submitLocalized(eq(7L), eq(event), fn.capture(), eq(bulk));
        return fn.getValue();
    }

    private static Map<String, Object> badge(String kind, String code, String tier) {
        return badge(kind, code, tier, "BUILDER");
    }

    private static Map<String, Object> badge(String kind, String code, String tier, String family) {
        Map<String, Object> m = new HashMap<>();
        m.put("kind", kind);
        m.put("badge_code", code);
        m.put("tier", tier);
        m.put("family", family);
        return m;
    }

    private static Map<String, Object> recap(Object month, Object runs, Object workflows, Object badges) {
        Map<String, Object> m = new HashMap<>();
        m.put("month", month);
        m.put("runs", runs);
        m.put("active_workflows", workflows);
        m.put("badges", badges);
        return m;
    }

    @Test
    @DisplayName("badge.unlocked: the payload carries the trophy and tier names in the recipient's language")
    void badgeUnlockedLocalized() {
        when(emails.submitLocalized(anyLong(), anyString(), any(), anyBoolean()))
                .thenReturn(LifecycleEmailService.Dispatch.QUEUED);

        LifecycleEmailService.Dispatch d = service.accept(7L, "badge.unlocked", badge("top_tier", "builder_50", "GOLD"));

        assertThat(d).isEqualTo(LifecycleEmailService.Dispatch.QUEUED);
        Function<String, Map<String, Object>> payload = captured("badge.unlocked", false);
        assertThat(payload.apply("fr")).containsExactly(
                Map.entry("kind", "top_tier"), Map.entry("badge_code", "builder_50"), Map.entry("tier", "GOLD"),
                Map.entry("family", "BUILDER"), Map.entry("badge_name", "Architecte de l'automatisation"),
                Map.entry("tier_name", "Or"), Map.entry("family_name", "Bâtisseur"));
        assertThat(payload.apply("en")).containsEntry("badge_name", "Automation Architect").containsEntry("tier_name", "Gold")
                .containsEntry("family_name", "Builder");
    }

    @Test
    @DisplayName("recap.monthly: month label and counts are localized, and the send is BULK (yields the queue)")
    void recapLocalizedAndBulk() {
        service.accept(7L, "recap.monthly", recap("2026-08", 1234L, 3, 0));

        Function<String, Map<String, Object>> payload = captured("recap.monthly", true);
        assertThat(payload.apply("fr")).containsEntry("month_label", "août 2026").containsEntry("active_workflows", "3")
                .containsEntry("badges", "0");
        assertThat(payload.apply("en")).containsExactly(Map.entry("month_label", "August 2026"),
                Map.entry("runs", "1,234"), Map.entry("active_workflows", "3"), Map.entry("badges", "0"));
        assertThat(payload.apply("zh")).containsEntry("month_label", "2026年8月");
    }

    @Test
    @DisplayName("anything off the allow-list is refused, the credits events included")
    void offListRefused() {
        for (String event : new String[]{"credits.exhausted", "credits.added", "user.signed_up", "checkout.started", null}) {
            assertThatThrownBy(() -> service.accept(7L, event, Map.of())).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(emails);
    }

    @Test
    @DisplayName("badge.unlocked with an unknown kind, badge, tier or family is refused")
    void badPayloadRefused() {
        assertThatThrownBy(() -> service.accept(7L, "badge.unlocked", badge("bronze_party", "builder_50", "GOLD")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.accept(7L, "badge.unlocked", badge("top_tier", "builder_51", "GOLD")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.accept(7L, "badge.unlocked", badge("top_tier", "builder_50", "gold")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.accept(7L, "badge.unlocked", badge("top_tier", "builder_50", "GOLD", "WIZARD")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.accept(7L, "badge.unlocked", null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(emails);
    }

    @Test
    @DisplayName("recap.monthly with a bad month or a missing, fractional or negative count is refused")
    void badRecapRefused() {
        assertThatThrownBy(() -> service.accept(7L, "recap.monthly", recap("August", 1, 1, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.accept(7L, "recap.monthly", recap(null, 1, 1, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.accept(7L, "recap.monthly", recap("2026-08", null, 1, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.accept(7L, "recap.monthly", recap("2026-08", 1.5, 1, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.accept(7L, "recap.monthly", recap("2026-08", 1, -1, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.accept(7L, "recap.monthly", recap("2026-08", 1, 1, "2")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(emails);
    }

    @Test
    @DisplayName("the queue's answer is passed through (BUSY lets the sender retry later)")
    void dispatchPassedThrough() {
        when(emails.submitLocalized(anyLong(), anyString(), any(), anyBoolean()))
                .thenReturn(LifecycleEmailService.Dispatch.BUSY);

        assertThat(service.accept(7L, "recap.monthly", recap("2026-08", 1, 1, 0)))
                .isEqualTo(LifecycleEmailService.Dispatch.BUSY);
    }
}
