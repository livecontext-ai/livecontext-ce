package com.apimarketplace.conversation.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Conversation Exceptions")
class ExceptionsTest {

    @Nested
    @DisplayName("ConversationInactiveException")
    class InactiveTests {

        @Test
        @DisplayName("should include conversation ID in message")
        void shouldIncludeConversationId() {
            ConversationInactiveException ex = new ConversationInactiveException("conv-123");
            assertThat(ex.getMessage()).isEqualTo("Conversation is inactive: conv-123");
        }

        @Test
        @DisplayName("should extend RuntimeException")
        void shouldExtendRuntimeException() {
            ConversationInactiveException ex = new ConversationInactiveException("conv-1");
            assertThat(ex).isInstanceOf(RuntimeException.class);
        }
    }

    @Nested
    @DisplayName("ConversationNotFoundException")
    class NotFoundTests {

        @Test
        @DisplayName("should include conversation ID in message")
        void shouldIncludeConversationId() {
            ConversationNotFoundException ex = new ConversationNotFoundException("conv-456");
            assertThat(ex.getMessage()).isEqualTo("Conversation not found: conv-456");
        }

        @Test
        @DisplayName("should extend RuntimeException")
        void shouldExtendRuntimeException() {
            ConversationNotFoundException ex = new ConversationNotFoundException("conv-1");
            assertThat(ex).isInstanceOf(RuntimeException.class);
        }
    }

    @Nested
    @DisplayName("InvalidMessageException")
    class InvalidMessageTests {

        @Test
        @DisplayName("should use provided message")
        void shouldUseProvidedMessage() {
            InvalidMessageException ex = new InvalidMessageException("missing role field");
            assertThat(ex.getMessage()).isEqualTo("missing role field");
        }

        @Test
        @DisplayName("should extend RuntimeException")
        void shouldExtendRuntimeException() {
            InvalidMessageException ex = new InvalidMessageException("error");
            assertThat(ex).isInstanceOf(RuntimeException.class);
        }
    }
}
