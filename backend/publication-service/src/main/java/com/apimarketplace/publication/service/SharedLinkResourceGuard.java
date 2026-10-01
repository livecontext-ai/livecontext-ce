package com.apimarketplace.publication.service;

import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.conversation.client.ConversationClient;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.SharedLinkEntity;
import com.apimarketplace.publication.domain.SharedLinkEntity.ResourceType;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.dto.StandaloneChatEndpointDto;
import com.apimarketplace.trigger.client.dto.StandaloneFormEndpointDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Decides which resource a share link may name, at creation, and whether a stored APPLICATION
 * link still names a publication its owner holds, at every resolution.
 *
 * <p><b>Why this exists.</b> A share link binds a resource ({@code resourceToken}, plus an
 * optional {@code resourceId}) to the identity of whoever created it, and an APPLICATION link's
 * token then authenticates any visitor AS that creator ({@code MonolithSecurityFilter}, the
 * gateway's {@code AuthenticationFilter}) and drives the public {@code /app/public/{token}} API.
 * {@code POST /api/publications/shared-links} used to store whatever the caller named. A user
 * could therefore create a link naming ANOTHER user's publication id (every marketplace listing
 * shows one) and read through it what only the owner may read: the publication's raw plan
 * snapshot, past the PRIVATE / UNLISTED gate; the owner's application clone id; and, through
 * {@code /app/public}, the publisher's source workflow and application run. Naming someone
 * else's resource also squatted it: the registry keeps one active link per resource token and
 * per resource id, so the real owner could no longer share their own resource.
 *
 * <p><b>The rule, per link type</b> (what the Applications page, the Public access tabs and the
 * conversation sidebar already send, so a legitimate share is never refused):
 * <ul>
 *   <li>APPLICATION: {@code resourceToken} is a publication the caller is in the owner scope of
 *       ({@link WorkflowPublicationService#isInOwnerScope}, the rule every publication mutation
 *       uses); {@code resourceId}, when given, is the caller's own application clone of it (the
 *       id {@code GET /api/publications/{id}/application-workflow} returns to the owner).</li>
 *   <li>CHAT / FORM: {@code resourceToken} is a standalone chat / form endpoint of the caller's
 *       workspace; {@code resourceId}, when given, is that endpoint's id.</li>
 *   <li>CONVERSATION: {@code resourceToken} is the share token of a shared conversation of the
 *       caller's workspace; {@code resourceId}, when given, is that conversation's id.</li>
 * </ul>
 * Anything else is refused. A lookup that cannot run (the owning service is down, answers a
 * 5xx, times out) throws {@link ResourceLookupUnavailableException} for EVERY type: fail closed
 * (the link is never allowed), but never disguised as "not yours", which would tell a
 * legitimate owner their own resource is not theirs. The caller answers it 503.
 *
 * <p><b>Only the public creation path is checked.</b> {@code SharedLinkService#register} runs
 * this; {@code registerForOwningService} (behind {@code /api/internal/shared-links/register},
 * unroutable from the edge) does not: its callers are the owning services themselves,
 * registering the endpoint or conversation they have just created or opened, sometimes before
 * that row is committed, where a look-up from here could not see it yet.
 */
@Component
public class SharedLinkResourceGuard {

    /**
     * A chat, form or conversation token is sent to its owning service as a PATH SEGMENT, so it
     * must be exactly one plain segment. Every token the platform mints ({@code ch_}, {@code fm_},
     * {@code cs_} + 32 hex) matches; a {@code /}, {@code .}, {@code %} or {@code ?} would let a
     * caller steer the internal request to another path of that service.
     */
    private static final Pattern PATH_SAFE_TOKEN = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");

    private final WorkflowPublicationRepository publicationRepository;
    private final OrchestratorInternalClient orchestratorClient;
    private final TriggerClient triggerClient;
    private final ConversationClient conversationClient;

    @Autowired
    public SharedLinkResourceGuard(WorkflowPublicationRepository publicationRepository,
                                   @Value("${services.orchestrator-url:http://localhost:8099}") String orchestratorUrl,
                                   @Value("${services.trigger-url:http://localhost:8091}") String triggerUrl,
                                   @Value("${services.conversation-url:http://localhost:8087}") String conversationUrl) {
        // Private clients, not beans: the CE monolith already defines a TriggerClient and a
        // ConversationClient (orchestrator, agent-service), and a second bean of either type
        // would make their by-type injection ambiguous or silently replace theirs. Own
        // instances also get the bounded timeouts below, which the shared
        // OrchestratorInternalClient bean (untimed, for long captures) does not have.
        this(publicationRepository,
                new OrchestratorInternalClient(boundedRestTemplate(), orchestratorUrl),
                new TriggerClient(boundedRestTemplate(), triggerUrl),
                new ConversationClient(boundedRestTemplate(), conversationUrl));
    }

    /** With the collaborators given; what the Spring constructor builds, and what tests wire. */
    public SharedLinkResourceGuard(WorkflowPublicationRepository publicationRepository,
                                   OrchestratorInternalClient orchestratorClient,
                                   TriggerClient triggerClient,
                                   ConversationClient conversationClient) {
        this.publicationRepository = publicationRepository;
        this.orchestratorClient = orchestratorClient;
        this.triggerClient = triggerClient;
        this.conversationClient = conversationClient;
    }

    /**
     * Whether the caller may create a link of this type naming this resource (see the class
     * note for the rule).
     *
     * @throws ResourceLookupUnavailableException when the owning service could not be asked
     */
    public boolean mayShare(ResourceType type, String tenantId, String organizationId,
                            String resourceToken, UUID resourceId) {
        if (type == null || resourceToken == null || resourceToken.isBlank()) {
            return false;
        }
        try {
            return switch (type) {
                case APPLICATION -> mayShareApplication(tenantId, organizationId, resourceToken, resourceId);
                case CHAT -> PATH_SAFE_TOKEN.matcher(resourceToken).matches()
                        && mayShareChatEndpoint(tenantId, organizationId, resourceToken, resourceId);
                case FORM -> PATH_SAFE_TOKEN.matcher(resourceToken).matches()
                        && mayShareFormEndpoint(tenantId, organizationId, resourceToken, resourceId);
                case CONVERSATION -> PATH_SAFE_TOKEN.matcher(resourceToken).matches()
                        && mayShareConversation(tenantId, organizationId, resourceToken, resourceId);
            };
        } catch (ResourceLookupUnavailableException e) {
            throw e;
        } catch (RuntimeException lookupFailed) {
            // Every remote lookup reports a failure it could not answer by throwing (the clone
            // lookup and the conversation lookup IllegalStateException, the strict endpoint
            // lookups TriggerClientException): one outcome for all of them.
            throw new ResourceLookupUnavailableException(type, lookupFailed);
        }
    }

    /**
     * The ownership check could not run: an owning service did not answer. The link is refused
     * (fail closed), and the caller says "try again" rather than "not found".
     */
    public static class ResourceLookupUnavailableException extends RuntimeException {
        public ResourceLookupUnavailableException(ResourceType type, Throwable cause) {
            super("Could not verify the " + type + " resource right now", cause);
        }
    }

    /**
     * Read-time defence: whether a stored link still names a resource its owner holds. Only an
     * APPLICATION link is checked, because only an APPLICATION link authenticates a visitor as
     * its owner (both edges refuse the others for API access) and only it drives
     * {@code /app/public}; a CHAT / FORM / CONVERSATION link resolves to its own capability
     * token and grants nothing that token does not.
     *
     * <p>Catches a link created before the creation check existed, and one whose publication
     * has since left its owner's scope or whose publication row no longer exists: such a link
     * must stop authenticating anyone, not keep reading a publication its owner no longer holds.
     * An unpublished publication (INACTIVE, which is what a user's delete does) still has its
     * owner, so its links keep resolving, as they did before.
     */
    public boolean isBoundToOwnedResource(SharedLinkEntity link) {
        if (link == null) {
            return false;
        }
        if (link.getResourceType() != ResourceType.APPLICATION) {
            return true;
        }
        UUID publicationId = parseUuid(link.getResourceToken());
        // The publication's own owner-scope rule, not a ScopeGuard row-vs-workspace predicate
        // (a USER-owned publication belongs to its user in any workspace): hence the ArchUnit
        // allow-list entry for this method.
        return publicationId != null
                && ownsPublication(publicationId, link.getTenantId(), link.getOrganizationId());
    }

    private boolean mayShareApplication(String tenantId, String organizationId,
                                        String resourceToken, UUID resourceId) {
        UUID publicationId = parseUuid(resourceToken);
        if (publicationId == null || !ownsPublication(publicationId, tenantId, organizationId)) {
            return false;
        }
        if (resourceId == null) {
            return true;
        }
        // The clone is looked up in a workspace; with none there is nothing to compare against.
        if (organizationId == null || organizationId.isBlank()) {
            return false;
        }
        Map<String, Object> clone = orchestratorClient.findBySourcePublicationStrict(
                publicationId, tenantId, organizationId);
        return clone != null && clone.get("id") != null
                && resourceId.toString().equalsIgnoreCase(clone.get("id").toString());
    }

    private boolean ownsPublication(UUID publicationId, String tenantId, String organizationId) {
        return publicationRepository.findOwnerScopeById(publicationId).stream()
                .anyMatch(row -> WorkflowPublicationService.isInOwnerScope(
                        row.getOwnerType(), row.getOwnerId(), row.getPublisherId(),
                        tenantId, organizationId));
    }

    private boolean mayShareChatEndpoint(String tenantId, String organizationId,
                                         String token, UUID resourceId) {
        StandaloneChatEndpointDto chat = triggerClient.findChatEndpointByTokenStrict(token);
        return chat != null
                && ScopeGuard.isInStrictScope(tenantId, organizationId,
                        chat.getTenantId(), chat.getOrganizationId())
                && (resourceId == null || resourceId.equals(chat.getId()));
    }

    private boolean mayShareFormEndpoint(String tenantId, String organizationId,
                                         String token, UUID resourceId) {
        StandaloneFormEndpointDto form = triggerClient.findFormEndpointByTokenStrict(token);
        // The form DTO carries its workspace but not its creator, and the workspace is what
        // strict scope compares for a caller who has one. A caller with none (who could not
        // file a link anyway: the row's organization_id is NOT NULL) matches nothing here.
        return form != null
                && ScopeGuard.isInStrictScope(tenantId, organizationId, null, form.getOrganizationId())
                && (resourceId == null || resourceId.equals(form.getId()));
    }

    private boolean mayShareConversation(String tenantId, String organizationId,
                                         String token, UUID resourceId) {
        String conversationId = conversationClient.findSharedConversationIdInScope(
                token, tenantId, organizationId);
        return conversationId != null
                && (resourceId == null || resourceId.toString().equalsIgnoreCase(conversationId));
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    /** Link creation is an interactive request: an owning service that hangs must fail it fast. */
    private static RestTemplate boundedRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return new RestTemplate(factory);
    }
}
