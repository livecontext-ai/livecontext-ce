package com.apimarketplace.conversation.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CreateConversationDto")
class CreateConversationDtoTest {

    @Nested
    @DisplayName("Constructors")
    class Constructors {

        @Test
        @DisplayName("should create with default constructor and default active")
        void shouldCreateWithDefault() {
            CreateConversationDto dto = new CreateConversationDto();
            assertThat(dto.getTitle()).isNull();
            assertThat(dto.getModel()).isNull();
            assertThat(dto.getProvider()).isNull();
            assertThat(dto.getWorkflowId()).isNull();
            assertThat(dto.getActive()).isTrue();
        }

        @Test
        @DisplayName("should create with args constructor")
        void shouldCreateWithArgs() {
            CreateConversationDto dto = new CreateConversationDto("Title", "gpt-4", "openai");

            assertThat(dto.getTitle()).isEqualTo("Title");
            assertThat(dto.getModel()).isEqualTo("gpt-4");
            assertThat(dto.getProvider()).isEqualTo("openai");
        }
    }

    @Nested
    @DisplayName("Getters and setters")
    class GettersSetters {

        @Test
        @DisplayName("should get and set all fields")
        void shouldGetAndSetAllFields() {
            CreateConversationDto dto = new CreateConversationDto();
            dto.setTitle("My Chat");
            dto.setModel("claude-3");
            dto.setProvider("anthropic");
            dto.setWorkflowId("wf-1");
            dto.setActive(false);

            assertThat(dto.getTitle()).isEqualTo("My Chat");
            assertThat(dto.getModel()).isEqualTo("claude-3");
            assertThat(dto.getProvider()).isEqualTo("anthropic");
            assertThat(dto.getWorkflowId()).isEqualTo("wf-1");
            assertThat(dto.getActive()).isFalse();
        }
    }

    @Nested
    @DisplayName("toString")
    class ToStringTest {

        @Test
        @DisplayName("should include key fields in toString")
        void shouldIncludeKeyFields() {
            CreateConversationDto dto = new CreateConversationDto("Title", "gpt-4", "openai");
            String str = dto.toString();

            assertThat(str).contains("Title");
            assertThat(str).contains("gpt-4");
            assertThat(str).contains("openai");
        }
    }
}
