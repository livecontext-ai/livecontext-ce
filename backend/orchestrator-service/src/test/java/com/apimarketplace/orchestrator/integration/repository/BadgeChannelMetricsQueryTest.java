package com.apimarketplace.orchestrator.integration.repository;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.common.security.CredentialEncryptionService;
import com.apimarketplace.common.security.token.TokenAtRest;
import com.apimarketplace.orchestrator.domain.channel.ChatAuthorizationRequestEntity;
import com.apimarketplace.orchestrator.domain.channel.ChatAuthorizationRequestEntity.RequestStatus;
import com.apimarketplace.orchestrator.domain.channel.ChatChannelBotEntity;
import com.apimarketplace.orchestrator.domain.channel.ChatChannelLinkEntity;
import com.apimarketplace.orchestrator.domain.execution.ApprovalChannelDeliveryEntity;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.services.badge.BadgeMetric;
import com.apimarketplace.orchestrator.services.badge.BadgeStats;
import com.apimarketplace.orchestrator.services.badge.BadgeStatsCollector;
import com.apimarketplace.publication.client.PublicationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The chat-channel trophy statement, run for real against rows that each test one rule.
 *
 * <p>{@code BadgeChannelMetricsTest} pins the statement's text with a mocked EntityManager; that
 * cannot catch a join on the wrong column or a tenant predicate on the wrong table. Here the
 * SHIPPED statement runs through a real persistence context over a data set in which every row that
 * must NOT count sits next to one that must, so each exclusion rule is proven by the total.
 */
@DataJpaIntegrationTest
@DisplayName("badge channel metrics - the statement over real rows")
class BadgeChannelMetricsQueryTest {

    private static final String TENANT = "42";
    private static final String OTHER = "77";
    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");

    @Autowired
    private TestEntityManager em;

    private BadgeStatsCollector collector;

    @BeforeEach
    void setUp() throws Exception {
        // Delivery tokens are hashed on persist, as in production.
        TokenAtRest.install(new CredentialEncryptionService("test-password-123", "0123456789abcdef"));
        collector = new BadgeStatsCollector(mock(PublicationClient.class), mock(AuthClient.class));
        Field field = BadgeStatsCollector.class.getDeclaredField("entityManager");
        field.setAccessible(true);
        field.set(collector, em.getEntityManager());
    }

    private ChatChannelBotEntity bot(String tenant, String channel) {
        ChatChannelBotEntity bot = new ChatChannelBotEntity();
        bot.setTenantId(tenant);
        bot.setOrganizationId(tenant);
        bot.setChannel(channel);
        bot.setCredentialId(9L);
        bot.setCreatedAt(NOW);
        bot.setUpdatedAt(NOW);
        return em.persist(bot);
    }

    private void link(String tenant, ChatChannelBotEntity bot, String chatId, boolean verified) {
        ChatChannelLinkEntity link = new ChatChannelLinkEntity();
        link.setTenantId(tenant);
        link.setOrganizationId(tenant);
        link.setBotId(bot.getId());
        link.setChatId(chatId);
        link.setVerifiedAt(verified ? NOW : null);
        em.persist(link);
    }

    private void request(String tenant, RequestStatus status, String decidedBy) {
        ChatAuthorizationRequestEntity row = new ChatAuthorizationRequestEntity();
        row.setTenantId(tenant);
        row.setOrganizationId(tenant);
        row.setLinkId(UUID.randomUUID());
        row.setChannel("telegram");
        row.setCredentialId(9L);
        row.setChatId("-100123");
        row.setCallbackToken(UUID.randomUUID().toString());
        row.setConversationId("conv-1");
        row.setGateKey("call-" + UUID.randomUUID());
        row.setRule("publish_post");
        row.setFingerprint(UUID.randomUUID().toString());
        row.setStatus(status);
        row.setDecidedBy(decidedBy);
        row.setExpiresAt(NOW.plusSeconds(3600));
        em.persist(row);
    }

    /** An approval wait of one of this tenant's runs, resolved by {@code resolvedBy} (null = still open). */
    private SignalWaitEntity approval(String resolvedBy) {
        SignalWaitEntity wait = new SignalWaitEntity();
        wait.setRunId("run-1");
        wait.setItemId("0");
        wait.setNodeId("core:approve_" + UUID.randomUUID());
        wait.setSignalType(SignalType.USER_APPROVAL);
        wait.setEpoch(1);
        wait.setCreatedAt(NOW);
        if (resolvedBy != null) {
            wait.setStatus(SignalWaitEntity.SignalWaitStatus.RESOLVED);
            wait.setResolvedBy(resolvedBy);
        }
        return em.persist(wait);
    }

    private void delivery(String tenant, SignalWaitEntity wait, String channel) {
        ApprovalChannelDeliveryEntity row = new ApprovalChannelDeliveryEntity();
        row.setSignalWaitId(wait.getId());
        row.setChannel(channel);
        row.setCallbackToken(UUID.randomUUID().toString());
        row.setTenantId(tenant);
        row.setRunId(wait.getRunId());
        row.setNodeId(wait.getNodeId());
        row.setEpoch(1);
        row.setStatus(ApprovalChannelDeliveryEntity.DeliveryStatus.SENT);
        row.setCreatedAt(NOW);
        em.persist(row);
    }

    private BadgeStats collect() {
        em.flush();
        return collector.collect(TENANT, Set.of(BadgeMetric.CHANNELS_CONNECTED));
    }

    @Test
    @DisplayName("connected = verified destinations of this user; services = distinct services among them")
    void connectedAndServices() {
        ChatChannelBotEntity telegram = bot(TENANT, "telegram");
        ChatChannelBotEntity slack = bot(TENANT, "slack");
        ChatChannelBotEntity discord = bot(OTHER, "discord");
        link(TENANT, telegram, "-1001", true);
        link(TENANT, telegram, "-1002", true);   // same service twice: 2 destinations, 1 service
        link(TENANT, slack, "C1", true);
        link(TENANT, slack, "C2", false);        // tried, never delivered: not connected
        link(OTHER, discord, "D1", true);        // someone else's

        BadgeStats stats = collect();

        assertThat(stats.get(BadgeMetric.CHANNELS_CONNECTED)).isEqualTo(3);
        assertThat(stats.get(BadgeMetric.CHANNEL_SERVICES)).isEqualTo(2);
    }

    @Test
    @DisplayName("agent requests count only when resolved from a chat, and only this user's")
    void agentRequests() {
        request(TENANT, RequestStatus.RESOLVED, "telegram:111");
        request(TENANT, RequestStatus.RESOLVED, null);           // no presser recorded
        request(TENANT, RequestStatus.EXPIRED, null);            // nobody answered
        request(TENANT, RequestStatus.SENT, null);               // still waiting
        request(OTHER, RequestStatus.RESOLVED, "telegram:222");  // someone else's

        assertThat(collect().get(BadgeMetric.REMOTE_DECISIONS)).isEqualTo(1);
    }

    @Test
    @DisplayName("an approval counts once, for the service that resolved it, never when decided in the app")
    void workflowApprovals() {
        SignalWaitEntity fromTelegram = approval("telegram:111");
        delivery(TENANT, fromTelegram, "telegram");

        // Sent to two services, decided on Slack: counted once, through the Slack delivery.
        SignalWaitEntity fromSlack = approval("slack:U1");
        delivery(TENANT, fromSlack, "telegram");
        delivery(TENANT, fromSlack, "slack");

        SignalWaitEntity inApp = approval("42");                 // clicked in the app
        delivery(TENANT, inApp, "telegram");
        SignalWaitEntity viaApi = approval("api");               // resolved through the API
        delivery(TENANT, viaApi, "telegram");
        SignalWaitEntity open = approval(null);                  // not decided yet
        delivery(TENANT, open, "telegram");
        SignalWaitEntity others = approval("telegram:333");      // another user's run
        delivery(OTHER, others, "telegram");

        assertThat(collect().get(BadgeMetric.REMOTE_DECISIONS)).isEqualTo(2);
    }

    @Test
    @DisplayName("remote decisions add both sources, and nothing at all reads as zero")
    void sumAndEmpty() {
        assertThat(collect().get(BadgeMetric.REMOTE_DECISIONS)).isZero();

        request(TENANT, RequestStatus.RESOLVED, "slack:U9");
        delivery(TENANT, approval("discord:55"), "discord");

        BadgeStats stats = collect();
        assertThat(stats.get(BadgeMetric.REMOTE_DECISIONS)).isEqualTo(2);
        assertThat(stats.get(BadgeMetric.CHANNELS_CONNECTED)).isZero();
    }
}
