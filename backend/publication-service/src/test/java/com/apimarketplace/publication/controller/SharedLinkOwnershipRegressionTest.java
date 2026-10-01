package com.apimarketplace.publication.controller;

import com.apimarketplace.common.security.CredentialEncryptionService;
import com.apimarketplace.common.security.token.TokenAtRest;
import com.apimarketplace.conversation.client.ConversationClient;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.SharedLinkEntity;
import com.apimarketplace.publication.domain.SharedLinkEntity.ResourceType;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.OwnerType;
import com.apimarketplace.publication.dto.SharedLinkResponse;
import com.apimarketplace.publication.repository.SharedLinkRepository;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository.OwnerScopeView;
import com.apimarketplace.publication.service.SharedLinkResourceGuard;
import com.apimarketplace.publication.service.SharedLinkService;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.TriggerClientException;
import com.apimarketplace.trigger.client.dto.StandaloneChatEndpointDto;
import com.apimarketplace.trigger.client.dto.StandaloneFormEndpointDto;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression for the share-link ownership defect, through the REAL {@link SharedLinkController},
 * {@link SharedLinkService} and {@link SharedLinkResourceGuard}. Only the repositories and the clients
 * to the owning services are mocked, so every decision under test is the production code's.
 *
 * <p>Before the fix, {@code POST /api/publications/shared-links} stored whatever resource the caller
 * named. User B could file an APPLICATION link naming user A's publication id, and its {@code sl_}
 * token then authenticated any visitor AS B while binding the share scope to A's publication: the
 * publication detail served A's raw plan snapshot past the PRIVATE / UNLISTED gate,
 * {@code /application-workflow} handed out A's clone id, and orchestrator's public
 * {@code /app/public/{token}} API followed the publication to A's source workflow and run. The same
 * missing check let a link name another workspace's chat, form or conversation, squatting the one
 * active link each resource may have.
 */
@DisplayName("Share-link resource ownership - regression (controller + service + guard)")
class SharedLinkOwnershipRegressionTest {

    @BeforeAll
    static void installTokenAtRest() {
        TokenAtRest.install(new CredentialEncryptionService("test-password-123", "0123456789abcdef"));
    }

    private static final String USER_A = "101";
    private static final String ORG_A = "0a0a0a0a-0000-0000-0000-00000000000a";
    private static final String USER_B = "202";
    private static final String ORG_B = "0b0b0b0b-0000-0000-0000-00000000000b";

    /** A's PRIVATE publication, ORG-owned by A's workspace. */
    private static final UUID PUBLICATION_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    /** A second publication of A's, to build a mismatched (publication, clone) pair. */
    private static final UUID OTHER_PUBLICATION_A = UUID.fromString("33333333-3333-3333-3333-333333333333");
    /** A's own application clone of PUBLICATION_A (what the Applications page sends as resourceId). */
    private static final UUID CLONE_A = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private SharedLinkRepository linkRepository;
    private WorkflowPublicationRepository publicationRepository;
    private OrchestratorInternalClient orchestratorClient;
    private TriggerClient triggerClient;
    private ConversationClient conversationClient;

    private SharedLinkService service;
    private SharedLinkController controller;

    @BeforeEach
    void setUp() {
        linkRepository = mock(SharedLinkRepository.class);
        publicationRepository = mock(WorkflowPublicationRepository.class);
        orchestratorClient = mock(OrchestratorInternalClient.class);
        triggerClient = mock(TriggerClient.class);
        conversationClient = mock(ConversationClient.class);

        service = new SharedLinkService(linkRepository, new SharedLinkResourceGuard(
                publicationRepository, orchestratorClient, triggerClient, conversationClient));
        controller = new SharedLinkController(service);

        when(publicationRepository.findOwnerScopeById(PUBLICATION_A)).thenReturn(List.of(ownedByOrgA()));
        when(publicationRepository.findOwnerScopeById(OTHER_PUBLICATION_A)).thenReturn(List.of(ownedByOrgA()));
        when(orchestratorClient.findBySourcePublicationStrict(PUBLICATION_A, USER_A, ORG_A))
                .thenReturn(Map.of("id", CLONE_A.toString()));
        when(linkRepository.save(any(SharedLinkEntity.class))).thenAnswer(inv -> {
            SharedLinkEntity saved = inv.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });
    }

    private static OwnerScopeView ownedByOrgA() {
        return new OwnerScopeView() {
            @Override public OwnerType getOwnerType() { return OwnerType.ORG; }
            @Override public String getOwnerId() { return ORG_A; }
            @Override public String getPublisherId() { return USER_A; }
        };
    }

    private ResponseEntity<?> create(String tenantId, String organizationId, String type,
                                     String resourceToken, UUID resourceId) {
        Map<String, Object> body = new HashMap<>();
        body.put("resourceType", type);
        body.put("resourceToken", resourceToken);
        if (resourceId != null) {
            body.put("resourceId", resourceId.toString());
        }
        body.put("title", "t");
        return controller.create(tenantId, organizationId, "MEMBER", "PRO", body);
    }

    private static void assertNotFound(ResponseEntity<?> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isEqualTo(Map.of("error", "Resource not found"));
    }

    /** An owning service could not be asked: refused, but as "try again", never as "not yours". */
    private static void assertUnavailable(ResponseEntity<?> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isEqualTo(Map.of("error", "Could not verify the resource right now, try again"));
    }

    private static SharedLinkEntity activeLink(ResourceType type, String resourceToken, UUID resourceId,
                                               String tenantId, String organizationId) {
        SharedLinkEntity link = new SharedLinkEntity();
        link.setId(UUID.randomUUID());
        link.setToken("sl_" + UUID.randomUUID().toString().replace("-", ""));
        link.setResourceType(type);
        link.setResourceToken(resourceToken);
        link.setResourceId(resourceId);
        link.setTenantId(tenantId);
        link.setOrganizationId(organizationId);
        link.setActive(true);
        return link;
    }

    @Nested
    @DisplayName("creation, APPLICATION")
    class ApplicationCreation {

        @Test
        @DisplayName("the reported attack: user B files an APPLICATION link naming user A's publication -> 404, nothing stored")
        void userBCannotShareUserAsPublication() {
            assertNotFound(create(USER_B, ORG_B, "APPLICATION", PUBLICATION_A.toString(), null));

            verify(linkRepository, never()).save(any());
        }

        @Test
        @DisplayName("naming A's clone as resourceId does not make A's publication B's -> 404")
        void userBCannotShareUserAsPublicationWithItsClone() {
            assertNotFound(create(USER_B, ORG_B, "APPLICATION", PUBLICATION_A.toString(), CLONE_A));

            verify(linkRepository, never()).save(any());
        }

        @Test
        @DisplayName("the refusal is the same 404 when A's publication is ALREADY shared: no 'already in use' oracle")
        void noAlreadyInUseOracle() {
            when(linkRepository.findByResourceTokenHashAndIsActiveTrue(TokenAtRest.hash(PUBLICATION_A.toString())))
                    .thenReturn(Optional.of(activeLink(ResourceType.APPLICATION, PUBLICATION_A.toString(), CLONE_A,
                            USER_A, ORG_A)));

            assertNotFound(create(USER_B, ORG_B, "APPLICATION", PUBLICATION_A.toString(), null));
            verify(linkRepository, never()).findByResourceTokenHashAndIsActiveTrue(anyString());
        }

        @Test
        @DisplayName("the owner shares their publication with their own clone, as the Applications page does -> 200")
        void ownerSharesOwnPublication() {
            ResponseEntity<?> response = create(USER_A, ORG_A, "APPLICATION", PUBLICATION_A.toString(), CLONE_A);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            SharedLinkResponse link = (SharedLinkResponse) response.getBody();
            assertThat(link.resourceToken()).isEqualTo(PUBLICATION_A.toString());
            assertThat(link.resourceId()).isEqualTo(CLONE_A);
        }

        @Test
        @DisplayName("a mismatched pair is refused: another workflow as resourceId")
        void anotherWorkflowAsResourceIdIsRefused() {
            assertNotFound(create(USER_A, ORG_A, "APPLICATION", PUBLICATION_A.toString(), UUID.randomUUID()));

            verify(linkRepository, never()).save(any());
        }

        @Test
        @DisplayName("a mismatched pair is refused: the clone of ANOTHER publication of the same owner")
        void cloneOfAnotherPublicationIsRefused() {
            // CLONE_A is A's clone of PUBLICATION_A; A has none of OTHER_PUBLICATION_A.
            assertNotFound(create(USER_A, ORG_A, "APPLICATION", OTHER_PUBLICATION_A.toString(), CLONE_A));

            verify(linkRepository, never()).save(any());
        }

        @Test
        @DisplayName("a failing clone lookup is a retryable 503, not a 404 that would tell the owner the app is not theirs")
        void failingCloneLookupIsUnavailable() {
            when(orchestratorClient.findBySourcePublicationStrict(PUBLICATION_A, USER_A, ORG_A))
                    .thenThrow(new IllegalStateException("orchestrator down"));

            assertUnavailable(create(USER_A, ORG_A, "APPLICATION", PUBLICATION_A.toString(), CLONE_A));
            verify(linkRepository, never()).save(any());
        }

        @Test
        @DisplayName("an unknown resource type is still a 400, as before")
        void unknownTypeIsBadRequest() {
            ResponseEntity<?> response = create(USER_A, ORG_A, "WORKFLOW", PUBLICATION_A.toString(), null);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            verify(linkRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("creation, CHAT / FORM / CONVERSATION")
    class OtherTypesCreation {

        private final UUID conversationOfA = UUID.randomUUID();

        @BeforeEach
        void resourcesOfWorkspaceA() {
            StandaloneChatEndpointDto chatOfA = new StandaloneChatEndpointDto();
            chatOfA.setId(UUID.randomUUID());
            chatOfA.setTenantId(USER_A);
            chatOfA.setOrganizationId(ORG_A);
            when(triggerClient.findChatEndpointByTokenStrict("ch_a")).thenReturn(chatOfA);
            StandaloneFormEndpointDto formOfA = new StandaloneFormEndpointDto();
            formOfA.setId(UUID.randomUUID());
            formOfA.setOrganizationId(ORG_A);
            when(triggerClient.findFormEndpointByTokenStrict("fm_a")).thenReturn(formOfA);
            // conversation-service answers for A's workspace only.
            when(conversationClient.findSharedConversationIdInScope("cs_a", USER_A, ORG_A))
                    .thenReturn(conversationOfA.toString());
        }

        @Test
        @DisplayName("user B naming A's chat endpoint -> 404, nothing stored")
        void foreignChatIsRefused() {
            assertNotFound(create(USER_B, ORG_B, "CHAT", "ch_a", null));
            verify(linkRepository, never()).save(any());
        }

        @Test
        @DisplayName("user B naming A's form endpoint -> 404, nothing stored")
        void foreignFormIsRefused() {
            assertNotFound(create(USER_B, ORG_B, "FORM", "fm_a", null));
            verify(linkRepository, never()).save(any());
        }

        @Test
        @DisplayName("user B naming A's shared conversation -> 404, nothing stored")
        void foreignConversationIsRefused() {
            assertNotFound(create(USER_B, ORG_B, "CONVERSATION", "cs_a", conversationOfA));
            verify(linkRepository, never()).save(any());
        }

        @Test
        @DisplayName("a synthetic token names nothing at all -> 404")
        void syntheticTokensAreRefused() {
            assertNotFound(create(USER_A, ORG_A, "CHAT", "chat-e2e-synthetic", null));
            assertNotFound(create(USER_A, ORG_A, "CONVERSATION", "conv-e2e-synthetic", null));
            verify(linkRepository, never()).save(any());
        }

        @Test
        @DisplayName("A's own conversation paired with a resourceId that is not that conversation -> 404")
        void conversationWithForeignResourceIdIsRefused() {
            assertNotFound(create(USER_A, ORG_A, "CONVERSATION", "cs_a", UUID.randomUUID()));
            verify(linkRepository, never()).save(any());
        }

        @Test
        @DisplayName("an owning service that is down answers 503 for all three types, never the 404 of 'not yours'")
        void outageIsUnavailableNotNotFound() {
            // Before: TriggerClient and ConversationClient folded every failure into null, so a
            // trigger-service or conversation-service outage told the OWNER their resource was not theirs.
            when(triggerClient.findChatEndpointByTokenStrict("ch_a")).thenThrow(new TriggerClientException(
                    TriggerClientException.Kind.TRANSPORT, "Connection refused", null));
            when(triggerClient.findFormEndpointByTokenStrict("fm_a")).thenThrow(new TriggerClientException(
                    TriggerClientException.Kind.SERVER_ERROR, "503", null));
            when(conversationClient.findSharedConversationIdInScope("cs_a", USER_A, ORG_A))
                    .thenThrow(new IllegalStateException("conversation-service down"));

            assertUnavailable(create(USER_A, ORG_A, "CHAT", "ch_a", null));
            assertUnavailable(create(USER_A, ORG_A, "FORM", "fm_a", null));
            assertUnavailable(create(USER_A, ORG_A, "CONVERSATION", "cs_a", conversationOfA));
            verify(linkRepository, never()).save(any());
        }

        @Test
        @DisplayName("the owners themselves are served for all three types")
        void ownersAreServed() {
            assertThat(create(USER_A, ORG_A, "CHAT", "ch_a", null).getStatusCode().value()).isEqualTo(200);
            assertThat(create(USER_A, ORG_A, "FORM", "fm_a", null).getStatusCode().value()).isEqualTo(200);
            assertThat(create(USER_A, ORG_A, "CONVERSATION", "cs_a", conversationOfA).getStatusCode().value())
                    .isEqualTo(200);
        }
    }

    /**
     * Links filed before creation was checked still occupy the one active slot of their resource
     * token or resource id, so the rightful holder's share failed with "already in use". The holder's
     * next share retires them; a link whose creator does hold the resource is never touched.
     */
    @Nested
    @DisplayName("creation, a link squatted before the fix")
    class SquatRetirement {

        @Test
        @DisplayName("B's link on A's publication is retired when A shares, and A's link is created")
        void squatOnResourceTokenIsRetired() {
            SharedLinkEntity squat = activeLink(ResourceType.APPLICATION, PUBLICATION_A.toString(), null,
                    USER_B, ORG_B);
            when(linkRepository.findByResourceTokenHashAndIsActiveTrue(TokenAtRest.hash(PUBLICATION_A.toString())))
                    .thenReturn(Optional.of(squat))      // seen by register's squat check
                    .thenReturn(Optional.empty());       // gone for the idempotency lookup that follows

            ResponseEntity<?> response = create(USER_A, ORG_A, "APPLICATION", PUBLICATION_A.toString(), CLONE_A);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(squat.isActive()).as("the squatting link is deactivated").isFalse();
            ArgumentCaptor<SharedLinkEntity> saved = ArgumentCaptor.forClass(SharedLinkEntity.class);
            verify(linkRepository, atLeastOnce()).save(saved.capture());
            assertThat(saved.getAllValues()).anySatisfy(link -> {
                assertThat(link.getTenantId()).isEqualTo(USER_A);
                assertThat(link.getOrganizationId()).isEqualTo(ORG_A);
                assertThat(link.isActive()).isTrue();
            });
        }

        @Test
        @DisplayName("a link squatting A's clone id as resourceId is retired too (the resource-id slot)")
        void squatOnResourceIdIsRetired() {
            // B filed a CHAT link on a made-up token with A's clone as resourceId.
            SharedLinkEntity squat = activeLink(ResourceType.CHAT, "chat-made-up", CLONE_A, USER_B, ORG_B);
            when(linkRepository.findByResourceIdAndIsActiveTrue(CLONE_A)).thenReturn(Optional.of(squat));

            ResponseEntity<?> response = create(USER_A, ORG_A, "APPLICATION", PUBLICATION_A.toString(), CLONE_A);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(squat.isActive()).isFalse();
        }

        @Test
        @DisplayName("an other-workspace link whose creator DOES hold the resource is left alone ('already in use', as before)")
        void legitimateOtherWorkspaceLinkIsKept() {
            // A USER-owned publication belongs to its user in any workspace: A's link filed from ORG_B
            // is legitimate, so A sharing again from ORG_A must not take it away.
            when(publicationRepository.findOwnerScopeById(PUBLICATION_A)).thenReturn(List.of(new OwnerScopeView() {
                @Override public OwnerType getOwnerType() { return OwnerType.USER; }
                @Override public String getOwnerId() { return USER_A; }
                @Override public String getPublisherId() { return USER_A; }
            }));
            SharedLinkEntity ownLinkElsewhere = activeLink(ResourceType.APPLICATION, PUBLICATION_A.toString(), null,
                    USER_A, ORG_B);
            when(linkRepository.findByResourceTokenHashAndIsActiveTrue(TokenAtRest.hash(PUBLICATION_A.toString())))
                    .thenReturn(Optional.of(ownLinkElsewhere));

            ResponseEntity<?> response = create(USER_A, ORG_A, "APPLICATION", PUBLICATION_A.toString(), null);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getBody()).isEqualTo(Map.of("error", "Resource token already in use"));
            assertThat(ownLinkElsewhere.isActive()).isTrue();
            verify(linkRepository, never()).save(any());
        }

        @Test
        @DisplayName("nothing is retired for a caller who does not hold the resource: the refusal comes first")
        void nonHolderRetiresNothing() {
            SharedLinkEntity squat = activeLink(ResourceType.APPLICATION, PUBLICATION_A.toString(), null,
                    USER_B, ORG_B);
            when(linkRepository.findByResourceTokenHashAndIsActiveTrue(TokenAtRest.hash(PUBLICATION_A.toString())))
                    .thenReturn(Optional.of(squat));

            assertNotFound(create("303", "0c0c0c0c-0000-0000-0000-00000000000c", "APPLICATION",
                    PUBLICATION_A.toString(), null));
            assertThat(squat.isActive()).isTrue();
            verify(linkRepository, never()).save(any());
        }
    }

    /**
     * {@code PUT /shared-links/{id}} with {@code isActive:true} switched any link of the caller's
     * workspace back on with only a workspace check. So a squat the holder's share had just retired
     * came straight back, and a second active row on the same resource token (the unique index is on
     * the encrypted column since V497, so nothing stopped it) made the next lookup of that token throw,
     * which failed the holder's every share and check with a 500.
     */
    @Nested
    @DisplayName("update, switching a link back on")
    class Reactivation {

        private ResponseEntity<?> reactivate(String tenantId, String organizationId, SharedLinkEntity link) {
            when(linkRepository.findById(link.getId())).thenReturn(Optional.of(link));
            return controller.update(tenantId, organizationId, "MEMBER", link.getId(), Map.of("isActive", true));
        }

        @Test
        @DisplayName("B cannot switch back on its retired link to A's publication -> 404, still inactive")
        void retiredSquatCannotBeReactivated() {
            SharedLinkEntity retired = activeLink(ResourceType.APPLICATION, PUBLICATION_A.toString(), null,
                    USER_B, ORG_B);
            retired.setActive(false);

            assertNotFound(reactivate(USER_B, ORG_B, retired));
            assertThat(retired.isActive()).isFalse();
            verify(linkRepository, never()).save(any());
        }

        @Test
        @DisplayName("a link whose resource token another active link holds cannot be switched back on -> 400")
        void reactivationRefusedWhenTokenIsTaken() {
            SharedLinkEntity mine = activeLink(ResourceType.APPLICATION, PUBLICATION_A.toString(), null,
                    USER_A, ORG_A);
            mine.setActive(false);
            SharedLinkEntity other = activeLink(ResourceType.APPLICATION, PUBLICATION_A.toString(), CLONE_A,
                    USER_A, ORG_A);
            when(linkRepository.findAllByResourceTokenHashAndIsActiveTrueOrderByCreatedAtAsc(
                    TokenAtRest.hash(PUBLICATION_A.toString()))).thenReturn(List.of(other));

            ResponseEntity<?> response = reactivate(USER_A, ORG_A, mine);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getBody()).isEqualTo(Map.of("error", "Resource token already in use"));
            assertThat(mine.isActive()).isFalse();
        }

        @Test
        @DisplayName("a link whose resource id another active link holds cannot be switched back on -> 400")
        void reactivationRefusedWhenResourceIdIsTaken() {
            SharedLinkEntity mine = activeLink(ResourceType.APPLICATION, PUBLICATION_A.toString(), CLONE_A,
                    USER_A, ORG_A);
            mine.setActive(false);
            when(linkRepository.findByResourceIdAndIsActiveTrue(CLONE_A)).thenReturn(Optional.of(
                    activeLink(ResourceType.CHAT, "ch_other", CLONE_A, USER_A, ORG_A)));

            assertThat(reactivate(USER_A, ORG_A, mine).getStatusCode().value()).isEqualTo(400);
            assertThat(mine.isActive()).isFalse();
        }

        @Test
        @DisplayName("the owner switches their own free link back on -> 200, active")
        void ownerReactivatesOwnLink() {
            SharedLinkEntity mine = activeLink(ResourceType.APPLICATION, PUBLICATION_A.toString(), CLONE_A,
                    USER_A, ORG_A);
            mine.setActive(false);

            assertThat(reactivate(USER_A, ORG_A, mine).getStatusCode().value()).isEqualTo(200);
            assertThat(mine.isActive()).isTrue();
        }

        @Test
        @DisplayName("an owning service that is down during reactivation -> 503, still inactive")
        void reactivationDuringOutageIsUnavailable() {
            SharedLinkEntity mine = activeLink(ResourceType.CHAT, "ch_a", null, USER_A, ORG_A);
            mine.setActive(false);
            when(triggerClient.findChatEndpointByTokenStrict("ch_a")).thenThrow(new TriggerClientException(
                    TriggerClientException.Kind.TRANSPORT, "Connection refused", null));

            assertUnavailable(reactivate(USER_A, ORG_A, mine));
            assertThat(mine.isActive()).isFalse();
        }

        @Test
        @DisplayName("a title edit on an active link asks nothing of the owning services, as before")
        void titleEditIsUnchecked() {
            SharedLinkEntity mine = activeLink(ResourceType.CHAT, "ch_a", null, USER_A, ORG_A);
            when(linkRepository.findById(mine.getId())).thenReturn(Optional.of(mine));

            ResponseEntity<?> response = controller.update(USER_A, ORG_A, "MEMBER", mine.getId(),
                    Map.of("title", "renamed", "isActive", true));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            org.mockito.Mockito.verifyNoInteractions(triggerClient);
        }
    }

    /**
     * Two active rows on one resource token can exist (see {@link Reactivation}): every lookup of
     * that token must keep working, and the caller's own row must still win the idempotency check.
     */
    @Nested
    @DisplayName("two active links on one resource token")
    class DuplicateActiveRows {

        @Test
        @DisplayName("the holder's share returns its own existing link instead of failing, and retires the squat beside it")
        void holderShareSurvivesDuplicates() {
            SharedLinkEntity squat = activeLink(ResourceType.APPLICATION, PUBLICATION_A.toString(), null,
                    USER_B, ORG_B);
            SharedLinkEntity own = activeLink(ResourceType.APPLICATION, PUBLICATION_A.toString(), null,
                    USER_A, ORG_A);
            when(linkRepository.findAllByResourceTokenHashAndIsActiveTrueOrderByCreatedAtAsc(
                    TokenAtRest.hash(PUBLICATION_A.toString()))).thenReturn(List.of(squat, own));

            ResponseEntity<?> response = create(USER_A, ORG_A, "APPLICATION", PUBLICATION_A.toString(), null);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(((SharedLinkResponse) response.getBody()).id()).isEqualTo(own.getId());
            assertThat(squat.isActive()).as("the squat beside it is retired").isFalse();
        }

        @Test
        @DisplayName("the repository answers the oldest row instead of throwing on a second one")
        void repositoryLookupToleratesDuplicates() {
            SharedLinkRepository repository = mock(SharedLinkRepository.class,
                    org.mockito.Mockito.withSettings().defaultAnswer(org.mockito.Mockito.CALLS_REAL_METHODS));
            SharedLinkEntity oldest = activeLink(ResourceType.CHAT, "ch_a", null, USER_A, ORG_A);
            SharedLinkEntity newer = activeLink(ResourceType.CHAT, "ch_a", null, USER_B, ORG_B);
            org.mockito.Mockito.doReturn(List.of(oldest, newer)).when(repository)
                    .findAllByResourceTokenHashAndIsActiveTrueOrderByCreatedAtAsc("h");

            assertThat(repository.findByResourceTokenHashAndIsActiveTrue("h")).containsSame(oldest);
        }
    }

    /**
     * A link filed before the creation check existed (or whose publication later left its owner's
     * scope) must authenticate nobody. Every consumer resolves the sl_ token through
     * SharedLinkService.getByToken: the CE edge directly, the gateway via /validate, orchestrator's
     * /app/public API via /by-token. The public /share resolver goes through resolve().
     */
    @Nested
    @DisplayName("read time: a stored link naming a foreign publication")
    class ReadTime {

        private static final String SL_TOKEN = "sl_0123456789abcdef0123456789abcdef";

        private SharedLinkEntity storedLink(String tenantId, String organizationId) {
            SharedLinkEntity link = activeLink(ResourceType.APPLICATION, PUBLICATION_A.toString(), null,
                    tenantId, organizationId);
            link.setToken(SL_TOKEN);
            when(linkRepository.findByTokenHash(TokenAtRest.hash(SL_TOKEN))).thenReturn(Optional.of(link));
            return link;
        }

        @Test
        @DisplayName("B's link to A's publication resolves to nothing on every path, and is not counted")
        void forgedLinkAuthenticatesNobody() {
            SharedLinkEntity forged = storedLink(USER_B, ORG_B);
            InternalSharedLinkController internal = new InternalSharedLinkController(service);
            PublicShareResolverController publicResolver = new PublicShareResolverController(service);

            assertThat(service.getByToken(SL_TOKEN)).as("CE edge").isEmpty();
            assertThat(internal.validate(SL_TOKEN).getStatusCode().value()).as("gateway /validate").isEqualTo(404);
            assertThat(internal.getByToken(SL_TOKEN).getStatusCode().value()).as("/app/public via /by-token")
                    .isEqualTo(404);
            assertThat(publicResolver.resolve(SL_TOKEN).getStatusCode().value()).as("public /share resolver")
                    .isEqualTo(404);
            verify(linkRepository, never()).incrementAccessCountById(forged.getId());
        }

        @Test
        @DisplayName("the owner's own link keeps resolving on every path")
        void ownersLinkStillResolves() {
            storedLink(USER_A, ORG_A);
            InternalSharedLinkController internal = new InternalSharedLinkController(service);

            assertThat(service.getByToken(SL_TOKEN)).isPresent();
            assertThat(internal.validate(SL_TOKEN).getStatusCode().value()).isEqualTo(200);
            assertThat(service.resolve(SL_TOKEN)).isPresent();
        }
    }
}
