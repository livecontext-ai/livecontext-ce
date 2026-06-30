package com.apimarketplace.conversation.streaming;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StreamState")
class StreamStateTest {

    @Nested
    @DisplayName("isActive")
    class IsActive {

        @Test
        @DisplayName("CREATED should be active")
        void createdShouldBeActive() {
            assertThat(StreamState.CREATED.isActive()).isTrue();
        }

        @Test
        @DisplayName("STREAMING should be active")
        void streamingShouldBeActive() {
            assertThat(StreamState.STREAMING.isActive()).isTrue();
        }

        @ParameterizedTest
        @EnumSource(value = StreamState.class, names = {"COMPLETED", "STOPPED_BY_USER", "AWAITING_APPROVAL", "ERROR", "INTERRUPTED"})
        @DisplayName("terminal states should not be active")
        void terminalStatesShouldNotBeActive(StreamState state) {
            assertThat(state.isActive()).isFalse();
        }
    }

    @Nested
    @DisplayName("isReconnectable")
    class IsReconnectable {

        @Test
        @DisplayName("STREAMING should be reconnectable")
        void streamingShouldBeReconnectable() {
            assertThat(StreamState.STREAMING.isReconnectable()).isTrue();
        }

        @Test
        @DisplayName("CREATED should not be reconnectable")
        void createdShouldNotBeReconnectable() {
            assertThat(StreamState.CREATED.isReconnectable()).isFalse();
        }

        @ParameterizedTest
        @EnumSource(value = StreamState.class, names = {"COMPLETED", "STOPPED_BY_USER", "ERROR", "AWAITING_APPROVAL", "INTERRUPTED"})
        @DisplayName("terminal states should not be reconnectable")
        void terminalStatesShouldNotBeReconnectable(StreamState state) {
            assertThat(state.isReconnectable()).isFalse();
        }
    }

    @Nested
    @DisplayName("isTerminal")
    class IsTerminal {

        @ParameterizedTest
        @EnumSource(value = StreamState.class, names = {"COMPLETED", "STOPPED_BY_USER", "AWAITING_APPROVAL", "ERROR", "INTERRUPTED"})
        @DisplayName("should be terminal for end states")
        void shouldBeTerminal(StreamState state) {
            assertThat(state.isTerminal()).isTrue();
        }

        @ParameterizedTest
        @EnumSource(value = StreamState.class, names = {"CREATED", "STREAMING"})
        @DisplayName("should not be terminal for active states")
        void shouldNotBeTerminal(StreamState state) {
            assertThat(state.isTerminal()).isFalse();
        }
    }

    @Nested
    @DisplayName("isAwaitingUserAction")
    class IsAwaitingUserAction {

        @Test
        @DisplayName("AWAITING_APPROVAL should be awaiting user action")
        void awaitingApprovalShouldBeAwaiting() {
            assertThat(StreamState.AWAITING_APPROVAL.isAwaitingUserAction()).isTrue();
        }

        @ParameterizedTest
        @EnumSource(value = StreamState.class, names = {"CREATED", "STREAMING", "COMPLETED", "STOPPED_BY_USER", "ERROR", "INTERRUPTED"})
        @DisplayName("other states should not be awaiting user action")
        void otherStatesShouldNotBeAwaiting(StreamState state) {
            assertThat(state.isAwaitingUserAction()).isFalse();
        }
    }
}
