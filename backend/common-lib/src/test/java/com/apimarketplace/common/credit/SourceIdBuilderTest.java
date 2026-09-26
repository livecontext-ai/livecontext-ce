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

    // ── Cross-tool isolation ────────────────────────────────────────────────

    @Test
    @DisplayName("web-fetch and web-search keys never collide (distinct prefixes)")
    void crossToolNoCollision() {
        String ws = SourceIdBuilder.webSearchDebitChat("s", "t", 0);
        String wf = SourceIdBuilder.webFetchDebitChat("s", "t", 0);
        assertThat(ws).isNotEqualTo(wf);
    }

    @Test
    @DisplayName("classifiers correctly identify web-search and web-fetch debits")
    void toolClassifiers() {
        String ws = SourceIdBuilder.webSearchDebitChat("s", "t", 0);
        String wf = SourceIdBuilder.webFetchDebitWorkflow("r", "s", 0);
        String markup = SourceIdBuilder.markupDebit("r", "s", 0, 0, 0, 0);

        assertThat(SourceIdBuilder.isWebSearchDebit(ws)).isTrue();
        assertThat(SourceIdBuilder.isWebSearchDebit(wf)).isFalse();
        assertThat(SourceIdBuilder.isWebSearchDebit(markup)).isFalse();

        assertThat(SourceIdBuilder.isWebFetchDebit(wf)).isTrue();
        assertThat(SourceIdBuilder.isWebFetchDebit(ws)).isFalse();
        assertThat(SourceIdBuilder.isWebFetchDebit(markup)).isFalse();

        assertThat(SourceIdBuilder.isWebSearchDebit(null)).isFalse();
        assertThat(SourceIdBuilder.isWebFetchDebit(null)).isFalse();
    }

    // ── V148+ scope-aware keys ──────────────────────────────────────────────

    @Test
    @DisplayName("markupDebitWithCall: workflow per-call shape preserves runId+stepId and ends in the callRef")
    void markupDebitWithCallShape() {
        String id = SourceIdBuilder.markupDebitWithCall("run-1", "mcp:image", "call-7");
        assertThat(id).isEqualTo("platform-markup:RUN:run-1:step:mcp:image:call-7");
    }

    @Test
    @DisplayName("markupDebitChat: chat shape - distinct from workflow")
    void markupDebitChatShape() {
        String id = SourceIdBuilder.markupDebitChat("stream-abc", "openai/openai-create-image", "call-2");
        assertThat(id).isEqualTo("platform-markup:STREAM:stream-abc:openai/openai-create-image:call-2");
    }

    /**
     * The tail is not decoration: the ledger is keyed on the whole sourceId and
     * a repeat is answered as "already reserved, zero debit". Two calls that
     * differ only in their callRef must therefore produce two different keys, or
     * the second one is a free generation.
     */
    @Test
    @DisplayName("two calls in the SAME run and step differ only by callRef, and that is what makes them two charges")
    void differentCallRefsInOneStepProduceDifferentKeys() {
        String first = SourceIdBuilder.markupDebitWithCall("run-1", "agent:generate", "call-a");
        String second = SourceIdBuilder.markupDebitWithCall("run-1", "agent:generate", "call-b");
        assertThat(first).isNotEqualTo(second);

        String firstChat = SourceIdBuilder.markupDebitChat("stream-1", "seedance/create", "call-a");
        String secondChat = SourceIdBuilder.markupDebitChat("stream-1", "seedance/create", "call-b");
        assertThat(firstChat).isNotEqualTo(secondChat);
    }

    /**
     * A defaulted callRef is exactly the defect: every call in the scope keys
     * the same row and only the first is charged. Loud failure beats a silent
     * giveaway.
     */
    @Test
    @DisplayName("a missing callRef is rejected rather than defaulted, on both scope shapes")
    void blankCallRefIsRejected() {
        assertThatThrownBy(() -> SourceIdBuilder.markupDebitWithCall("r", "s", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("callRef");
        assertThatThrownBy(() -> SourceIdBuilder.markupDebitWithCall("r", "s", "  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceIdBuilder.markupDebitChat("s", "t", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("callRef");
        assertThatThrownBy(() -> SourceIdBuilder.markupDebitChat("s", "t", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The legacy 6-segment rows are still in the prod ledger. A new per-call key
     * must not be able to land on one of them.
     */
    @Test
    @DisplayName("a per-call key never collides with a legacy 6-segment markupDebit row")
    void doesNotCollideWithLegacyShape() {
        String legacy = SourceIdBuilder.markupDebit("run-1", "agent:generate", 0, 0, 0, 0);
        String perCall = SourceIdBuilder.markupDebitWithCall("run-1", "agent:generate", "0");
        assertThat(perCall).isNotEqualTo(legacy);
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
                SourceIdBuilder.markupDebitWithCall("r", "s", "call-0"))).isTrue();
        assertThat(SourceIdBuilder.isMarkupDebit(
                SourceIdBuilder.markupDebitChat("s", "t", "call-0"))).isTrue();
        assertThat(SourceIdBuilder.isMarkupDebit(
                SourceIdBuilder.markupReserveInit("RUN", "r", 7L))).isTrue();
        // Non-markup keys still reject
        assertThat(SourceIdBuilder.isMarkupDebit(
                SourceIdBuilder.webSearchDebitChat("s", "t", 0))).isFalse();
        assertThat(SourceIdBuilder.isMarkupDebit("random:string")).isFalse();
        assertThat(SourceIdBuilder.isMarkupDebit(null)).isFalse();
    }

    @Test
    @DisplayName("STREAM and RUN scope keys never collide on same id (different discriminators)")
    void scopeIsolation() {
        String run = SourceIdBuilder.markupDebitWithCall("abc", "step", "call-0");
        String stream = SourceIdBuilder.markupDebitChat("abc", "step", "call-0");
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

    @Test
    @DisplayName("marketplacePurchase keys the PURCHASE (buyer organization + publication), never the publication alone")
    void marketplacePurchaseKeyShape() {
        String key = SourceIdBuilder.marketplacePurchase("org-1", "pub-1");
        assertThat(key).isEqualTo("marketplace-purchase:org-1:pub-1");
        // Two buyers of one publication must never share a ledger key (idx_cl_source_id_unique is
        // global), and the same purchase must always rebuild the same key (retry = replay).
        assertThat(SourceIdBuilder.marketplacePurchase("org-2", "pub-1")).isNotEqualTo(key);
        assertThat(SourceIdBuilder.marketplacePurchase("org-1", "pub-1")).isEqualTo(key);
        assertThat(SourceIdBuilder.isMarketplacePurchase(key)).isTrue();
        assertThat(SourceIdBuilder.isMarketplacePurchase("pub-1")).isFalse();
        assertThat(SourceIdBuilder.isMarketplacePurchase(null)).isFalse();
    }

    @Test
    @DisplayName("marketplacePurchase refuses a missing buyer organization or publication instead of building a shared key")
    void marketplacePurchaseRequiresBothParts() {
        assertThatThrownBy(() -> SourceIdBuilder.marketplacePurchase(null, "pub-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceIdBuilder.marketplacePurchase("  ", "pub-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceIdBuilder.marketplacePurchase("org-1", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceIdBuilder.marketplacePurchase("org-1", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
