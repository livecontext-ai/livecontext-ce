package com.apimarketplace.agent.service;

import com.apimarketplace.agent.dto.TaskResponse;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.common.auth.UserSummaryDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Attaches resolved display identities ({@code displayName} + {@code avatarUrl})
 * to {@link TaskResponse} objects so the task board NEVER has to fall back to the
 * viewer's own (Keycloak real) name when labelling a creator / assignee / reviewer /
 * note author.
 *
 * <p>Resolution is batched through {@link AuthClient#batchResolveUsers} (cache-aware,
 * one RPC for the whole page) and keyed by the canonical {@code displayName} from
 * {@code user_onboarding} - never the raw Keycloak first/last name.
 */
@Component
public class TaskResponseEnricher {

    private final AuthClient authClient;

    public TaskResponseEnricher(@Autowired(required = false) AuthClient authClient) {
        this.authClient = authClient;
    }

    /** Enrich a single task (e.g. the detail view, which carries note authors too). */
    public TaskResponse enrich(TaskResponse task) {
        if (task == null) return null;
        return enrichAll(List.of(task)).get(0);
    }

    /** Enrich a page of tasks with a single batch user-resolve. */
    public List<TaskResponse> enrichAll(List<TaskResponse> tasks) {
        if (tasks == null || tasks.isEmpty() || authClient == null) return tasks;

        Set<String> ids = new HashSet<>();
        for (TaskResponse t : tasks) collectIds(t, ids);
        if (ids.isEmpty()) return tasks;

        Map<String, UserSummaryDto> resolved = authClient.batchResolveUsers(ids);
        return tasks.stream().map(t -> {
            Map<String, TaskResponse.UserRef> users = new HashMap<>();
            addRef(users, resolved, t.createdByUserId());
            addRef(users, resolved, t.assignedToUserId());
            addRef(users, resolved, t.reviewerUserId());
            if (t.notes() != null) {
                for (TaskResponse.NoteView n : t.notes()) addRef(users, resolved, n.authorUserId());
            }
            return users.isEmpty() ? t : t.withUsers(users);
        }).toList();
    }

    private static void collectIds(TaskResponse t, Set<String> ids) {
        addId(ids, t.createdByUserId());
        addId(ids, t.assignedToUserId());
        addId(ids, t.reviewerUserId());
        if (t.notes() != null) {
            for (TaskResponse.NoteView n : t.notes()) addId(ids, n.authorUserId());
        }
    }

    private static void addId(Set<String> ids, String id) {
        if (id != null && !id.isBlank()) ids.add(id);
    }

    private static void addRef(Map<String, TaskResponse.UserRef> users,
                               Map<String, UserSummaryDto> resolved,
                               String id) {
        if (id == null || id.isBlank() || users.containsKey(id)) return;
        UserSummaryDto dto = resolved.get(id);
        // Always emit a key so the FE has a stable lookup. A null displayName
        // (unknown / deleted user) makes the FE show a neutral localized label -
        // never the viewer's own name.
        users.put(id, new TaskResponse.UserRef(
                dto != null ? dto.displayName() : null,
                dto != null ? dto.avatarUrl() : null));
    }
}
