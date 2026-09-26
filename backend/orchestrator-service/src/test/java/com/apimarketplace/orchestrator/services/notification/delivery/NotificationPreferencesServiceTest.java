package com.apimarketplace.orchestrator.services.notification.delivery;

import com.apimarketplace.orchestrator.controllers.notification.NotificationPreferencesController;
import com.apimarketplace.orchestrator.services.channel.ChatChannelService;
import com.apimarketplace.orchestrator.services.channel.ChatChannelService.ChatChannelSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("NotificationPreferencesService + controller - the settings screen's view")
class NotificationPreferencesServiceTest {

    private static final String TENANT = "42";
    private static final String ORG = "org-1";

    private NotificationPreferenceStore store;
    private NotificationEmailEntitlement entitlement;
    private ChatChannelService channels;
    private NotificationPreferencesService service;

    @BeforeEach
    void setUp() {
        store = mock(NotificationPreferenceStore.class);
        entitlement = mock(NotificationEmailEntitlement.class);
        channels = mock(ChatChannelService.class);
        service = new NotificationPreferencesService(store, entitlement, channels);
        Map<NotificationTopic, DeliveryMode> modes = new EnumMap<>(NotificationTopic.class);
        for (NotificationTopic t : NotificationTopic.values()) modes.put(t, t.defaultDelivery());
        when(store.resolveAll(TENANT, ORG)).thenReturn(modes);
        when(channels.list(ORG)).thenReturn(List.of());
    }

    private static ChatChannelSummary summary(boolean isDefault, boolean active, String title) {
        return new ChatChannelSummary(UUID.randomUUID(), "slack", 1L, "bot", "C123", title, "channel",
                isDefault, active, null, null, null, null, List.of());
    }

    @Test
    @DisplayName("On a plan without email, only the credit topic says email is available")
    void freePlanView() {
        when(entitlement.requiredPlan(TENANT)).thenReturn("STARTER");

        NotificationPreferencesService.PreferencesView view = service.view(TENANT, ORG);

        assertThat(view.emailRequiredPlan()).isEqualTo("STARTER");
        assertThat(view.topics()).extracting(NotificationPreferencesService.TopicView::topic,
                        NotificationPreferencesService.TopicView::emailAvailable)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("FAILURES", false),
                        org.assertj.core.groups.Tuple.tuple("CREDITS", true),
                        org.assertj.core.groups.Tuple.tuple("ACCOUNT", false),
                        org.assertj.core.groups.Tuple.tuple("TASKS", false));
    }

    @Test
    @DisplayName("The channel shown is the ACTIVE default, the one alerts are really sent to")
    void channelIsActiveDefault() {
        when(channels.list(ORG)).thenReturn(List.of(summary(false, true, "#random"),
                summary(true, false, "#dead"), summary(true, true, "#ops")));

        NotificationPreferencesService.ChannelView channel = service.view(TENANT, ORG).channel();

        assertThat(channel.connected()).isTrue();
        assertThat(channel.title()).isEqualTo("#ops");
    }

    @Test
    @DisplayName("No active default: connected=false, so the screen disables the channel options")
    void noChannel() {
        when(channels.list(ORG)).thenReturn(List.of(summary(false, true, "#random")));

        assertThat(service.view(TENANT, ORG).channel().connected()).isFalse();
    }

    @Test
    @DisplayName("An unknown topic or delivery is refused with a sentence, and nothing is saved")
    void invalidRefused() {
        assertThatThrownBy(() -> service.update(TENANT, ORG, "MARKETING", "EMAIL"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("MARKETING");
        assertThatThrownBy(() -> service.update(TENANT, ORG, "FAILURES", "PUSH"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("OFF, EMAIL, CHANNEL or BOTH");
        verify(store, never()).save(any(), any(), any(), any());
    }

    @Test
    @DisplayName("A valid change is saved for THIS person in THIS workspace")
    void saved() {
        service.update(TENANT, ORG, "failures", "channel");

        verify(store).save(TENANT, ORG, NotificationTopic.FAILURES, DeliveryMode.CHANNEL);
    }

    @Test
    @DisplayName("Controller: no active workspace is a 400, a bad value is a 400 with the sentence")
    void controllerRefusals() {
        NotificationPreferencesController controller = new NotificationPreferencesController(service);

        assertThat(controller.get(TENANT, null).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.update(TENANT, " ", new NotificationPreferencesController.UpdateRequest("FAILURES", "OFF"))
                .getStatusCode().value()).isEqualTo(400);
        assertThat(controller.update(TENANT, ORG, null).getStatusCode().value()).isEqualTo(400);
        ResponseEntity<?> bad = controller.update(TENANT, ORG,
                new NotificationPreferencesController.UpdateRequest("FAILURES", "PUSH"));
        assertThat(bad.getStatusCode().value()).isEqualTo(400);
        assertThat(bad.getBody().toString()).contains("OFF, EMAIL, CHANNEL or BOTH");
        assertThat(controller.get(TENANT, ORG).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("A saved choice reports notification_preference_changed with the previous choice and the email fact")
    void updateReportsTheChange() {
        com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter analytics =
                mock(com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.class);
        when(analytics.isActive()).thenReturn(true);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "analytics", analytics);
        when(entitlement.requiredPlan(TENANT)).thenReturn("STARTER");
        when(store.resolve(TENANT, ORG, NotificationTopic.FAILURES)).thenReturn(DeliveryMode.BOTH);

        service.update(TENANT, ORG, "failures", "email");

        verify(store).save(TENANT, ORG, NotificationTopic.FAILURES, DeliveryMode.EMAIL);
        verify(analytics).notificationPreferenceChanged(TENANT, ORG, NotificationTopic.FAILURES,
                DeliveryMode.EMAIL, DeliveryMode.BOTH, false, false);
    }

    @Test
    @DisplayName("Re-saving the choice already in force is not a change: no notification_preference_changed")
    void sameChoiceIsNotReported() {
        com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter analytics =
                mock(com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.class);
        when(analytics.isActive()).thenReturn(true);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "analytics", analytics);
        when(store.resolve(TENANT, ORG, NotificationTopic.FAILURES)).thenReturn(DeliveryMode.EMAIL);

        service.update(TENANT, ORG, "failures", "email");

        verify(store).save(TENANT, ORG, NotificationTopic.FAILURES, DeliveryMode.EMAIL);
        verify(analytics, never()).notificationPreferenceChanged(any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("With analytics inactive the previous choice is not even read, and a refused update reports nothing")
    void inactiveAnalyticsReadsNothingExtra() {
        com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter analytics =
                mock(com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "analytics", analytics);

        service.update(TENANT, ORG, "credits", "both");
        assertThatThrownBy(() -> service.update(TENANT, ORG, "nope", "both"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(store, never()).resolve(any(), any(), any());
        verify(analytics).notificationPreferenceChanged(TENANT, ORG, NotificationTopic.CREDITS,
                DeliveryMode.BOTH, null, true, true);
    }
}
