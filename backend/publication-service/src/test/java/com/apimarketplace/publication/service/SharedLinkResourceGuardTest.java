package com.apimarketplace.publication.service;

import com.apimarketplace.conversation.client.ConversationClient;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.SharedLinkEntity;
import com.apimarketplace.publication.domain.SharedLinkEntity.ResourceType;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.OwnerType;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository.OwnerScopeView;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.TriggerClientException;
import com.apimarketplace.trigger.client.dto.StandaloneChatEndpointDto;
import com.apimarketplace.trigger.client.dto.StandaloneFormEndpointDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link SharedLinkResourceGuard}: which resource a user may name in a share link, per type, and
 * whether a stored APPLICATION link still names a publication its owner holds.
 *
 * <p>The attack it closes: user B files an APPLICATION link naming user A's publication id, then
 * reads through it what only A may read. The end-to-end form of that attack, through the real
 * controller and service, is SharedLinkOwnershipRegressionTest.
 */
@DisplayName("SharedLinkResourceGuard")
class SharedLinkResourceGuardTest {

    private static final String USER_A = "user-a";
    private static final String ORG_A = "org-a";
    private static final String USER_B = "user-b";
    private static final String ORG_B = "org-b";
    private static final UUID PUBLICATION = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CLONE_OF_A = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private WorkflowPublicationRepository publicationRepository;
    private OrchestratorInternalClient orchestratorClient;
    private TriggerClient triggerClient;
    private ConversationClient conversationClient;
    private SharedLinkResourceGuard guard;

    @BeforeEach
    void setUp() {
        publicationRepository = mock(WorkflowPublicationRepository.class);
        orchestratorClient = mock(OrchestratorInternalClient.class);
        triggerClient = mock(TriggerClient.class);
        conversationClient = mock(ConversationClient.class);
        guard = new SharedLinkResourceGuard(publicationRepository, orchestratorClient, triggerClient, conversationClient);
    }

    private static OwnerScopeView ownerScope(OwnerType type, String ownerId, String publisherId) {
        return new OwnerScopeView() {
            @Override public OwnerType getOwnerType() { return type; }
            @Override public String getOwnerId() { return ownerId; }
            @Override public String getPublisherId() { return publisherId; }
        };
    }

    private void publicationOwnedByOrgA() {
        when(publicationRepository.findOwnerScopeById(PUBLICATION))
                .thenReturn(List.of(ownerScope(OwnerType.ORG, ORG_A, USER_A)));
    }

    @Nested
    @DisplayName("APPLICATION creation")
    class Application {

        @Test
        @DisplayName("the attack: user B naming user A's publication is refused, and nothing else is asked")
        void refusesAnotherWorkspacesPublication() {
            publicationOwnedByOrgA();

            assertThat(guard.mayShare(ResourceType.APPLICATION, USER_B, ORG_B, PUBLICATION.toString(), null)).isFalse();
            assertThat(guard.mayShare(ResourceType.APPLICATION, USER_B, ORG_B, PUBLICATION.toString(), CLONE_OF_A))
                    .as("naming the owner's clone as resourceId does not make the publication B's").isFalse();
            verifyNoInteractions(orchestratorClient);
        }

        @Test
        @DisplayName("the owner's workspace may share it, with no resourceId")
        void allowsOwnerWithoutResourceId() {
            publicationOwnedByOrgA();

            assertThat(guard.mayShare(ResourceType.APPLICATION, USER_A, ORG_A, PUBLICATION.toString(), null)).isTrue();
            assertThat(guard.mayShare(ResourceType.APPLICATION, "teammate-of-a", ORG_A, PUBLICATION.toString(), null))
                    .as("an ORG-owned publication belongs to the whole workspace, as for every publication mutation")
                    .isTrue();
        }

        @Test
        @DisplayName("the owner with resourceId = their own application clone (what the Applications page sends) is allowed")
        void allowsOwnerWithOwnClone() {
            publicationOwnedByOrgA();
            when(orchestratorClient.findBySourcePublicationStrict(PUBLICATION, USER_A, ORG_A))
                    .thenReturn(Map.of("id", CLONE_OF_A.toString()));

            assertThat(guard.mayShare(ResourceType.APPLICATION, USER_A, ORG_A, PUBLICATION.toString(), CLONE_OF_A)).isTrue();
        }

        @Test
        @DisplayName("a mismatched pair is refused: a resourceId that is not the owner's clone of THAT publication")
        void refusesMismatchedResourceId() {
            publicationOwnedByOrgA();
            when(orchestratorClient.findBySourcePublicationStrict(PUBLICATION, USER_A, ORG_A))
                    .thenReturn(Map.of("id", CLONE_OF_A.toString()));

            assertThat(guard.mayShare(ResourceType.APPLICATION, USER_A, ORG_A, PUBLICATION.toString(), UUID.randomUUID()))
                    .isFalse();
        }

        @Test
        @DisplayName("a resourceId is refused when the owner has no clone of the publication")
        void refusesResourceIdWithoutClone() {
            publicationOwnedByOrgA();
            when(orchestratorClient.findBySourcePublicationStrict(PUBLICATION, USER_A, ORG_A)).thenReturn(null);

            assertThat(guard.mayShare(ResourceType.APPLICATION, USER_A, ORG_A, PUBLICATION.toString(), CLONE_OF_A)).isFalse();
        }

        @Test
        @DisplayName("a resourceId is refused, unasked, when the caller has no workspace to look the clone up in")
        void refusesResourceIdWithoutWorkspace() {
            // USER-owned, so the caller does own it without a workspace: only the clone check refuses.
            when(publicationRepository.findOwnerScopeById(PUBLICATION))
                    .thenReturn(List.of(ownerScope(OwnerType.USER, USER_A, USER_A)));

            assertThat(guard.mayShare(ResourceType.APPLICATION, USER_A, null, PUBLICATION.toString(), null)).isTrue();
            assertThat(guard.mayShare(ResourceType.APPLICATION, USER_A, null, PUBLICATION.toString(), CLONE_OF_A)).isFalse();
            verifyNoInteractions(orchestratorClient);
        }

        @Test
        @DisplayName("a blank workspace cannot look the clone up either: refused, orchestrator not asked")
        void refusesResourceIdWithBlankWorkspace() {
            when(publicationRepository.findOwnerScopeById(PUBLICATION))
                    .thenReturn(List.of(ownerScope(OwnerType.USER, USER_A, USER_A)));

            assertThat(guard.mayShare(ResourceType.APPLICATION, USER_A, " ", PUBLICATION.toString(), CLONE_OF_A)).isFalse();
            verifyNoInteractions(orchestratorClient);
        }

        @Test
        @DisplayName("a clone answer without an id is refused")
        void refusesCloneAnswerWithoutId() {
            publicationOwnedByOrgA();
            when(orchestratorClient.findBySourcePublicationStrict(PUBLICATION, USER_A, ORG_A)).thenReturn(Map.of());

            assertThat(guard.mayShare(ResourceType.APPLICATION, USER_A, ORG_A, PUBLICATION.toString(), CLONE_OF_A)).isFalse();
        }

        @Test
        @DisplayName("the clone id compares case-insensitively (a UUID's text form is not canonical everywhere)")
        void cloneIdComparesCaseInsensitively() {
            // Hex letters on purpose: an all-digit UUID reads the same in either case and proves nothing.
            UUID clone = UUID.fromString("abcdef12-abcd-4ef0-abcd-abcdef123456");
            publicationOwnedByOrgA();
            when(orchestratorClient.findBySourcePublicationStrict(PUBLICATION, USER_A, ORG_A))
                    .thenReturn(Map.of("id", clone.toString().toUpperCase()));

            assertThat(guard.mayShare(ResourceType.APPLICATION, USER_A, ORG_A, PUBLICATION.toString(), clone)).isTrue();
        }

        @Test
        @DisplayName("a failing clone lookup is 'could not check', not a silent 'not found'")
        void cloneLookupFailurePropagates() {
            publicationOwnedByOrgA();
            when(orchestratorClient.findBySourcePublicationStrict(PUBLICATION, USER_A, ORG_A))
                    .thenThrow(new IllegalStateException("orchestrator down"));

            assertThatThrownBy(() -> guard.mayShare(ResourceType.APPLICATION, USER_A, ORG_A, PUBLICATION.toString(), CLONE_OF_A))
                    .isInstanceOf(SharedLinkResourceGuard.ResourceLookupUnavailableException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("USER-owned and legacy rows follow the publication owner-scope rule")
        void userOwnedAndLegacyRows() {
            when(publicationRepository.findOwnerScopeById(PUBLICATION))
                    .thenReturn(List.of(ownerScope(OwnerType.USER, USER_A, USER_A)));
            assertThat(guard.mayShare(ResourceType.APPLICATION, USER_A, ORG_B, PUBLICATION.toString(), null)).isTrue();
            assertThat(guard.mayShare(ResourceType.APPLICATION, USER_B, ORG_A, PUBLICATION.toString(), null)).isFalse();

            // No owner scope assigned: the publisher is the owner.
            when(publicationRepository.findOwnerScopeById(PUBLICATION))
                    .thenReturn(List.of(ownerScope(null, null, USER_A)));
            assertThat(guard.mayShare(ResourceType.APPLICATION, USER_A, ORG_A, PUBLICATION.toString(), null)).isTrue();
            assertThat(guard.mayShare(ResourceType.APPLICATION, USER_B, ORG_A, PUBLICATION.toString(), null)).isFalse();
        }

        @Test
        @DisplayName("an unknown publication, a token that is not an id, and a blank token are refused")
        void refusesUnknownAndMalformed() {
            when(publicationRepository.findOwnerScopeById(PUBLICATION)).thenReturn(List.of());

            assertThat(guard.mayShare(ResourceType.APPLICATION, USER_A, ORG_A, PUBLICATION.toString(), null)).isFalse();
            assertThat(guard.mayShare(ResourceType.APPLICATION, USER_A, ORG_A, "app-e2e-synthetic", null)).isFalse();
            assertThat(guard.mayShare(ResourceType.APPLICATION, USER_A, ORG_A, " ", null)).isFalse();
            assertThat(guard.mayShare(null, USER_A, ORG_A, PUBLICATION.toString(), null)).isFalse();
        }
    }

    @Nested
    @DisplayName("CHAT / FORM creation")
    class Endpoints {

        private final UUID chatId = UUID.randomUUID();
        private final UUID formId = UUID.randomUUID();

        @BeforeEach
        void endpointsOfWorkspaceA() {
            StandaloneChatEndpointDto chat = new StandaloneChatEndpointDto();
            chat.setId(chatId);
            chat.setTenantId(USER_A);
            chat.setOrganizationId(ORG_A);
            when(triggerClient.findChatEndpointByTokenStrict("ch_a")).thenReturn(chat);

            StandaloneFormEndpointDto form = new StandaloneFormEndpointDto();
            form.setId(formId);
            form.setOrganizationId(ORG_A);
            when(triggerClient.findFormEndpointByTokenStrict("fm_a")).thenReturn(form);
        }

        @Test
        @DisplayName("an endpoint of the caller's workspace may be shared, with or without its own id as resourceId")
        void allowsOwnEndpoint() {
            assertThat(guard.mayShare(ResourceType.CHAT, USER_A, ORG_A, "ch_a", null)).isTrue();
            assertThat(guard.mayShare(ResourceType.CHAT, USER_A, ORG_A, "ch_a", chatId)).isTrue();
            assertThat(guard.mayShare(ResourceType.FORM, USER_A, ORG_A, "fm_a", null)).isTrue();
            assertThat(guard.mayShare(ResourceType.FORM, USER_A, ORG_A, "fm_a", formId)).isTrue();
        }

        @Test
        @DisplayName("another workspace's endpoint is refused")
        void refusesForeignEndpoint() {
            assertThat(guard.mayShare(ResourceType.CHAT, USER_B, ORG_B, "ch_a", null)).isFalse();
            assertThat(guard.mayShare(ResourceType.FORM, USER_B, ORG_B, "fm_a", null)).isFalse();
        }

        @Test
        @DisplayName("a resourceId that is not the endpoint's own id is refused (it would squat another resource's slot)")
        void refusesMismatchedResourceId() {
            assertThat(guard.mayShare(ResourceType.CHAT, USER_A, ORG_A, "ch_a", UUID.randomUUID())).isFalse();
            assertThat(guard.mayShare(ResourceType.FORM, USER_A, ORG_A, "fm_a", UUID.randomUUID())).isFalse();
        }

        @Test
        @DisplayName("an unknown endpoint is refused (a synthetic token names nothing)")
        void refusesUnknownEndpoint() {
            assertThat(guard.mayShare(ResourceType.CHAT, USER_A, ORG_A, "chat-e2e-synthetic", null)).isFalse();
            assertThat(guard.mayShare(ResourceType.FORM, USER_A, ORG_A, "form-e2e-synthetic", null)).isFalse();
        }

        @Test
        @DisplayName("a caller with no workspace matches no form endpoint")
        void refusesFormWithoutWorkspace() {
            assertThat(guard.mayShare(ResourceType.FORM, USER_A, null, "fm_a", null)).isFalse();
        }
    }

    @Nested
    @DisplayName("CONVERSATION creation")
    class ConversationLinks {

        private final UUID conversationId = UUID.randomUUID();

        @Test
        @DisplayName("a shared conversation of the caller's workspace may be shared, with its own id as resourceId")
        void allowsOwnConversation() {
            when(conversationClient.findSharedConversationIdInScope("cs_a", USER_A, ORG_A))
                    .thenReturn(conversationId.toString());

            assertThat(guard.mayShare(ResourceType.CONVERSATION, USER_A, ORG_A, "cs_a", conversationId)).isTrue();
            assertThat(guard.mayShare(ResourceType.CONVERSATION, USER_A, ORG_A, "cs_a", null)).isTrue();
        }

        @Test
        @DisplayName("another workspace's conversation token is refused: conversation-service answers nothing for that caller")
        void refusesForeignConversation() {
            when(conversationClient.findSharedConversationIdInScope("cs_a", USER_B, ORG_B)).thenReturn(null);

            assertThat(guard.mayShare(ResourceType.CONVERSATION, USER_B, ORG_B, "cs_a", null)).isFalse();
        }

        @Test
        @DisplayName("a resourceId that is not the conversation the token opens is refused")
        void refusesMismatchedResourceId() {
            when(conversationClient.findSharedConversationIdInScope("cs_a", USER_A, ORG_A))
                    .thenReturn(conversationId.toString());

            assertThat(guard.mayShare(ResourceType.CONVERSATION, USER_A, ORG_A, "cs_a", UUID.randomUUID())).isFalse();
        }
    }

    /**
     * An owning service that cannot be asked must never read as "not yours" (a 404 that tells a
     * legitimate owner their resource is someone else's), and must never let the link through:
     * one exception for every type, which the controller answers 503.
     */
    @Nested
    @DisplayName("an owning service that cannot be asked")
    class LookupOutages {

        @Test
        @DisplayName("trigger-service down on a CHAT link: 'could not check', not a refusal")
        void chatLookupOutage() {
            when(triggerClient.findChatEndpointByTokenStrict("ch_a")).thenThrow(new TriggerClientException(
                    TriggerClientException.Kind.TRANSPORT, "Connection refused", null));

            assertThatThrownBy(() -> guard.mayShare(ResourceType.CHAT, USER_A, ORG_A, "ch_a", null))
                    .isInstanceOf(SharedLinkResourceGuard.ResourceLookupUnavailableException.class)
                    .hasCauseInstanceOf(TriggerClientException.class);
        }

        @Test
        @DisplayName("trigger-service answering a 5xx on a FORM link: 'could not check', not a refusal")
        void formLookupOutage() {
            when(triggerClient.findFormEndpointByTokenStrict("fm_a")).thenThrow(new TriggerClientException(
                    TriggerClientException.Kind.SERVER_ERROR, "500", null));

            assertThatThrownBy(() -> guard.mayShare(ResourceType.FORM, USER_A, ORG_A, "fm_a", null))
                    .isInstanceOf(SharedLinkResourceGuard.ResourceLookupUnavailableException.class);
        }

        @Test
        @DisplayName("conversation-service down on a CONVERSATION link: 'could not check', not a refusal")
        void conversationLookupOutage() {
            when(conversationClient.findSharedConversationIdInScope("cs_a", USER_A, ORG_A))
                    .thenThrow(new IllegalStateException("conversation-service down"));

            assertThatThrownBy(() -> guard.mayShare(ResourceType.CONVERSATION, USER_A, ORG_A, "cs_a", null))
                    .isInstanceOf(SharedLinkResourceGuard.ResourceLookupUnavailableException.class);
        }

        @Test
        @DisplayName("an endpoint the owning service does not know is still a plain refusal (404 upstream = not found)")
        void unknownEndpointIsStillARefusal() {
            when(triggerClient.findChatEndpointByTokenStrict("ch_a")).thenReturn(null);

            assertThat(guard.mayShare(ResourceType.CHAT, USER_A, ORG_A, "ch_a", null)).isFalse();
        }
    }

    @ParameterizedTest(name = "{0} token \"../../x\" never reaches the owning service")
    @EnumSource(value = ResourceType.class, names = {"CHAT", "FORM", "CONVERSATION"})
    @DisplayName("a token that is not one plain path segment is refused before any remote lookup")
    void refusesPathUnsafeTokenWithoutLookup(ResourceType type) {
        assertThat(guard.mayShare(type, USER_A, ORG_A, null, null)).as("null token").isFalse();
        for (String token : List.of("../../x", "ch_a/../../tokens", "cs_a?x=1", "fm%2Fa", "a.b", "a".repeat(129))) {
            assertThat(guard.mayShare(type, USER_A, ORG_A, token, null)).as(token).isFalse();
        }
        verifyNoInteractions(triggerClient, conversationClient);
    }

    @Nested
    @DisplayName("read time: isBoundToOwnedResource")
    class ReadTime {

        private SharedLinkEntity applicationLink(String tenantId, String organizationId, String resourceToken) {
            SharedLinkEntity link = new SharedLinkEntity();
            link.setResourceType(ResourceType.APPLICATION);
            link.setResourceToken(resourceToken);
            link.setTenantId(tenantId);
            link.setOrganizationId(organizationId);
            return link;
        }

        @Test
        @DisplayName("an APPLICATION link its owner holds the publication of stays bound")
        void ownersLinkStaysBound() {
            publicationOwnedByOrgA();

            assertThat(guard.isBoundToOwnedResource(applicationLink(USER_A, ORG_A, PUBLICATION.toString()))).isTrue();
        }

        @Test
        @DisplayName("a stored link naming ANOTHER workspace's publication is not bound: it must authenticate nobody")
        void foreignLinkIsNotBound() {
            publicationOwnedByOrgA();

            assertThat(guard.isBoundToOwnedResource(applicationLink(USER_B, ORG_B, PUBLICATION.toString()))).isFalse();
        }

        @Test
        @DisplayName("a link naming a deleted publication, or no publication id at all, is not bound")
        void deletedOrMalformedIsNotBound() {
            when(publicationRepository.findOwnerScopeById(PUBLICATION)).thenReturn(List.of());

            assertThat(guard.isBoundToOwnedResource(applicationLink(USER_A, ORG_A, PUBLICATION.toString()))).isFalse();
            assertThat(guard.isBoundToOwnedResource(applicationLink(USER_A, ORG_A, "app-e2e-synthetic"))).isFalse();
            assertThat(guard.isBoundToOwnedResource(null)).isFalse();
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = ResourceType.class, names = {"CHAT", "FORM", "CONVERSATION"})
        @DisplayName("a CHAT / FORM / CONVERSATION link is bound without any lookup: its token is its whole capability")
        void otherTypesNeedNoLookup(ResourceType type) {
            SharedLinkEntity link = applicationLink(USER_B, ORG_B, "anything");
            link.setResourceType(type);

            assertThat(guard.isBoundToOwnedResource(link)).isTrue();
            verifyNoInteractions(publicationRepository, triggerClient, conversationClient);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ch_0123456789abcdef0123456789abcdef", "fm_ABCdef-123", "cs_legacy_token"})
    @DisplayName("every token shape the platform mints passes the path-segment check")
    void mintedTokenShapesAreLookedUp(String token) {
        when(triggerClient.findChatEndpointByTokenStrict(anyString())).thenReturn(null);

        guard.mayShare(ResourceType.CHAT, USER_A, ORG_A, token, null);

        verify(triggerClient).findChatEndpointByTokenStrict(token);
    }
}
