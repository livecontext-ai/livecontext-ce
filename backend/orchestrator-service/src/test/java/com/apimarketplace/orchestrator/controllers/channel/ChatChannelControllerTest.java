package com.apimarketplace.orchestrator.controllers.channel;

import com.apimarketplace.orchestrator.services.channel.ChatChannelService;
import com.apimarketplace.orchestrator.services.channel.ChatChannelService.ChatChannelException;
import com.apimarketplace.orchestrator.services.channel.ChatChannelService.ChatChannelForbiddenException;
import com.apimarketplace.orchestrator.services.channel.ChatChannelService.ChatChannelSummary;
import com.apimarketplace.orchestrator.services.channel.ChatChannelService.ConnectRequest;
import com.apimarketplace.orchestrator.services.channel.ChatChannelService.ConnectResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The REST half of the channel surface, which had no test at all.
 *
 * <p>The MCP half was covered, so what a person's browser gets was the unverified one: the status
 * of a refusal, the shape of the JSON, and what happens to a credentialId nobody can read. All
 * three matter here more than usual, because this surface decides WHERE a workspace is reached,
 * and therefore who is asked to approve an agent's sensitive action.
 *
 * <p>Standalone MockMvc rather than a Spring context: the controller's own
 * {@code @ExceptionHandler} is honoured by standalone setup, which is exactly the mapping under
 * test, and nothing here needs the rest of the application.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatChannelController - the REST surface")
class ChatChannelControllerTest {

    @Mock private ChatChannelService service;

    private MockMvc mockMvc;

    private static final String TENANT = "tenant-1";
    private static final String ORG = "org-1";
    private static final UUID LINK_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ChatChannelController(service)).build();
    }

    private static ChatChannelSummary summary() {
        return new ChatChannelSummary(LINK_ID, "telegram", 9L, "ops_bot", "-100123", "Ops room",
                "group", true, true, Instant.parse("2026-09-17T10:00:00Z"),
                "https://app.example.com/approval-callback/telegram",
                Instant.parse("2026-09-17T09:00:00Z"), null, List.of("777"));
    }

    private static ConnectResult connected() {
        return new ConnectResult(summary(), true, ChatChannelService.WebhookOutcome.POINTED, null);
    }

    @Test
    @DisplayName("list answers with the documented keys, allow-list included")
    void listReturnsChannelsArrayWithTheDocumentedKeys() throws Exception {
        when(service.list(ORG)).thenReturn(List.of(summary()));

        mockMvc.perform(get("/api/chat-channels")
                        .header("X-User-ID", TENANT)
                        .header("X-Organization-ID", ORG)
                        .header("X-Organization-Role", "MEMBER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channels[0].linkId").value(LINK_ID.toString()))
                .andExpect(jsonPath("$.channels[0].chatId").value("-100123"))
                .andExpect(jsonPath("$.channels[0].isDefault").value(true))
                // Absent before the allow-list was made readable: a connected group could be
                // open to every one of its members with nothing on this surface saying so.
                .andExpect(jsonPath("$.channels[0].allowedUserIds[0]").value("777"));
    }

    @Test
    @DisplayName("a role refusal is 403 even when its sentence says nothing about being read-only")
    void roleRefusalIs403WhateverTheSentenceSays() throws Exception {
        // The status used to be picked by looking for "read-only" in the message. A refusal
        // worded any other way was answered 400, which reads as "fix your request" for something
        // the caller cannot fix.
        when(service.setDefault(anyString(), anyString(), any()))
                .thenThrow(new ChatChannelForbiddenException("not allowed"));

        mockMvc.perform(post("/api/chat-channels/{id}/default", LINK_ID)
                        .header("X-User-ID", TENANT)
                        .header("X-Organization-ID", ORG)
                        .header("X-Organization-Role", "MEMBER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("not allowed"));
    }

    @Test
    @DisplayName("a client refusal stays 400 even when its sentence mentions read-only")
    void clientRefusalIs400EvenIfItMentionsReadOnly() throws Exception {
        // The other direction of the same defect: an ordinary refusal that happens to talk about
        // a read-only chat was promoted to 403.
        when(service.connect(anyString(), anyString(), any(), any()))
                .thenThrow(new ChatChannelException("the bot is read-only in that chat"));

        mockMvc.perform(post("/api/chat-channels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chatId\":\"-100123\"}")
                        .header("X-User-ID", TENANT)
                        .header("X-Organization-ID", ORG)
                        .header("X-Organization-Role", "MEMBER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("the bot is read-only in that chat"));
    }

    @Test
    @DisplayName("a numeric credentialId reaches the service as a number")
    void credentialIdAsNumberIsCoerced() throws Exception {
        when(service.connect(anyString(), anyString(), any(), any())).thenReturn(connected());

        mockMvc.perform(post("/api/chat-channels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chatId\":\"-100123\",\"credentialId\":9}")
                        .header("X-User-ID", TENANT)
                        .header("X-Organization-ID", ORG)
                        .header("X-Organization-Role", "MEMBER"))
                .andExpect(status().isOk());

        assertThat(captured().credentialId()).isEqualTo(9L);
    }

    @Test
    @DisplayName("a numeric string credentialId is coerced too, because JSON bodies carry both")
    void credentialIdAsNumericStringIsCoerced() throws Exception {
        when(service.connect(anyString(), anyString(), any(), any())).thenReturn(connected());

        mockMvc.perform(post("/api/chat-channels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chatId\":\"-100123\",\"credentialId\":\"9\"}")
                        .header("X-User-ID", TENANT)
                        .header("X-Organization-ID", ORG)
                        .header("X-Organization-Role", "MEMBER"))
                .andExpect(status().isOk());

        assertThat(captured().credentialId()).isEqualTo(9L);
    }

    @Test
    @DisplayName("a credentialId that is not a number is refused with the field named")
    void credentialIdGarbageStringIs400WithTheSentence() throws Exception {
        mockMvc.perform(post("/api/chat-channels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chatId\":\"-100123\",\"credentialId\":\"abc\"}")
                        .header("X-User-ID", TENANT)
                        .header("X-Organization-ID", ORG)
                        .header("X-Organization-Role", "MEMBER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString(
                        "credentialId must be a number")));

        verify(service, never()).connect(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("a credentialId of an unexpected type is refused, not silently defaulted")
    void credentialIdOfUnexpectedTypeIs400NotSilentDefault() throws Exception {
        // The one that was worst: neither a number nor a string fell through to null, which is
        // the SAME value as absent, so the connect went ahead on whichever credential the
        // workspace defaults to and answered 200. The caller named one credential and used
        // another, with nothing anywhere saying so.
        mockMvc.perform(post("/api/chat-channels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chatId\":\"-100123\",\"credentialId\":true}")
                        .header("X-User-ID", TENANT)
                        .header("X-Organization-ID", ORG)
                        .header("X-Organization-Role", "MEMBER"))
                .andExpect(status().isBadRequest());

        verify(service, never()).connect(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("an omitted credentialId stays absent, which is how the workspace default is asked for")
    void omittedCredentialIdStaysNull() throws Exception {
        // Guards the refusal above from over-correcting into "any missing value is an error":
        // absent is a legitimate request for the workspace's default credential.
        when(service.connect(anyString(), anyString(), any(), any())).thenReturn(connected());

        mockMvc.perform(post("/api/chat-channels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chatId\":\"-100123\"}")
                        .header("X-User-ID", TENANT)
                        .header("X-Organization-ID", ORG)
                        .header("X-Organization-Role", "MEMBER"))
                .andExpect(status().isOk());

        assertThat(captured().credentialId()).isNull();
    }

    @Test
    @DisplayName("an omitted channel defaults to telegram rather than refusing")
    void channelDefaultsToTelegram() throws Exception {
        when(service.connect(anyString(), anyString(), any(), any())).thenReturn(connected());

        mockMvc.perform(post("/api/chat-channels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chatId\":\"-100123\"}")
                        .header("X-User-ID", TENANT)
                        .header("X-Organization-ID", ORG)
                        .header("X-Organization-Role", "MEMBER"))
                .andExpect(status().isOk());

        assertThat(captured().channel()).isEqualTo("telegram");
    }

    @Test
    @DisplayName("an omitted allowedUserIds reaches the service as null, so the stored list is left alone")
    void connectWithoutAllowedUserIdsSendsNull() throws Exception {
        when(service.connect(anyString(), anyString(), any(), any())).thenReturn(connected());

        mockMvc.perform(post("/api/chat-channels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chatId\":\"-100123\"}")
                        .header("X-User-ID", TENANT)
                        .header("X-Organization-ID", ORG)
                        .header("X-Organization-Role", "MEMBER"))
                .andExpect(status().isOk());

        assertThat(captured().allowedUserIds())
                .as("a reconnect that says nothing must not widen who may approve")
                .isNull();
    }

    @Test
    @DisplayName("an empty allowedUserIds array reaches the service as an empty list, which clears it")
    void connectWithEmptyArraySendsEmptyList() throws Exception {
        when(service.connect(anyString(), anyString(), any(), any())).thenReturn(connected());

        mockMvc.perform(post("/api/chat-channels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chatId\":\"-100123\",\"allowedUserIds\":[]}")
                        .header("X-User-ID", TENANT)
                        .header("X-Organization-ID", ORG)
                        .header("X-Organization-Role", "MEMBER"))
                .andExpect(status().isOk());

        assertThat(captured().allowedUserIds()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("disconnect answers 204 and forgets the link")
    void disconnectReturns204() throws Exception {
        mockMvc.perform(delete("/api/chat-channels/{id}", LINK_ID)
                        .header("X-User-ID", TENANT)
                        .header("X-Organization-ID", ORG)
                        .header("X-Organization-Role", "MEMBER"))
                .andExpect(status().isNoContent());

        verify(service).disconnect(TENANT, ORG, LINK_ID, ChatChannelService.ChangeSource.MANUAL);
    }

    @Test
    @DisplayName("a read-only member is refused at discovery, before any call to the service")
    void readOnlyMemberCannotDiscover() throws Exception {
        // The manual form's first remote step. Refusing only at connect let a read-only member
        // pick an account and a chat before learning none of it could be saved.
        mockMvc.perform(post("/api/chat-channels/discover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"slack\",\"credentialId\":7}")
                        .header("X-User-ID", TENANT)
                        .header("X-Organization-ID", ORG)
                        .header("X-Organization-Role", "VIEWER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("read-only")));

        verify(service, never()).discover(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a member with a write role can still discover")
    void memberCanDiscover() throws Exception {
        when(service.discover(TENANT, "slack", 7L)).thenReturn(
                new ChatChannelService.DiscoveryResult("slack", 7L, "ops", List.of()));

        mockMvc.perform(post("/api/chat-channels/discover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"slack\",\"credentialId\":7}")
                        .header("X-User-ID", TENANT)
                        .header("X-Organization-ID", ORG)
                        .header("X-Organization-Role", "MEMBER"))
                .andExpect(status().isOk());

        verify(service).discover(TENANT, "slack", 7L);
    }

    private ConnectRequest captured() {
        ArgumentCaptor<ConnectRequest> request = ArgumentCaptor.forClass(ConnectRequest.class);
        verify(service).connect(anyString(), anyString(), request.capture(),
                org.mockito.ArgumentMatchers.eq(ChatChannelService.ChangeSource.MANUAL));
        return request.getValue();
    }
}
