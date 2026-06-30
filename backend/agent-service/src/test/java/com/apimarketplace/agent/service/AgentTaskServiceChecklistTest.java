package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentTaskEventRepository;
import com.apimarketplace.agent.repository.AgentTaskNoteRepository;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.conversation.client.ConversationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** F10: checklist + attachment normalisation and validation. */
class AgentTaskServiceChecklistTest {

    private static final String TENANT = "tenant-1";

    private AgentTaskRepository taskRepository;
    private AgentTaskService service;
    private final UUID taskId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        taskRepository = mock(AgentTaskRepository.class);
        AgentTaskService self = mock(AgentTaskService.class);
        service = new AgentTaskService(taskRepository, mock(AgentTaskNoteRepository.class),
                mock(AgentTaskEventRepository.class), mock(AgentRepository.class),
                mock(AgentExecutionRepository.class), mock(TaskBoardPublisher.class),
                mock(ConversationClient.class), self);
        lenient().when(taskRepository.save(any(AgentTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private AgentTaskEntity task() {
        AgentTaskEntity t = new AgentTaskEntity();
        t.setId(taskId);
        t.setTenantId(TENANT);
        when(taskRepository.findByIdAndTenantId(taskId, TENANT)).thenReturn(Optional.of(t));
        return t;
    }

    private static Map<String, Object> item(String... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test
    @DisplayName("checklist normalises items: assigns an id when missing, preserves done")
    void checklistNormalises() {
        task();
        Map<String, Object> withDone = new LinkedHashMap<>();
        withDone.put("text", "write tests");
        withDone.put("done", true);

        AgentTaskEntity r = service.setTaskChecklist(TENANT, taskId, null, TENANT,
                new ArrayList<>(List.of(item("text", "  draft  "), withDone)));

        List<Map<String, Object>> cl = r.getChecklist();
        assertEquals(2, cl.size());
        assertEquals("draft", cl.get(0).get("text"));        // trimmed
        assertNotNull(cl.get(0).get("id"));                  // id assigned
        assertEquals(false, cl.get(0).get("done"));          // default false
        assertEquals(true, cl.get(1).get("done"));
    }

    @Test
    @DisplayName("checklist rejects a blank item text")
    void checklistRejectsBlank() {
        task();
        assertThrows(IllegalArgumentException.class,
                () -> service.setTaskChecklist(TENANT, taskId, null, TENANT, List.of(item("text", "   "))));
    }

    @Test
    @DisplayName("checklist rejects more than 100 items")
    void checklistCap() {
        task();
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 101; i++) many.add(item("text", "item " + i));
        assertThrows(IllegalArgumentException.class,
                () -> service.setTaskChecklist(TENANT, taskId, null, TENANT, many));
    }

    @Test
    @DisplayName("attachments require fileName and storageKey")
    void attachmentsValidate() {
        task();
        AgentTaskEntity r = service.setTaskAttachments(TENANT, taskId, null, TENANT,
                new ArrayList<>(List.of(item("fileName", "spec.pdf", "storageKey", "tenant-1/abc", "mimeType", "application/pdf"))));
        assertEquals(1, r.getAttachments().size());
        assertEquals("spec.pdf", r.getAttachments().get(0).get("fileName"));
        assertNotNull(r.getAttachments().get(0).get("id"));

        assertThrows(IllegalArgumentException.class,
                () -> service.setTaskAttachments(TENANT, taskId, null, TENANT, List.of(item("fileName", "no-key.txt"))));
    }

    @Test
    @DisplayName("an empty list clears the checklist")
    void clearsChecklist() {
        AgentTaskEntity t = task();
        t.setChecklist(new ArrayList<>(List.of(item("id", "x", "text", "old"))));
        AgentTaskEntity r = service.setTaskChecklist(TENANT, taskId, null, TENANT, List.of());
        assertTrue(r.getChecklist().isEmpty());
    }

    // ------------------------------------------------ added coverage (gaps) ----

    @Test
    @DisplayName("checklist accepts 500-char item text but rejects 501")
    void checklistTextBoundary() {
        task();
        AgentTaskEntity ok = service.setTaskChecklist(TENANT, taskId, null, TENANT,
                List.of(item("text", "a".repeat(500))));
        assertEquals(1, ok.getChecklist().size());
        assertThrows(IllegalArgumentException.class,
                () -> service.setTaskChecklist(TENANT, taskId, null, TENANT, List.of(item("text", "a".repeat(501)))));
    }

    @Test
    @DisplayName("checklist keeps a caller-supplied item id instead of generating a new one")
    void checklistPreservesId() {
        task();
        AgentTaskEntity r = service.setTaskChecklist(TENANT, taskId, null, TENANT,
                List.of(item("id", "keep-me", "text", "done it")));
        assertEquals("keep-me", r.getChecklist().get(0).get("id"));
    }

    @Test
    @DisplayName("checklist silently skips a null entry without an NPE")
    void checklistSkipsNullEntry() {
        task();
        List<Map<String, Object>> withNull = new ArrayList<>();
        withNull.add(item("text", "valid"));
        withNull.add(null);
        AgentTaskEntity r = service.setTaskChecklist(TENANT, taskId, null, TENANT, withNull);
        assertEquals(1, r.getChecklist().size());
        assertEquals("valid", r.getChecklist().get(0).get("text"));
    }

    @Test
    @DisplayName("checklist coerces a string 'done' into a boolean")
    void checklistCoercesDoneString() {
        task();
        AgentTaskEntity r = service.setTaskChecklist(TENANT, taskId, null, TENANT,
                List.of(item("text", "task", "done", "true")));
        assertEquals(true, r.getChecklist().get(0).get("done"));
    }

    @Test
    @DisplayName("checklist on an unknown task id fails")
    void checklistTaskNotFound() {
        UUID ghost = UUID.randomUUID();
        when(taskRepository.findByIdAndTenantId(ghost, TENANT)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> service.setTaskChecklist(TENANT, ghost, null, TENANT, List.of(item("text", "x"))));
    }

    @Test
    @DisplayName("a null checklist list clears the checklist (no exception)")
    void checklistNullListClears() {
        AgentTaskEntity t = task();
        t.setChecklist(new ArrayList<>(List.of(item("id", "x", "text", "old"))));
        AgentTaskEntity r = service.setTaskChecklist(TENANT, taskId, null, TENANT, null);
        assertTrue(r.getChecklist().isEmpty());
    }

    @Test
    @DisplayName("attachments reject 51 but accept exactly 50")
    void attachmentsCap() {
        task();
        List<Map<String, Object>> fifty = new ArrayList<>();
        for (int i = 0; i < 50; i++) fifty.add(item("fileName", "f" + i + ".pdf", "storageKey", "t/" + i));
        assertEquals(50, service.setTaskAttachments(TENANT, taskId, null, TENANT, fifty).getAttachments().size());

        List<Map<String, Object>> fiftyOne = new ArrayList<>(fifty);
        fiftyOne.add(item("fileName", "x.pdf", "storageKey", "t/x"));
        assertThrows(IllegalArgumentException.class,
                () -> service.setTaskAttachments(TENANT, taskId, null, TENANT, fiftyOne));
    }

    @Test
    @DisplayName("attachments reject a blank fileName")
    void attachmentsBlankFileName() {
        task();
        assertThrows(IllegalArgumentException.class,
                () -> service.setTaskAttachments(TENANT, taskId, null, TENANT,
                        List.of(item("fileName", "   ", "storageKey", "t/abc"))));
    }

    @Test
    @DisplayName("attachments preserve optional mimeType + sizeBytes and a caller-supplied id")
    void attachmentsPreserveOptionalFieldsAndId() {
        task();
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", "file-1");
        a.put("fileName", "f.pdf");
        a.put("storageKey", "t/k");
        a.put("mimeType", "application/pdf");
        a.put("sizeBytes", 12345);
        Map<String, Object> stored = service.setTaskAttachments(TENANT, taskId, null, TENANT, List.of(a))
                .getAttachments().get(0);
        assertEquals("file-1", stored.get("id"));
        assertEquals("application/pdf", stored.get("mimeType"));
        assertEquals(12345, stored.get("sizeBytes"));
    }

    @Test
    @DisplayName("a null/empty attachments list clears existing attachments")
    void attachmentsNullAndEmptyClear() {
        AgentTaskEntity t = task();
        t.setAttachments(new ArrayList<>(List.of(item("fileName", "old.pdf", "storageKey", "t/old"))));
        assertTrue(service.setTaskAttachments(TENANT, taskId, null, TENANT, null).getAttachments().isEmpty());
        t.setAttachments(new ArrayList<>(List.of(item("fileName", "old.pdf", "storageKey", "t/old"))));
        assertTrue(service.setTaskAttachments(TENANT, taskId, null, TENANT, List.of()).getAttachments().isEmpty());
    }
}
