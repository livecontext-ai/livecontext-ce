package com.apimarketplace.common.credit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ChatCreditRefusal - the chat-side vocabulary for an empty wallet")
class ChatCreditRefusalTest {

    @Nested
    @DisplayName("recognised")
    class Recognised {

        @Test
        @DisplayName("the bare wording the sync-chat endpoint returns with its 402")
        void bareWording() {
            assertThat(ChatCreditRefusal.isChatCreditRefusal(ChatCreditRefusal.MESSAGE)).isTrue();
        }

        @Test
        @DisplayName("the exact transport-wrapped string production relayed on 2026-09-17")
        void theStringProductionActuallyRelayed() {
            // Verbatim from the orchestrator pod. This is the case the shipped fix missed: the
            // classifier was only ever asked about the workflow wording, so this answered
            // "not user-actionable" and kept ERROR every 30 minutes.
            String relayed = "402  on POST request for \"http://livecontext-livecontext-conversation:8087"
                + "/api/internal/chat/sync\": \"{\"conversationId\":\"3dddb2d1-5c16-4b57-b5b1-534c20d739d7\","
                + "\"error\":\"Insufficient credits\",\"success\":false}\"";

            assertThat(ChatCreditRefusal.isChatCreditRefusal(relayed)).isTrue();
        }

        @Test
        @DisplayName("the credit client's own prefixed wording, which a task result can carry")
        void creditClientPrefixedWording() {
            // CreditConsumptionClient emits this exact literal on its 402 branch. It does NOT
            // reach this class through ConversationClient - a task result carries either the
            // body's error field or the transport sentence, never this one - so the honest
            // claim is narrower: it reaches UserActionableFailure through orchestrator's own
            // paths (a catalog tool refusal relayed to a step node), and prefix tolerance is
            // what lets one classifier serve both. Stating the reachable path matters: a
            // fixture whose provenance is invented proves only that contains() works, which is
            // the mistake this whole change exists to undo.
            assertThat(ChatCreditRefusal.isChatCreditRefusal("402 Insufficient credits")).isTrue();
        }
    }

    @Nested
    @DisplayName("refused - an unknown shape must stay an error")
    class Refused {

        @Test
        @DisplayName("null and blank")
        void nullAndBlank() {
            assertThat(ChatCreditRefusal.isChatCreditRefusal(null)).isFalse();
            assertThat(ChatCreditRefusal.isChatCreditRefusal("")).isFalse();
            assertThat(ChatCreditRefusal.isChatCreditRefusal("   ")).isFalse();
        }

        @Test
        @DisplayName("a genuine platform fault is never softened")
        void platformFault() {
            assertThat(ChatCreditRefusal.isChatCreditRefusal("NullPointerException in the agent loop"))
                .isFalse();
            assertThat(ChatCreditRefusal.isChatCreditRefusal(
                "500 on POST request for \"http://conversation:8087/api/internal/chat/sync\"")).isFalse();
        }

        @Test
        @DisplayName("a near-miss wording is not close enough - the constant is the contract")
        void nearMiss() {
            // If the producer is ever re-worded, this classifier must go quiet rather than
            // guess. Silence is visible (the line returns to ERROR); a guess is not.
            assertThat(ChatCreditRefusal.isChatCreditRefusal("insufficient credit")).isFalse();
            assertThat(ChatCreditRefusal.isChatCreditRefusal("Not enough credits")).isFalse();
        }
    }
}
