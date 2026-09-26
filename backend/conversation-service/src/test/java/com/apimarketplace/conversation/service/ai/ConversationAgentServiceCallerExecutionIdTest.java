package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.conversation.dto.ChatRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The id a synchronous run is recorded under.
 *
 * <p>Prod 2026-09-23: a task locked itself to one id and its run was recorded under another, so
 * reading the task's run with the id the task showed found nothing. A caller that already holds
 * the id now passes it; anything that is not a UUID is ignored, since it becomes a primary key.
 */
@DisplayName("ConversationAgentService - the id a sync run is recorded under")
class ConversationAgentServiceCallerExecutionIdTest {

    @Test
    @DisplayName("the caller's id is the run's id")
    void callerIdIsUsed() {
        String id = UUID.randomUUID().toString();
        ChatRequest request = new ChatRequest();
        request.setExecutionId(id);

        assertThat(ConversationAgentService.callerExecutionIdOrNew(request)).isEqualTo(id);
    }

    @Test
    @DisplayName("an upper-case or padded id is normalised, not refused")
    void callerIdIsNormalised() {
        UUID id = UUID.randomUUID();
        ChatRequest request = new ChatRequest();
        request.setExecutionId("  " + id.toString().toUpperCase() + " ");

        assertThat(ConversationAgentService.callerExecutionIdOrNew(request)).isEqualTo(id.toString());
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = {" ", "not-an-id", "1234", "'; drop table x; --"})
    @DisplayName("no usable id: a fresh one, never the given text")
    void unusableIdGetsAFreshOne(String given) {
        ChatRequest request = new ChatRequest();
        request.setExecutionId(given);

        String id = ConversationAgentService.callerExecutionIdOrNew(request);

        assertThat(UUID.fromString(id)).isNotNull();
        assertThat(id).isNotEqualTo(given);
    }

    @Test
    @DisplayName("two requests without an id never share one")
    void freshIdsAreDistinct() {
        assertThat(ConversationAgentService.callerExecutionIdOrNew(new ChatRequest()))
                .isNotEqualTo(ConversationAgentService.callerExecutionIdOrNew(new ChatRequest()));
        assertThat(ConversationAgentService.callerExecutionIdOrNew(null)).isNotBlank();
    }
}
