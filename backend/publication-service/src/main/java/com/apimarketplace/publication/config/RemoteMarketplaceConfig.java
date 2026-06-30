package com.apimarketplace.publication.config;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.publication.repository.CeCloudLinkRepository;
import com.apimarketplace.publication.repository.PublicationReceiptRepository;
import com.apimarketplace.publication.service.AgentPublicationService;
import com.apimarketplace.publication.service.CloudLinkService;
import com.apimarketplace.publication.service.RemoteMarketplaceService;
import com.apimarketplace.publication.service.ResourcePublicationService;
import com.apimarketplace.publication.service.SnapshotCloneService;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    /** CE distribution version stamped on heartbeats + REGISTER audit metadata. */
    @Value("${ce.version:dev}")
    private String ceVersion;

    @Bean
    public CloudLinkService cloudLinkService(
            CeCloudLinkRepository cloudLinkRepository,
            ObjectMapper objectMapper) {
        return new CloudLinkService(
                cloudLinkRepository, keycloakUrl, clientId, redirectUri, encryptionKey,
                cloudApiUrl, ceVersion, objectMapper);
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
            OrchestratorInternalClient orchestratorClient) {
        return new RemoteMarketplaceService(
                cloudApiUrl, snapshotCloneService, receiptRepository, cloudLinkService, objectMapper, authClient,
                agentPublicationService, resourcePublicationService, orchestratorClient);
    }
}
