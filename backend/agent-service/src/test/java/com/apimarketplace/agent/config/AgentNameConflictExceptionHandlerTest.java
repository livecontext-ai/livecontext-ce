package com.apimarketplace.agent.config;

import com.apimarketplace.agent.service.AgentNameConflictException;
import com.apimarketplace.agent.service.AgentService;
import com.apimarketplace.otherservicefixture.OtherServiceFixtureController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real dispatch through the advices as the CE monolith wires them: an agent name held by another
 * active agent (V269 index) answers 409 {@code AGENT_NAME_CONFLICT} with the free name, whether
 * the service caught it before the insert or the index caught it at commit (a lost race).
 *
 * <p>The monolith's trap is reproduced by registering, FIRST, an advice shaped like
 * orchestrator's GlobalExceptionHandler (an IllegalArgumentException handler answering 400 and a
 * catch-all answering 500): without the agent advice's highest precedence, the 409 is shadowed.
 * Verified by removing {@code @Order} from AgentNameConflictExceptionHandler: the checked
 * duplicate then answers 400, and both integrity-error cases on the agent controller 500.
 */
@DisplayName("AgentNameConflictExceptionHandler - agent name conflict, monolith ordering")
class AgentNameConflictExceptionHandlerTest {

    static final UUID EXISTING = UUID.fromString("22222222-2222-4222-8222-222222222222");
    static final String ORG = "33333333-3333-4333-8333-333333333333";

    /** Carries the marker, like AgentController and InternalAgentController. */
    @RestController
    static class AgentFixtureController implements AgentNameConflictSource {
        @GetMapping("/checked")
        @SuppressWarnings("unused")
        Map<String, Object> checked() {
            throw new AgentNameConflictException("Nova", EXISTING, "Nova (2)");
        }

        @org.springframework.web.bind.annotation.PutMapping("/agents/{id}")
        @SuppressWarnings("unused")
        Map<String, Object> renameRace(@org.springframework.web.bind.annotation.PathVariable("id") String id) {
            return race();
        }

        @GetMapping("/race")
        @SuppressWarnings("unused")
        Map<String, Object> race() {
            throw new DataIntegrityViolationException("could not execute statement",
                    new RuntimeException("ERROR: duplicate key value violates unique constraint "
                            + "\"uq_agents_org_name_active\"\n  Detail: Key (organization_id, name)=("
                            + ORG + ", Nova) already exists."));
        }

        @GetMapping("/other-duplicate")
        @SuppressWarnings("unused")
        Map<String, Object> otherDuplicate() {
            throw new DataIntegrityViolationException(
                    "duplicate key value violates unique constraint \"uq_skills_org_name\"");
        }

        @GetMapping("/plain-invalid")
        @SuppressWarnings("unused")
        Map<String, Object> plainInvalid() {
            throw new IllegalArgumentException("temperature must be between 0 and 2");
        }
    }

    /** Shaped like orchestrator's GlobalExceptionHandler, consulted first in the monolith. */
    @RestControllerAdvice
    static class OrchestratorLikeAdvice {
        @ExceptionHandler(IllegalArgumentException.class)
        ResponseEntity<Map<String, Object>> illegalArgument(IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_ARGUMENT", "by", "orchestrator"));
        }

        @ExceptionHandler(Exception.class)
        ResponseEntity<Map<String, Object>> any(Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "INTERNAL_ERROR", "by", "orchestrator"));
        }
    }

    private MockMvc mockMvc;
    private AgentService agentService;

    @BeforeEach
    void setUp() {
        agentService = Mockito.mock(AgentService.class);
        AgentNameConflictExceptionHandler handler = new AgentNameConflictExceptionHandler();
        ReflectionTestUtils.setField(handler, "agentService", agentService);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AgentFixtureController(), new OtherServiceFixtureController())
                // Registration order = the monolith's scan order: the orchestrator-like advice first.
                .setControllerAdvice(new OrchestratorLikeAdvice(), new GlobalExceptionHandler(), handler)
                .build();
    }

    @Test
    @DisplayName("a checked duplicate -> 409 AGENT_NAME_CONFLICT with suggestedName, even with orchestrator's IllegalArgumentException handler first")
    void checkedDuplicateIs409WithSuggestion() throws Exception {
        mockMvc.perform(get("/checked"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("AGENT_NAME_CONFLICT"))
                .andExpect(jsonPath("$.name").value("Nova"))
                .andExpect(jsonPath("$.suggestedName").value("Nova (2)"))
                .andExpect(jsonPath("$.existingAgentId").value(EXISTING.toString()));
    }

    @Test
    @DisplayName("regression: the index refusing the insert (lost race) -> the same 409, suggestion computed afresh")
    void indexViolationIsTheSame409() throws Exception {
        Mockito.when(agentService.allocateAgentName(eq(ORG), eq("Nova"))).thenReturn("Nova (2)");

        mockMvc.perform(get("/race"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("AGENT_NAME_CONFLICT"))
                .andExpect(jsonPath("$.suggestedName").value("Nova (2)"))
                .andExpect(jsonPath("$.existingAgentId").doesNotExist());
    }

    @Test
    @DisplayName("a lost RENAME race (PUT on an agent id) -> rename advice, suggestion counting the agent's own name as free")
    void renameRaceGetsRenameAdviceAndOwnNameSuggestion() throws Exception {
        UUID renamed = UUID.fromString("44444444-4444-4444-8444-444444444444");
        Mockito.when(agentService.allocateAgentNameForRename(ORG, "Nova", renamed)).thenReturn("Nova (2)");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/agents/" + renamed))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.suggestedName").value("Nova (2)"))
                .andExpect(jsonPath("$.message").value(
                        "Another active agent already uses the name 'Nova' in this workspace. "
                                + "Nothing was saved. Retry with name='Nova (2)'."));
        Mockito.verify(agentService, Mockito.never()).allocateAgentName(Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("any other unique violation on an agent controller keeps agent-service's 400 DUPLICATE_RESOURCE")
    void otherDuplicateOnAgentControllerUnchanged() throws Exception {
        mockMvc.perform(get("/other-duplicate"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("DUPLICATE_RESOURCE"));
        Mockito.verifyNoInteractions(agentService);
    }

    @Test
    @DisplayName("another service's integrity error is NOT taken by the agent advice (it covers only the marked agent controllers)")
    void otherServiceIntegrityErrorNotStolen() throws Exception {
        mockMvc.perform(get("/other-service/duplicate"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.by").value("orchestrator"));
        Mockito.verifyNoInteractions(agentService);
    }

    @Test
    @DisplayName("a plain IllegalArgumentException is not claimed by the agent advice: the advice ranked next answers it")
    void plainIllegalArgumentNotClaimed() throws Exception {
        mockMvc.perform(get("/plain-invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.by").value("orchestrator"));
    }

    @Test
    @DisplayName("a controller WITHOUT the marker is outside the advice even inside agent packages")
    void unmarkedAgentControllerNotCovered() throws Exception {
        MockMvc unmarked = MockMvcBuilders.standaloneSetup(new UnmarkedAgentController())
                .setControllerAdvice(new OrchestratorLikeAdvice(), new AgentNameConflictExceptionHandler())
                .build();
        unmarked.perform(get("/unmarked/race"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.by").value("orchestrator"));
    }

    @RestController
    static class UnmarkedAgentController {
        @GetMapping("/unmarked/race")
        @SuppressWarnings("unused")
        Map<String, Object> race() {
            throw new DataIntegrityViolationException("duplicate key value violates unique constraint "
                    + "\"uq_agents_org_name_active\"");
        }
    }
}
