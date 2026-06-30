package com.apimarketplace.conversation.mapper;

import com.apimarketplace.conversation.dto.ConversationDto;
import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.entity.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationMapperTest {

    private final ConversationMapper mapper = new ConversationMapper();

    @Test
    void toDtoCopiesCoreFields() {
        Conversation conversation = new Conversation("user", "title", "model", "provider");
        conversation.setId("conv-id");
        conversation.setMessages(List.of(new Message()));
        conversation.setCreatedAt(LocalDateTime.now());
        conversation.setUpdatedAt(LocalDateTime.now());

        ConversationDto dto = mapper.toDto(conversation);

        assertThat(dto.getId()).isEqualTo("conv-id");
        assertThat(dto.getMessageCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("toDto copies BOTH the legacy single pending_action and the pending_actions list")
    void toDtoCopiesPendingActionsList() {
        Conversation conversation = new Conversation("user", "title", "model", "provider");
        Map<String, Object> svc = Map.of("waiting_for", "service_approval",
                "services", List.of(Map.of("serviceType", "gmail")));
        Map<String, Object> auth = Map.of("waiting_for", "tool_authorization", "rule", "application:acquire");
        conversation.setPendingAction(svc);
        conversation.setPendingActions(List.of(svc, auth));

        ConversationDto dto = mapper.toDto(conversation);

        assertThat(dto.getPendingAction()).isEqualTo(svc);
        assertThat(dto.getPendingActions()).containsExactly(svc, auth);
    }

    // -------------------------------------------------------------------------
    // Compaction-marker projection (Stage 3 follow-up #52)
    //
    // The UI renders a "prior context summarised" divider between COLD and
    // HOT+WARM turns. Marker is a lightweight projection of summary_cold JSONB;
    // these tests pin the defensive read paths because the blob is
    // model-authored and can arrive partially-populated.
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("compactionMarker is null when summary_cold is absent - no summary generated yet")
    void compactionMarkerNullWhenNoSummary() {
        Conversation conversation = new Conversation("user", "title", "model", "provider");
        conversation.setSummaryCold(null);

        ConversationDto dto = mapper.toDto(conversation);

        assertThat(dto.getCompactionMarker()).isNull();
    }

    @Test
    @DisplayName("compactionMarker is null when summary_cold is an empty map")
    void compactionMarkerNullWhenEmptyMap() {
        Conversation conversation = new Conversation("user", "title", "model", "provider");
        conversation.setSummaryCold(new HashMap<>());

        ConversationDto dto = mapper.toDto(conversation);

        assertThat(dto.getCompactionMarker()).isNull();
    }

    @Test
    @DisplayName("compactionMarker is null when turns_covered is missing - UI can't place divider")
    void compactionMarkerNullWhenTurnsCoveredMissing() {
        // Missing turns_covered is the only field that blocks rendering: without
        // the index list, the frontend has no anchor for the divider.
        Map<String, Object> summary = new HashMap<>();
        summary.put("generated_at", "2026-04-21T10:00:00Z");
        summary.put("model", "claude-haiku-4-5");
        // No turns_covered key.
        Conversation conversation = new Conversation("user", "title", "model", "provider");
        conversation.setSummaryCold(summary);

        ConversationDto dto = mapper.toDto(conversation);

        assertThat(dto.getCompactionMarker()).isNull();
    }

    @Test
    @DisplayName("compactionMarker populated from valid summary_cold envelope")
    void compactionMarkerPopulatedForValidEnvelope() {
        Map<String, Object> summary = new HashMap<>();
        summary.put("turns_covered", List.of(0, 1, 2, 3));
        summary.put("generated_at", "2026-04-21T10:00:00Z");
        summary.put("model", "claude-haiku-4-5");
        Conversation conversation = new Conversation("user", "title", "model", "provider");
        conversation.setSummaryCold(summary);

        ConversationDto dto = mapper.toDto(conversation);

        ConversationDto.CompactionMarker marker = dto.getCompactionMarker();
        assertThat(marker).isNotNull();
        assertThat(marker.getTurnsCovered()).containsExactly(0, 1, 2, 3);
        assertThat(marker.getGeneratedAt()).isEqualTo("2026-04-21T10:00:00Z");
        assertThat(marker.getModel()).isEqualTo("claude-haiku-4-5");
        // No status key in the envelope -> null on the marker (legacy rows).
        assertThat(marker.getStatus()).isNull();
    }

    @Test
    @DisplayName("compactionMarker surfaces the envelope status so the UI can flag a stale summary")
    void compactionMarkerSurfacesStatus() {
        Map<String, Object> summary = new HashMap<>();
        summary.put("turns_covered", List.of(0, 1));
        summary.put("status", "stale");
        Conversation conversation = new Conversation("user", "title", "model", "provider");
        conversation.setSummaryCold(summary);

        ConversationDto dto = mapper.toDto(conversation);

        assertThat(dto.getCompactionMarker()).isNotNull();
        assertThat(dto.getCompactionMarker().getStatus()).isEqualTo("stale");
    }

    @Test
    @DisplayName("compactionMarker passes an 'active' status through unchanged")
    void compactionMarkerPassesActiveStatusThrough() {
        Map<String, Object> summary = new HashMap<>();
        summary.put("turns_covered", List.of(0, 1));
        summary.put("status", "active");
        Conversation conversation = new Conversation("user", "title", "model", "provider");
        conversation.setSummaryCold(summary);

        ConversationDto dto = mapper.toDto(conversation);

        assertThat(dto.getCompactionMarker().getStatus()).isEqualTo("active");
    }

    @Test
    @DisplayName("compactionMarker tolerates a non-string status (defensive null, no throw)")
    void compactionMarkerNonStringStatusIsNull() {
        Map<String, Object> summary = new HashMap<>();
        summary.put("turns_covered", List.of(0));
        summary.put("status", 42);
        Conversation conversation = new Conversation("user", "title", "model", "provider");
        conversation.setSummaryCold(summary);

        ConversationDto dto = mapper.toDto(conversation);

        assertThat(dto.getCompactionMarker()).isNotNull();
        assertThat(dto.getCompactionMarker().getStatus()).isNull();
    }

    @Test
    @DisplayName("compactionMarker tolerates partial envelope: missing generated_at/model still projects turns")
    void compactionMarkerPartialEnvelopeProjectsTurns() {
        // Pre-schema-v1 rows or model-malformed outputs may drop generated_at
        // or model. The marker still renders - the divider is the critical UI;
        // timestamp/model are decoration the UI can omit gracefully.
        Map<String, Object> summary = new HashMap<>();
        summary.put("turns_covered", List.of(0, 1));
        Conversation conversation = new Conversation("user", "title", "model", "provider");
        conversation.setSummaryCold(summary);

        ConversationDto dto = mapper.toDto(conversation);

        ConversationDto.CompactionMarker marker = dto.getCompactionMarker();
        assertThat(marker).isNotNull();
        assertThat(marker.getTurnsCovered()).containsExactly(0, 1);
        assertThat(marker.getGeneratedAt()).isNull();
        assertThat(marker.getModel()).isNull();
    }

    @Test
    @DisplayName("compactionMarker filters non-Number entries from turns_covered - no classcast/NPE")
    void compactionMarkerFiltersNonNumberEntries() {
        // Defensive read: JSONB deserialisation may produce mixed-type lists on
        // hand-edited or legacy rows. Mapper must skip strings/nulls instead
        // of throwing ClassCastException when the DTO reaches the UI.
        Map<String, Object> summary = new HashMap<>();
        List<Object> mixed = new ArrayList<>();
        mixed.add("not-a-number");
        mixed.add(null);
        mixed.add(2);
        mixed.add(5L);       // Long is a Number subclass - counts
        summary.put("turns_covered", mixed);
        summary.put("generated_at", "2026-04-21T10:00:00Z");
        summary.put("model", "claude-haiku-4-5");
        Conversation conversation = new Conversation("user", "title", "model", "provider");
        conversation.setSummaryCold(summary);

        ConversationDto dto = mapper.toDto(conversation);

        ConversationDto.CompactionMarker marker = dto.getCompactionMarker();
        assertThat(marker).isNotNull();
        assertThat(marker.getTurnsCovered()).containsExactly(2, 5);
    }

    @Test
    @DisplayName("compactionMarker is null when turns_covered is present but contains only non-Number entries")
    void compactionMarkerNullWhenTurnsCoveredAllInvalid() {
        Map<String, Object> summary = new HashMap<>();
        List<Object> junk = new ArrayList<>();
        junk.add("a");
        junk.add(null);
        summary.put("turns_covered", junk);
        Conversation conversation = new Conversation("user", "title", "model", "provider");
        conversation.setSummaryCold(summary);

        ConversationDto dto = mapper.toDto(conversation);

        assertThat(dto.getCompactionMarker()).isNull();
    }

    @Test
    void updateEntityAppliesChanges() {
        ConversationDto dto = new ConversationDto();
        dto.setTitle("new title");
        dto.setModel("new-model");
        dto.setProvider("new-provider");
        dto.setActive(false);
        dto.setMemoryEnabled(false);

        Conversation conversation = new Conversation("user", "old", "old-model", "old-provider");
        conversation.setMemoryEnabled(true);
        mapper.updateEntity(dto, conversation);

        assertThat(conversation.getTitle()).isEqualTo("new title");
        assertThat(conversation.getModel()).isEqualTo("new-model");
        assertThat(conversation.getProvider()).isEqualTo("new-provider");
        assertThat(conversation.getActive()).isFalse();
        assertThat(conversation.getMemoryEnabled()).isFalse();
    }
}
