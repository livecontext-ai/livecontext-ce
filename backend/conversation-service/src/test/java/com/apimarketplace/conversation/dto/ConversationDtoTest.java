package com.apimarketplace.conversation.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConversationDto")
class ConversationDtoTest {

    @Nested
    @DisplayName("Constructors")
    class Constructors {

        @Test
        @DisplayName("should create with default constructor")
        void shouldCreateWithDefault() {
            ConversationDto dto = new ConversationDto();
            assertThat(dto.getActive()).isTrue();
        }

        @Test
        @DisplayName("should create with args constructor")
        void shouldCreateWithArgs() {
            ConversationDto dto = new ConversationDto("user-1", "Title", "gpt-4", "openai");

            assertThat(dto.getUserId()).isEqualTo("user-1");
            assertThat(dto.getTitle()).isEqualTo("Title");
            assertThat(dto.getModel()).isEqualTo("gpt-4");
            assertThat(dto.getProvider()).isEqualTo("openai");
        }
    }

    @Nested
    @DisplayName("hasPendingAction")
    class HasPendingAction {

        @Test
        @DisplayName("should return false when pendingAction is null")
        void shouldReturnFalseWhenNull() {
            ConversationDto dto = new ConversationDto();
            assertThat(dto.hasPendingAction()).isFalse();
        }

        @Test
        @DisplayName("should return false when pendingAction is empty")
        void shouldReturnFalseWhenEmpty() {
            ConversationDto dto = new ConversationDto();
            dto.setPendingAction(new HashMap<>());
            assertThat(dto.hasPendingAction()).isFalse();
        }

        @Test
        @DisplayName("should return true when pendingAction has entries")
        void shouldReturnTrueWhenHasEntries() {
            ConversationDto dto = new ConversationDto();
            dto.setPendingAction(Map.of("waiting_for", "credential:gmail"));
            assertThat(dto.hasPendingAction()).isTrue();
        }
    }

    @Nested
    @DisplayName("getWaitingFor")
    class GetWaitingFor {

        @Test
        @DisplayName("should return null when pendingAction is null")
        void shouldReturnNullWhenNull() {
            ConversationDto dto = new ConversationDto();
            assertThat(dto.getWaitingFor()).isNull();
        }

        @Test
        @DisplayName("should return waiting_for value")
        void shouldReturnWaitingForValue() {
            ConversationDto dto = new ConversationDto();
            dto.setPendingAction(Map.of("waiting_for", "credential:gmail"));
            assertThat(dto.getWaitingFor()).isEqualTo("credential:gmail");
        }

        @Test
        @DisplayName("should return null when waiting_for key not present")
        void shouldReturnNullWhenKeyMissing() {
            ConversationDto dto = new ConversationDto();
            dto.setPendingAction(Map.of("other", "value"));
            assertThat(dto.getWaitingFor()).isNull();
        }
    }

    @Nested
    @DisplayName("Getters and setters")
    class GettersSetters {

        @Test
        @DisplayName("should get and set all fields")
        void shouldGetAndSetAllFields() {
            ConversationDto dto = new ConversationDto();
            dto.setId("conv-1");
            dto.setUserId("user-1");
            dto.setTitle("Title");
            dto.setModel("gpt-4");
            dto.setProvider("openai");
            dto.setWorkflowId("wf-1");
            dto.setActive(false);
            dto.setApprovedServices(Set.of("gmail"));
            dto.setCreatedAt(LocalDateTime.of(2024, 1, 1, 0, 0));
            dto.setUpdatedAt(LocalDateTime.of(2024, 1, 2, 0, 0));
            dto.setMessageCount(5L);

            assertThat(dto.getId()).isEqualTo("conv-1");
            assertThat(dto.getUserId()).isEqualTo("user-1");
            assertThat(dto.getTitle()).isEqualTo("Title");
            assertThat(dto.getModel()).isEqualTo("gpt-4");
            assertThat(dto.getProvider()).isEqualTo("openai");
            assertThat(dto.getWorkflowId()).isEqualTo("wf-1");
            assertThat(dto.getActive()).isFalse();
            assertThat(dto.getApprovedServices()).containsExactly("gmail");
            assertThat(dto.getCreatedAt()).isEqualTo(LocalDateTime.of(2024, 1, 1, 0, 0));
            assertThat(dto.getUpdatedAt()).isEqualTo(LocalDateTime.of(2024, 1, 2, 0, 0));
            assertThat(dto.getMessageCount()).isEqualTo(5L);
        }
    }
}
