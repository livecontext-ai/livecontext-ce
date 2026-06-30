package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.domain.AgentTaskNoteEntity;
import com.apimarketplace.agent.dto.TaskResponse;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.common.auth.UserSummaryDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The enricher resolves display identities (never the viewer's name) and batches
 * the resolve so a whole board page costs one RPC.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TaskResponseEnricher")
class TaskResponseEnricherTest {

    @Mock private AuthClient authClient;

    private TaskResponse taskWith(String createdBy, String assigneeUser, String reviewerUser, String noteAuthor) {
        AgentTaskEntity e = new AgentTaskEntity();
        e.setId(UUID.randomUUID());
        e.setTenantId("t");
        e.setCreatedByUserId(createdBy);
        e.setAssignedToUserId(assigneeUser);
        e.setReviewerUserId(reviewerUser);
        e.setTitle("T");
        e.setInstructions("i");
        AgentTaskNoteEntity note = new AgentTaskNoteEntity();
        note.setId(UUID.randomUUID());
        note.setAuthorUserId(noteAuthor);
        note.setContent("hi");
        note.setCreatedAt(Instant.now());
        return TaskResponse.from(e, List.of(note));
    }

    @Test
    @DisplayName("resolves displayName + avatarUrl for creator / assignee / reviewer / note author")
    void enrichesAllReferencedUsers() {
        when(authClient.batchResolveUsers(any())).thenReturn(Map.of(
                "1", new UserSummaryDto("1", "Dana Example", "http://a/1.png"),
                "42", new UserSummaryDto("42", "Alice", null),
                "7", new UserSummaryDto("7", "Bob", null)));
        TaskResponseEnricher enricher = new TaskResponseEnricher(authClient);

        TaskResponse out = enricher.enrich(taskWith("1", "42", "7", "1"));

        assertThat(out.users()).containsKeys("1", "42", "7");
        assertThat(out.users().get("1").displayName()).isEqualTo("Dana Example");
        assertThat(out.users().get("1").avatarUrl()).isEqualTo("http://a/1.png");
        assertThat(out.users().get("42").displayName()).isEqualTo("Alice");
    }

    @Test
    @DisplayName("batches the whole page into a single resolve call")
    void batchesOneRpcPerPage() {
        when(authClient.batchResolveUsers(any())).thenReturn(Map.of(
                "1", new UserSummaryDto("1", "Dana Example", null),
                "42", new UserSummaryDto("42", "Alice", null)));
        TaskResponseEnricher enricher = new TaskResponseEnricher(authClient);

        enricher.enrichAll(List.of(
                taskWith("1", null, null, null),
                taskWith("42", null, null, null)));

        ArgumentCaptor<Set<String>> captor = ArgumentCaptor.forClass(Set.class);
        org.mockito.Mockito.verify(authClient, org.mockito.Mockito.times(1)).batchResolveUsers(captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder("1", "42");
    }

    @Test
    @DisplayName("an unknown / un-resolvable id still gets a stable key (never the viewer's name)")
    void unknownIdGetsNullDisplayNameButKeyPresent() {
        when(authClient.batchResolveUsers(any())).thenReturn(Map.of()); // nothing resolved
        TaskResponseEnricher enricher = new TaskResponseEnricher(authClient);

        TaskResponse out = enricher.enrich(taskWith("ghost", null, null, null));

        assertThat(out.users()).containsKey("ghost");
        assertThat(out.users().get("ghost").displayName()).isNull();
    }

    @Test
    @DisplayName("no AuthClient wired → passthrough, no resolve")
    void noAuthClientPassthrough() {
        TaskResponseEnricher enricher = new TaskResponseEnricher(null);
        TaskResponse in = taskWith("1", "42", null, null);

        TaskResponse out = enricher.enrich(in);

        assertThat(out).isSameAs(in);
        verifyNoInteractions(authClient);
    }
}
