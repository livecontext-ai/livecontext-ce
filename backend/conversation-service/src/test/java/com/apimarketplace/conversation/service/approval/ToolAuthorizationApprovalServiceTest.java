package com.apimarketplace.conversation.service.approval;

import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.repository.ConversationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@DisplayName("ToolAuthorizationApprovalService - always vs once buckets")
class ToolAuthorizationApprovalServiceTest {

    @Mock
    private ConversationRepository conversationRepository;

    @InjectMocks
    private ToolAuthorizationApprovalService service;

    private Conversation conversation;

    @BeforeEach
    void setUp() {
        conversation = new Conversation();
        conversation.setId("conv-1");
        lenient().when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conversation));
        lenient().when(conversationRepository.save(any(Conversation.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @SuppressWarnings("unchecked")
    private List<String> bucket(String name) {
        Object v = conversation.getApprovedToolActions().get(name);
        return v == null ? List.of() : (List<String>) v;
    }

    @Test
    @DisplayName("remember=true persists the rule into the 'always' bucket")
    void rememberPersistsToAlways() {
        service.approve("conv-1", "application:acquire", true);

        assertThat(bucket("always")).containsExactly("application:acquire");
        assertThat(service.getActiveRules("conv-1")).containsExactly("application:acquire");
    }

    @Test
    @DisplayName("remember=false stores a single-shot grant in the 'once' bucket")
    void onceGrantGoesToOnceBucket() {
        service.approve("conv-1", "catalog:execute", false);

        assertThat(bucket("once")).containsExactly("catalog:execute");
        assertThat(bucket("always")).isEmpty();
    }

    @Test
    @DisplayName("resolveAndConsumeForTurn returns always ∪ once, then clears the once grants")
    void resolveAndConsumeUnionThenClearsOnce() {
        service.approve("conv-1", "application:acquire", true);  // always
        service.approve("conv-1", "catalog:execute", false);     // once

        List<String> resolved = service.resolveAndConsumeForTurn("conv-1");

        assertThat(resolved).containsExactlyInAnyOrder("application:acquire", "catalog:execute");
        // once consumed
        assertThat(bucket("once")).isEmpty();
        // always survives a second turn
        assertThat(service.resolveAndConsumeForTurn("conv-1")).containsExactly("application:acquire");
    }

    @Test
    @DisplayName("chatConfig.autoAuthorizeTools=true adds the '*' wildcard to the resolved grants")
    void autoAuthorizeTogglerAddsWildcard() {
        conversation.setChatConfig(new java.util.HashMap<>(java.util.Map.of("autoAuthorizeTools", true)));

        List<String> resolved = service.resolveAndConsumeForTurn("conv-1");

        assertThat(resolved).contains("*");
    }

    @Test
    @DisplayName("chatConfig.autoAuthorizeTools=false leaves the resolved grants wildcard-free")
    void autoAuthorizeDisabledHasNoWildcard() {
        conversation.setChatConfig(new java.util.HashMap<>(java.util.Map.of("autoAuthorizeTools", false)));
        service.approve("conv-1", "application:acquire", true);

        List<String> resolved = service.resolveAndConsumeForTurn("conv-1");

        assertThat(resolved).containsExactly("application:acquire");
        assertThat(resolved).doesNotContain("*");
    }

    @Test
    @DisplayName("revoke removes a rule from both buckets")
    void revokeRemovesRule() {
        service.approve("conv-1", "application:acquire", true);

        boolean changed = service.revoke("conv-1", "application:acquire");

        assertThat(changed).isTrue();
        assertThat(service.getActiveRules("conv-1")).isEmpty();
    }

    @Test
    @DisplayName("Blank rule or missing conversation is a no-op")
    void blankRuleIsNoOp() {
        assertThat(service.approve("conv-1", "  ", true)).isFalse();
        assertThat(service.approve("conv-1", null, false)).isFalse();
    }
}
