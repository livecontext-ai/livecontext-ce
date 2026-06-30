package com.apimarketplace.conversation.controller;

import com.apimarketplace.conversation.controller.internal.InternalDmController;
import com.apimarketplace.conversation.dto.DmMessageDto;
import com.apimarketplace.conversation.dto.DmThreadDto;
import com.apimarketplace.conversation.service.DmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("DmController / InternalDmController")
class DmControllerTest {

    @Mock
    private DmService dmService;

    private MockMvc dm;
    private MockMvc internal;

    @BeforeEach
    void setUp() {
        dm = MockMvcBuilders.standaloneSetup(new DmController(dmService)).build();
        internal = MockMvcBuilders.standaloneSetup(new InternalDmController(dmService)).build();
    }

    private DmThreadDto thread() {
        return new DmThreadDto("t1", "8", null, "hey", 2, Instant.parse("2026-01-01T00:00:00Z"));
    }

    private DmMessageDto message() {
        return new DmMessageDto("m1", "t1", "7", "hi", List.of(), null, Instant.parse("2026-01-02T00:00:00Z"));
    }

    @Test
    @DisplayName("POST /api/dm/threads opens/gets a thread using the X-User-ID header")
    void openThread() throws Exception {
        when(dmService.openOrGetThread("7", "8")).thenReturn(thread());

        dm.perform(post("/api/dm/threads").header("X-User-ID", "7")
                        .contentType("application/json").content("{\"otherUserId\":\"8\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("t1"))
                .andExpect(jsonPath("$.otherUserId").value("8"))
                .andExpect(jsonPath("$.unreadCount").value(2));
        verify(dmService).openOrGetThread("7", "8");
    }

    @Test
    @DisplayName("GET /threads/{id}/messages clamps page size to 100 and returns {items,totalCount,page,size}")
    void listMessagesClampsAndShapesBody() throws Exception {
        Page<DmMessageDto> page = new PageImpl<>(List.of(message()), PageRequest.of(0, 100), 1);
        when(dmService.listMessages(eq("7"), eq("t1"), any(Pageable.class))).thenReturn(page);

        dm.perform(get("/api/dm/threads/t1/messages").header("X-User-ID", "7").param("size", "9999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("m1"))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(100));

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(dmService).listMessages(eq("7"), eq("t1"), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(100); // clamped from 9999
    }

    @Test
    @DisplayName("POST /threads/{id}/messages returns 201 with the saved message")
    void sendMessage() throws Exception {
        when(dmService.sendMessage("7", "t1", "hi", null)).thenReturn(message());

        dm.perform(post("/api/dm/threads/t1/messages").header("X-User-ID", "7")
                        .contentType("application/json").content("{\"content\":\"hi\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.content").value("hi"));
    }

    @Test
    @DisplayName("POST /threads/{id}/messages forwards the attachment refs to the service")
    @SuppressWarnings("unchecked")
    void sendMessageWithAttachments() throws Exception {
        when(dmService.sendMessage(eq("7"), eq("t1"), eq("look"), any())).thenReturn(message());

        dm.perform(post("/api/dm/threads/t1/messages").header("X-User-ID", "7")
                        .contentType("application/json")
                        .content("{\"content\":\"look\",\"attachments\":[{\"storageId\":\""
                                + "5f0e8c1a-1111-2222-3333-444455556666\",\"type\":\"IMAGE\","
                                + "\"fileName\":\"cat.png\",\"mimeType\":\"image/png\"}]}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<List<com.apimarketplace.conversation.dto.DmAttachmentDto>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(dmService).sendMessage(eq("7"), eq("t1"), eq("look"), captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).fileName()).isEqualTo("cat.png");
    }

    @Test
    @DisplayName("GET /threads/{id}/attachments/{storageId} streams the bytes with the stored mime type")
    void downloadAttachment() throws Exception {
        when(dmService.getAttachment("7", "t1", "5f0e8c1a-1111-2222-3333-444455556666"))
                .thenReturn(new com.apimarketplace.conversation.service.AttachmentService.AttachmentData(
                        new byte[]{1, 2, 3}, "image/png", "cat.png"));

        dm.perform(get("/api/dm/threads/t1/attachments/5f0e8c1a-1111-2222-3333-444455556666")
                        .header("X-User-ID", "7"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentType()).isEqualTo("image/png"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsByteArray()).containsExactly(1, 2, 3));
    }

    @Test
    @DisplayName("attachment download honours the service's 404 (unknown / unreferenced storageId)")
    void downloadAttachmentNotFound() throws Exception {
        when(dmService.getAttachment("7", "t1", "5f0e8c1a-1111-2222-3333-444455556666"))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment not found"));

        dm.perform(get("/api/dm/threads/t1/attachments/5f0e8c1a-1111-2222-3333-444455556666")
                        .header("X-User-ID", "7"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /threads/{id} hides the conversation for the caller")
    void deleteThread() throws Exception {
        dm.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/dm/threads/t1").header("X-User-ID", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));

        verify(dmService).deleteThreadForUser("7", "t1");
    }

    @Test
    @DisplayName("POST /threads/{id}/read returns the number of messages marked read")
    void markRead() throws Exception {
        when(dmService.markRead("7", "t1")).thenReturn(3);

        dm.perform(post("/api/dm/threads/t1/read").header("X-User-ID", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.markedRead").value(3));
    }

    @Test
    @DisplayName("DmService's ResponseStatusException is honoured (403), not swallowed into a 500")
    void honoursResponseStatus() throws Exception {
        when(dmService.sendMessage("999", "t1", "x", null))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a participant"));

        dm.perform(post("/api/dm/threads/t1/messages").header("X-User-ID", "999")
                        .contentType("application/json").content("{\"content\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("internal access endpoint returns the participant check verbatim (no orgId)")
    void internalAccess() throws Exception {
        when(dmService.isParticipant("t1", "7")).thenReturn(true);
        when(dmService.isParticipant("t1", "999")).thenReturn(false);

        internal.perform(get("/api/internal/dm-threads/t1/access").param("userId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(true));
        internal.perform(get("/api/internal/dm-threads/t1/access").param("userId", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(false));
    }
}
