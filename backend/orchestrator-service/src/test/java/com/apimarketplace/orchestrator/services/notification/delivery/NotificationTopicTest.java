package com.apimarketplace.orchestrator.services.notification.delivery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NotificationTopic - which bell categories may leave the app, and how")
class NotificationTopicTest {

    @Test
    @DisplayName("Approvals, agent questions, invitations and trophies never map to a topic (they have their own path)")
    void interactiveAndSocialCategoriesExcluded() {
        for (String category : List.of("APPROVAL_PENDING", "AGENT_AUTHORIZATION_UNREACHABLE",
                "ORG_INVITATION_PENDING", "BADGE_UNLOCKED", "BRIDGE_LOW_CREDIT")) {
            assertThat(NotificationTopic.ofCategory(category)).as(category).isEmpty();
        }
    }

    @Test
    @DisplayName("Failures and credits are sent at once; account and tasks only in the daily summary")
    void immediateVersusDigest() {
        assertThat(NotificationTopic.FAILURES.isDigest()).isFalse();
        assertThat(NotificationTopic.CREDITS.isDigest()).isFalse();
        assertThat(NotificationTopic.ACCOUNT.isDigest()).isTrue();
        assertThat(NotificationTopic.TASKS.isDigest()).isTrue();
        assertThat(NotificationTopic.digestCategories()).containsExactlyInAnyOrder("CRED_EXPIRED",
                "WEBHOOK_TRIGGER_DISABLED", "AGENT_TASK_ASSIGNED", "AGENT_TASK_MENTION", "AGENT_TASK_AWAITING_REVIEW");
    }

    @Test
    @DisplayName("Tasks default to email only: the workspace channel is shared, an assignment is personal")
    void tasksDefaultToEmail() {
        assertThat(NotificationTopic.TASKS.defaultDelivery()).isEqualTo(DeliveryMode.EMAIL);
        assertThat(NotificationTopic.FAILURES.defaultDelivery()).isEqualTo(DeliveryMode.BOTH);
    }

    @Test
    @DisplayName("No category belongs to two topics")
    void categoriesAreDisjoint() {
        Set<String> seen = new HashSet<>();
        for (NotificationTopic t : NotificationTopic.values()) {
            for (String c : t.categories()) {
                assertThat(seen.add(c)).as("category %s listed twice", c).isTrue();
            }
        }
    }

    @Test
    @DisplayName("Parsing is case-insensitive and refuses unknown values")
    void parsing() {
        assertThat(NotificationTopic.parse("credits")).contains(NotificationTopic.CREDITS);
        assertThat(NotificationTopic.parse("PUSH")).isEmpty();
        assertThat(DeliveryMode.parse(" both ")).contains(DeliveryMode.BOTH);
        assertThat(DeliveryMode.BOTH.wantsEmail() && DeliveryMode.BOTH.wantsChannel()).isTrue();
        assertThat(DeliveryMode.OFF.wantsEmail() || DeliveryMode.OFF.wantsChannel()).isFalse();
    }
}
