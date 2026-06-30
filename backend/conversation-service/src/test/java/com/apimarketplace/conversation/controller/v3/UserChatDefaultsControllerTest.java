package com.apimarketplace.conversation.controller.v3;

import com.apimarketplace.conversation.service.UserChatDefaultsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class UserChatDefaultsControllerTest {

    @Mock
    private UserChatDefaultsService service;

    @InjectMocks
    private UserChatDefaultsController controller;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("GET returns the caller's defaults for the active workspace")
    void getReturnsDefaultsForActiveWorkspace() throws Exception {
        when(service.get("u1", "orgA")).thenReturn(Map.of("webSearch", false));

        mockMvc.perform(get("/api/v3/chat/defaults")
                        .header("X-User-ID", "u1")
                        .header("X-Organization-ID", "orgA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.webSearch").value(false));

        verify(service).get("u1", "orgA");
    }

    @Test
    @DisplayName("GET without a workspace header returns an empty object and never hits the store")
    void getWithoutOrgReturnsEmpty() throws Exception {
        mockMvc.perform(get("/api/v3/chat/defaults").header("X-User-ID", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isMap());

        verify(service, never()).get(any(), any());
    }

    @Test
    @DisplayName("PUT saves the caller's defaults for the active workspace")
    void putSavesDefaults() throws Exception {
        when(service.save(eq("u1"), eq("orgA"), any())).thenReturn(Map.of("webSearch", true));

        mockMvc.perform(put("/api/v3/chat/defaults")
                        .header("X-User-ID", "u1")
                        .header("X-Organization-ID", "orgA")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("webSearch", true, "junk", "x"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.webSearch").value(true));

        verify(service).save(eq("u1"), eq("orgA"), any());
    }

    @Test
    @DisplayName("PUT without a workspace header is a 400 and never writes (defaults are per (user, workspace))")
    void putWithoutOrgIsBadRequest() throws Exception {
        mockMvc.perform(put("/api/v3/chat/defaults")
                        .header("X-User-ID", "u1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"webSearch\":true}"))
                .andExpect(status().isBadRequest());

        verify(service, never()).save(any(), any(), any());
    }
}
