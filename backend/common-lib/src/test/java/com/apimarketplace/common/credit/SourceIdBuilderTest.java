package com.apimarketplace.common.credit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SourceIdBuilder Tests")
class SourceIdBuilderTest {

    @Test
    @DisplayName("markupDebit: full shape with all context fields")
    void markupDebitFullShape() {
        String id = SourceIdBuilder.markupDebit("run-abc", "mcp:send_email", 2, 1, 3, 5);
        assertThat(id).isEqualTo("platform-markup:RUN:run-abc:step:mcp:send_email:2:1:3:5");
    }

    @Test
    @DisplayName("markupDebit: two calls with identical inputs produce identical keys (idempotency)")
    void markupDebitIdempotent() {
        String a = SourceIdBuilder.markupDebit("r1", "s1", 0, 0, 0, 0);
        String b = SourceIdBuilder.markupDebit("r1", "s1", 0, 0, 0, 0);
        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("markupDebit: different step context yields different keys")
    void markupDebitDifferentContext() {
        String a = SourceIdBuilder.markupDebit("r1", "s1", 0, 0, 0, 0);
        String b = SourceIdBuilder.markupDebit("r1", "s1", 0, 0, 0, 1);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("markupDebit: null runId/stepId rejected")
    void markupDebitRejectsNulls() {
        assertThatThrownBy(() -> SourceIdBuilder.markupDebit(null, "s", 0, 0, 0, 0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SourceIdBuilder.markupDebit("r", null, 0, 0, 0, 0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("isMarkupDebit classifier")
    void classifiers() {
        String debit = SourceIdBuilder.markupDebit("r1", "s1", 0, 0, 0, 0);
        assertThat(SourceIdBuilder.isMarkupDebit(debit)).isTrue();
        assertThat(SourceIdBuilder.isMarkupDebit(null)).isFalse();
        assertThat(SourceIdBuilder.isMarkupDebit("some-other-key")).isFalse();
    }

    // ── Web search ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("webSearchDebitChat: shape uses :CHAT: scope discriminator")
    void webSearchChatShape() {
        String id = SourceIdBuilder.webSearchDebitChat("stream-7", "tool-call-9", 0);
        assertThat(id).isEqualTo("web-search:CHAT:stream-7:tool-call-9:0");
    }

    @Test
    @DisplayName("webSearchDebitWorkflow: shape uses :RUN: scope discriminator")
    void webSearchWorkflowShape() {
        String id = SourceIdBuilder.webSearchDebitWorkflow("run-abc", "mcp:search", 2);
        assertThat(id).isEqualTo("web-search:RUN:run-abc:step:mcp:search:2");
    }

    @Test
    @DisplayName("webSearch: chat and workflow keys never collide even on identical ids")
    void webSearchScopesDoNotCollide() {
        // Pathological case: a chat streamId equals a workflow runId, AND a
        // chat toolCallId equals a workflow stepId. Without the scope prefix
        // these would collapse to the same string and the unique constraint
        // on source_id would block one of the two ledger rows.
        String chat = SourceIdBuilder.webSearchDebitChat("same-id", "same-id-2", 0);
        String wf = SourceIdBuilder.webSearchDebitWorkflow("same-id", "same-id-2", 0);
        assertThat(chat).isNotEqualTo(wf);
    }

    @Test
    @DisplayName("webSearch: same inputs produce same key (idempotency)")
    void webSearchIdempotent() {
        assertThat(SourceIdBuilder.webSearchDebitChat("s", "t", 1))
                .isEqualTo(SourceIdBuilder.webSearchDebitChat("s", "t", 1));
        assertThat(SourceIdBuilder.webSearchDebitWorkflow("r", "s", 1))
                .isEqualTo(SourceIdBuilder.webSearchDebitWorkflow("r", "s", 1));
    }

    @Test
    @DisplayName("webSearch: null inputs rejected")
    void webSearchRejectsNulls() {
        assertThatThrownBy(() -> SourceIdBuilder.webSearchDebitChat(null, "t", 0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SourceIdBuilder.webSearchDebitChat("s", null, 0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SourceIdBuilder.webSearchDebitWorkflow(null, "s", 0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SourceIdBuilder.webSearchDebitWorkflow("r", null, 0))
                .isInstanceOf(NullPointerException.class);
    }

    // ── Image generation ────────────────────────────────────────────────────

    @Test
    @DisplayName("imageGenerationDebitChat: shape uses :CHAT: scope discriminator")
    void imageGenChatShape() {
        String id = SourceIdBuilder.imageGenerationDebitChat("stream-7", "tool-call-9", 0);
        assertThat(id).isEqualTo("image-generation:CHAT:stream-7:tool-call-9:0");
    }

    @Test
    @DisplayName("imageGenerationDebitWorkflow: shape uses :RUN: scope discriminator")
    void imageGenWorkflowShape() {
        String id = SourceIdBuilder.imageGenerationDebitWorkflow("run-abc", "mcp:image", 5);
        assertThat(id).isEqualTo("image-generation:RUN:run-abc:step:mcp:image:5");
    }

    @Test
    @DisplayName("imageGeneration: chat and workflow keys never collide")
    void imageGenScopesDoNotCollide() {
        String chat = SourceIdBuilder.imageGenerationDebitChat("x", "y", 0);
        String wf = SourceIdBuilder.imageGenerationDebitWorkflow("x", "y", 0);
        assertThat(chat).isNotEqualTo(wf);
    }

    // ── Cross-tool isolation ────────────────────────────────────────────────

    @Test
    @DisplayName("web-search and image-generation keys never collide (distinct prefixes)")
    void crossToolNoCollision() {
        String ws = SourceIdBuilder.webSearchDebitChat("s", "t", 0);
        String ig = SourceIdBuilder.imageGenerationDebitChat("s", "t", 0);
        assertThat(ws).isNotEqualTo(ig);
    }

    @Test
    @DisplayName("classifiers correctly identify web-search and image-generation debits")
    void toolClassifiers() {
        String ws = SourceIdBuilder.webSearchDebitChat("s", "t", 0);
        String ig = SourceIdBuilder.imageGenerationDebitWorkflow("r", "s", 0);
        String markup = SourceIdBuilder.markupDebit("r", "s", 0, 0, 0, 0);

        assertThat(SourceIdBuilder.isWebSearchDebit(ws)).isTrue();
        assertThat(SourceIdBuilder.isWebSearchDebit(ig)).isFalse();
        assertThat(SourceIdBuilder.isWebSearchDebit(markup)).isFalse();

        assertThat(SourceIdBuilder.isImageGenerationDebit(ig)).isTrue();
        assertThat(SourceIdBuilder.isImageGenerationDebit(ws)).isFalse();
        assertThat(SourceIdBuilder.isImageGenerationDebit(markup)).isFalse();

        assertThat(SourceIdBuilder.isWebSearchDebit(null)).isFalse();
        assertThat(SourceIdBuilder.isImageGenerationDebit(null)).isFalse();
    }

    // ── V148+ scope-aware keys ──────────────────────────────────────────────

    @Test
    @DisplayName("markupDebitWithCall: 7-segment workflow per-call shape preserves runId+stepId+coords")
    void markupDebitWithCallShape() {
        String id = SourceIdBuilder.markupDebitWithCall("run-1", "mcp:image", 2, 1, 0, 3, 7);
        assertThat(id).isEqualTo("platform-markup:RUN:run-1:step:mcp:image:2:1:0:3:7");
    }

    @Test
    @DisplayName("markupDebitChat: 5-segment chat shape - distinct from workflow")
    void markupDebitChatShape() {
        String id = SourceIdBuilder.markupDebitChat("stream-abc", "openai/openai-create-image", 2);
        assertThat(id).isEqualTo("platform-markup:STREAM:stream-abc:openai/openai-create-image:2");
    }

    @Test
    @DisplayName("markupReserveInit: 4-segment INIT shape for run-init reservations")
    void markupReserveInitShape() {
        String id = SourceIdBuilder.markupReserveInit("RUN", "run-1", 7L);
        assertThat(id).isEqualTo("platform-markup:INIT:RUN:run-1:7");
    }

    @Test
    @DisplayName("isMarkupDebit: matches RUN, STREAM, and INIT prefixes (V148 widening)")
    void isMarkupDebitWidened() {
        assertThat(SourceIdBuilder.isMarkupDebit(
                SourceIdBuilder.markupDebit("r", "s", 0, 0, 0, 0))).isTrue();
        assertThat(SourceIdBuilder.isMarkupDebit(
                SourceIdBuilder.markupDebitWithCall("r", "s", 0, 0, 0, 0, 0))).isTrue();
        assertThat(SourceIdBuilder.isMarkupDebit(
                SourceIdBuilder.markupDebitChat("s", "t", 0))).isTrue();
        assertThat(SourceIdBuilder.isMarkupDebit(
                SourceIdBuilder.markupReserveInit("RUN", "r", 7L))).isTrue();
        // Non-markup keys still reject
        assertThat(SourceIdBuilder.isMarkupDebit(
                SourceIdBuilder.webSearchDebitChat("s", "t", 0))).isFalse();
        assertThat(SourceIdBuilder.isMarkupDebit(
                SourceIdBuilder.imageGenerationDebitChat("s", "t", 0))).isFalse();
        assertThat(SourceIdBuilder.isMarkupDebit("random:string")).isFalse();
        assertThat(SourceIdBuilder.isMarkupDebit(null)).isFalse();
    }

    @Test
    @DisplayName("STREAM and RUN scope keys never collide on same id (different discriminators)")
    void scopeIsolation() {
        String run = SourceIdBuilder.markupDebitWithCall("abc", "step", 0, 0, 0, 0, 0);
        String stream = SourceIdBuilder.markupDebitChat("abc", "step", 0);
        assertThat(run).isNotEqualTo(stream);
        assertThat(run).contains(":RUN:");
        assertThat(stream).contains(":STREAM:");
    }

    @Test
    @DisplayName("markupReserveInit: rejects null scope")
    void markupReserveInitNulls() {
        assertThatThrownBy(() -> SourceIdBuilder.markupReserveInit(null, "x", 1L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SourceIdBuilder.markupReserveInit("RUN", null, 1L))
                .isInstanceOf(NullPointerException.class);
    }
}
