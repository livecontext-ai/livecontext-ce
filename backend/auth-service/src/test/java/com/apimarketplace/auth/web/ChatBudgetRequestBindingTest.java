package com.apimarketplace.auth.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire shape of {@code /api/credits/check-chat}'s body (V494).
 *
 * <p>The record gained a {@code sourceType} component plus a 4-arg convenience
 * constructor for the callers that predate it. Both are invisible to every other test
 * in this module, which builds the record in Java - and a record with two constructors
 * is exactly the shape where Jackson can pick the wrong creator and start binding
 * fields positionally or not at all. The failure would be silent and total: a gate
 * that reads {@code null} for every field answers about no model, no source type, and
 * no token estimate.
 */
@DisplayName("ChatBudgetRequest - JSON binding")
class ChatBudgetRequestBindingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("binds the full V494 body, source type included")
    void bindsTheFullBody() throws Exception {
        CreditController.ChatBudgetRequest r = mapper.readValue("""
                {"provider":"anthropic","model":"claude-haiku-4-5",
                 "estimatedPromptTokens":1000,"estimatedCompletionTokens":500,
                 "sourceType":"CE_LLM_RELAY"}
                """, CreditController.ChatBudgetRequest.class);

        assertThat(r.provider()).isEqualTo("anthropic");
        assertThat(r.model()).isEqualTo("claude-haiku-4-5");
        assertThat(r.estimatedPromptTokens()).isEqualTo(1000);
        assertThat(r.estimatedCompletionTokens()).isEqualTo(500);
        assertThat(r.sourceType()).isEqualTo("CE_LLM_RELAY");
    }

    @Test
    @DisplayName("binds a body from a caller that predates the source type, leaving it null")
    void bindsTheLegacyBody() throws Exception {
        // A service still sending the pre-V494 shape must not fail to bind, and must
        // read as "no source type stated" so the controller applies its default rather
        // than gating against a blank string.
        CreditController.ChatBudgetRequest r = mapper.readValue("""
                {"provider":"openai","model":"gpt-5",
                 "estimatedPromptTokens":10,"estimatedCompletionTokens":20}
                """, CreditController.ChatBudgetRequest.class);

        assertThat(r.provider()).isEqualTo("openai");
        assertThat(r.estimatedCompletionTokens()).isEqualTo(20);
        assertThat(r.sourceType()).isNull();
    }

    @Test
    @DisplayName("a model id carrying / and : survives the round trip verbatim")
    void bindsARouterModelId() throws Exception {
        // The ids that motivated the URI-template fix on the GET gate. Here they travel
        // in a JSON body, where no encoding applies at all - which is why this endpoint
        // was always right about them and the GET one had to be fixed.
        CreditController.ChatBudgetRequest r = mapper.readValue("""
                {"provider":"openrouter","model":"meta/llama-3:70b",
                 "estimatedPromptTokens":1,"estimatedCompletionTokens":1}
                """, CreditController.ChatBudgetRequest.class);

        assertThat(r.model()).isEqualTo("meta/llama-3:70b");
    }
}
