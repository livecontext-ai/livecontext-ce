package com.apimarketplace.conversation.service;

import com.apimarketplace.conversation.dto.ConversationDto;
import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.mapper.ConversationMapper;
import com.apimarketplace.conversation.repository.ConversationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConversationSharingService")
class ConversationSharingServiceTest {

    @Mock
    private ConversationRepository conversationRepository;

    private final ConversationMapper conversationMapper = new ConversationMapper();

    private ConversationSharingService service;

    private static final String CONV_ID = "conv-001";
    private static final String USER_ID = "user|123";
    private static final String OTHER_USER = "user|999";

    @BeforeEach
    void setUp() {
        service = new ConversationSharingService(conversationRepository, conversationMapper);
    }

    // ──────────────── enableSharing ────────────────

    @Nested
    @DisplayName("enableSharing")
    class EnableSharing {

        @Test
        @DisplayName("generates cs_ token and sets read mode")
        void generatesTokenAndSetsMode() {
            Conversation conv = buildConversation(CONV_ID, USER_ID);
            when(conversationRepository.findById(CONV_ID)).thenReturn(Optional.of(conv));
            when(conversationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ConversationDto result = service.enableSharing(CONV_ID, USER_ID, "read", true);

            assertThat(result.getShareToken()).startsWith("cs_");
            assertThat(result.getShareToken()).hasSize(35); // cs_ + 32 hex chars
            assertThat(result.getShareMode()).isEqualTo("read");
            assertThat(result.getMemoryEnabled()).isTrue();
        }

        @Test
        @DisplayName("preserves existing token on re-enable")
        void preservesExistingToken() {
            Conversation conv = buildConversation(CONV_ID, USER_ID);
            conv.setShareToken("cs_existing123");
            conv.setShareMode("off");
            when(conversationRepository.findById(CONV_ID)).thenReturn(Optional.of(conv));
            when(conversationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ConversationDto result = service.enableSharing(CONV_ID, USER_ID, "readwrite", false);

            assertThat(result.getShareToken()).isEqualTo("cs_existing123");
            assertThat(result.getShareMode()).isEqualTo("readwrite");
            assertThat(result.getMemoryEnabled()).isFalse();
        }

        @Test
        @DisplayName("defaults to read mode when shareMode is null")
        void defaultsToReadMode() {
            Conversation conv = buildConversation(CONV_ID, USER_ID);
            when(conversationRepository.findById(CONV_ID)).thenReturn(Optional.of(conv));
            when(conversationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ConversationDto result = service.enableSharing(CONV_ID, USER_ID, null, true);

            assertThat(result.getShareMode()).isEqualTo("read");
        }

        @Test
        @DisplayName("throws when conversation not found")
        void throwsWhenNotFound() {
            when(conversationRepository.findById(CONV_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.enableSharing(CONV_ID, USER_ID, "read", true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        @DisplayName("throws when user is not owner")
        void throwsWhenNotOwner() {
            Conversation conv = buildConversation(CONV_ID, USER_ID);
            when(conversationRepository.findById(CONV_ID)).thenReturn(Optional.of(conv));

            assertThatThrownBy(() -> service.enableSharing(CONV_ID, OTHER_USER, "read", true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Not authorized");
        }

        @Test
        @DisplayName("saves the conversation with updated sharing fields")
        void savesConversation() {
            Conversation conv = buildConversation(CONV_ID, USER_ID);
            when(conversationRepository.findById(CONV_ID)).thenReturn(Optional.of(conv));
            when(conversationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.enableSharing(CONV_ID, USER_ID, "read", true);

            ArgumentCaptor<Conversation> captor = ArgumentCaptor.forClass(Conversation.class);
            verify(conversationRepository).save(captor.capture());
            Conversation saved = captor.getValue();
            assertThat(saved.getShareToken()).startsWith("cs_");
            assertThat(saved.getShareMode()).isEqualTo("read");
            assertThat(saved.getMemoryEnabled()).isTrue();
        }
    }

    // ──────────────── updateShareSettings ────────────────

    @Nested
    @DisplayName("updateShareSettings")
    class UpdateShareSettings {

        @Test
        @DisplayName("updates shareMode only")
        void updatesShareMode() {
            Conversation conv = buildConversation(CONV_ID, USER_ID);
            conv.setShareToken("cs_test");
            conv.setShareMode("read");
            conv.setMemoryEnabled(true);
            when(conversationRepository.findById(CONV_ID)).thenReturn(Optional.of(conv));
            when(conversationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ConversationDto result = service.updateShareSettings(CONV_ID, USER_ID, "readwrite", null);

            assertThat(result.getShareMode()).isEqualTo("readwrite");
            assertThat(result.getMemoryEnabled()).isTrue();
        }

        @Test
        @DisplayName("updates memoryEnabled only")
        void updatesMemoryEnabled() {
            Conversation conv = buildConversation(CONV_ID, USER_ID);
            conv.setShareToken("cs_test");
            conv.setShareMode("read");
            conv.setMemoryEnabled(true);
            when(conversationRepository.findById(CONV_ID)).thenReturn(Optional.of(conv));
            when(conversationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ConversationDto result = service.updateShareSettings(CONV_ID, USER_ID, null, false);

            assertThat(result.getShareMode()).isEqualTo("read");
            assertThat(result.getMemoryEnabled()).isFalse();
        }

        @Test
        @DisplayName("throws when not owner")
        void throwsWhenNotOwner() {
            Conversation conv = buildConversation(CONV_ID, USER_ID);
            when(conversationRepository.findById(CONV_ID)).thenReturn(Optional.of(conv));

            assertThatThrownBy(() -> service.updateShareSettings(CONV_ID, OTHER_USER, "read", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ──────────────── disableSharing ────────────────

    @Nested
    @DisplayName("disableSharing")
    class DisableSharing {

        @Test
        @DisplayName("preserves token and sets mode to off")
        void preservesTokenAndSetsOff() {
            Conversation conv = buildConversation(CONV_ID, USER_ID);
            conv.setShareToken("cs_test");
            conv.setShareMode("read");
            when(conversationRepository.findById(CONV_ID)).thenReturn(Optional.of(conv));
            when(conversationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.disableSharing(CONV_ID, USER_ID);

            ArgumentCaptor<Conversation> captor = ArgumentCaptor.forClass(Conversation.class);
            verify(conversationRepository).save(captor.capture());
            assertThat(captor.getValue().getShareToken()).isEqualTo("cs_test");
            assertThat(captor.getValue().getShareMode()).isEqualTo("off");
        }

        @Test
        @DisplayName("throws when not owner")
        void throwsWhenNotOwner() {
            Conversation conv = buildConversation(CONV_ID, USER_ID);
            when(conversationRepository.findById(CONV_ID)).thenReturn(Optional.of(conv));

            assertThatThrownBy(() -> service.disableSharing(CONV_ID, OTHER_USER))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("throws when conversation not found")
        void throwsWhenNotFound() {
            when(conversationRepository.findById(CONV_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.disableSharing(CONV_ID, USER_ID))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ──────────────── findByShareToken ────────────────

    @Nested
    @DisplayName("findByShareToken")
    class FindByShareToken {

        @Test
        @DisplayName("returns conversation when token exists")
        void returnsConversation() {
            Conversation conv = buildConversation(CONV_ID, USER_ID);
            conv.setShareToken("cs_test");
            when(conversationRepository.findByShareToken("cs_test")).thenReturn(Optional.of(conv));

            Optional<Conversation> result = service.findByShareToken("cs_test");

            assertThat(result).isPresent();
            assertThat(result.get().getId()).isEqualTo(CONV_ID);
        }

        @Test
        @DisplayName("returns empty when token not found")
        void returnsEmpty() {
            when(conversationRepository.findByShareToken("cs_nonexistent")).thenReturn(Optional.empty());

            Optional<Conversation> result = service.findByShareToken("cs_nonexistent");

            assertThat(result).isEmpty();
        }
    }

    // ──────────────── PR28 - org-aware authorization ────────────────

    @Nested
    @DisplayName("PR28 - org teammate authorization")
    class OrgTeammateAuth {

        private static final String OWNER = "user-owner";
        private static final String TEAMMATE = "user-teammate";
        private static final String OTHER_USER = "user-other";
        private static final String ORG = "org-acme";
        private static final String OTHER_ORG = "org-other";

        @Test
        @DisplayName("Org teammate can enable sharing on an org-tagged conversation owned by another user")
        void teammateCanEnableSharing() {
            // Load-bearing: pre-PR28 this case threw "Not authorized" because the
            // service only allowed conversation.getUserId().equals(userId).
            Conversation conv = buildConversation(CONV_ID, OWNER);
            conv.setOrganizationId(ORG);
            when(conversationRepository.findById(CONV_ID)).thenReturn(Optional.of(conv));
            when(conversationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // Teammate's active workspace matches the conversation's org → authorized.
            service.enableSharing(CONV_ID, TEAMMATE, ORG, "read", null);

            verify(conversationRepository).save(conv);
        }

        @Test
        @DisplayName("Non-teammate (different org) is REFUSED on org-tagged conversation")
        void nonTeammateRefused() {
            Conversation conv = buildConversation(CONV_ID, OWNER);
            conv.setOrganizationId(ORG);
            when(conversationRepository.findById(CONV_ID)).thenReturn(Optional.of(conv));

            org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> service.enableSharing(CONV_ID, OTHER_USER, OTHER_ORG, "read", null));
            verify(conversationRepository, never()).save(any());
        }

        @Test
        @DisplayName("Personal-scope caller (orgId=null) on org-tagged conversation is REFUSED")
        void personalCallerRefusedOnOrgConv() {
            // A user signed into personal workspace cannot share an org-tagged
            // conversation by guessing the ID. Closes the cross-scope leak in
            // the opposite direction.
            Conversation conv = buildConversation(CONV_ID, OWNER);
            conv.setOrganizationId(ORG);
            when(conversationRepository.findById(CONV_ID)).thenReturn(Optional.of(conv));

            org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> service.enableSharing(CONV_ID, OTHER_USER, null, "read", null));
        }

        @Test
        @DisplayName("Owner always authorized regardless of orgId")
        void ownerAlwaysAuthorized() {
            // Back-compat invariant: an owner with no active org (personal-scope
            // caller) on their own org-tagged conversation should still work.
            Conversation conv = buildConversation(CONV_ID, OWNER);
            conv.setOrganizationId(ORG);
            when(conversationRepository.findById(CONV_ID)).thenReturn(Optional.of(conv));
            when(conversationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.enableSharing(CONV_ID, OWNER, null, "read", null);

            verify(conversationRepository).save(conv);
        }

        @Test
        @DisplayName("disableSharing: teammate can disable on org-tagged conv (symmetric to enable)")
        void teammateCanDisableSharing() {
            Conversation conv = buildConversation(CONV_ID, OWNER);
            conv.setOrganizationId(ORG);
            conv.setShareMode("read");
            when(conversationRepository.findById(CONV_ID)).thenReturn(Optional.of(conv));

            service.disableSharing(CONV_ID, TEAMMATE, ORG);

            assertThat(conv.getShareMode()).isEqualTo("off");
            verify(conversationRepository).save(conv);
        }
    }

    // ──────────────── helpers ────────────────

    private Conversation buildConversation(String id, String userId) {
        Conversation conv = new Conversation();
        conv.setId(id);
        conv.setUserId(userId);
        conv.setTitle("Test Conversation");
        conv.setActive(true);
        conv.setShareMode("off");
        conv.setMemoryEnabled(true);
        return conv;
    }
}
