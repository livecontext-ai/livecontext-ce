package com.apimarketplace.publication.config;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.publication.repository.CeCloudLinkRepository;
import com.apimarketplace.publication.repository.PublicationReceiptRepository;
import com.apimarketplace.publication.service.AgentPublicationService;
import com.apimarketplace.publication.service.CloudLinkService;
import com.apimarketplace.publication.service.EditableWorkflowTwinService;
import com.apimarketplace.publication.service.RemoteMarketplaceService;
import com.apimarketplace.publication.service.ResourcePublicationService;
import com.apimarketplace.publication.service.SnapshotCloneService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for the CE remote marketplace feature.
 * Creates CloudLinkService and RemoteMarketplaceService beans.
 * Only active when marketplace.mode=remote (CE monolith).
 */
@Configuration
@ConditionalOnProperty(name = "marketplace.mode", havingValue = "remote")
public class RemoteMarketplaceConfig {

    @Value("${marketplace.cloud-api-url:https://livecontext.ai/api}")
    private String cloudApiUrl;

    @Value("${cloud-link.keycloak-url:https://auth.livecontext.ai/realms/livecontext}")
    private String keycloakUrl;

    @Value("${cloud-link.client-id:livecontext-frontend}")
    private String clientId;

    @Value("${cloud-link.redirect-uri:http://localhost:8080/api/cloud-link/callback}")
    private String redirectUri;

    @Value("${cloud-link.encryption-key:}")
    private String encryptionKey;

    /**
     * Cloud web app base the onboarding start URL is built on ({@code CLOUD_WEB_URL}). Blank =
     * derived from {@code marketplace.cloud-api-url} minus a trailing {@code /api}.
     */
    @Value("${cloud-link.web-url:}")
    private String webUrl;

    /**
     * Lifetime of a pending cloud-link OAuth flow. Long enough for a cloud signup, onboarding and
     * checkout. Accepts {@code 2h}, {@code 90m} or ISO-8601 ({@code PT2H}).
     */
    @Value("${cloud-link.pending-auth-ttl:2h}")
    private String pendingAuthTtl;

    /** CE distribution version stamped on heartbeats + REGISTER audit metadata. */
    @Value("${ce.version:dev}")
    private String ceVersion;

    @Bean
    public CloudLinkService cloudLinkService(
            CeCloudLinkRepository cloudLinkRepository,
            ObjectMapper objectMapper) {
        return new CloudLinkService(
                cloudLinkRepository, keycloakUrl, clientId, redirectUri, encryptionKey,
                cloudApiUrl, ceVersion, objectMapper, webUrl, parsePendingAuthTtl(pendingAuthTtl));
    }

    /** Null (the service's 2h default) when blank or unparseable, so a typo never breaks boot. */
    static java.time.Duration parsePendingAuthTtl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return org.springframework.boot.convert.DurationStyle.detectAndParse(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Bean
    public com.apimarketplace.publication.service.CeCloudLinkHeartbeatScheduler ceCloudLinkHeartbeatScheduler(
            CeCloudLinkRepository cloudLinkRepository,
            CloudLinkService cloudLinkService) {
        return new com.apimarketplace.publication.service.CeCloudLinkHeartbeatScheduler(
                cloudLinkRepository, cloudLinkService);
    }

    @Bean
    public RemoteMarketplaceService remoteMarketplaceService(
            SnapshotCloneService snapshotCloneService,
            PublicationReceiptRepository receiptRepository,
            CloudLinkService cloudLinkService,
            ObjectMapper objectMapper,
            AuthClient authClient,
            AgentPublicationService agentPublicationService,
            ResourcePublicationService resourcePublicationService,
            OrchestratorInternalClient orchestratorClient,
            ObjectProvider<EditableWorkflowTwinService> editableWorkflowTwinService) {
        return new RemoteMarketplaceService(
                cloudApiUrl, snapshotCloneService, receiptRepository, cloudLinkService, objectMapper, authClient,
                agentPublicationService, resourcePublicationService, orchestratorClient,
                // Backs the ON-DEMAND editable copy (and owns its WORKFLOW-quota check).
                // Acquire itself no longer needs it: it stopped minting a copy per install.
                editableWorkflowTwinService.getIfAvailable());
    }
}
