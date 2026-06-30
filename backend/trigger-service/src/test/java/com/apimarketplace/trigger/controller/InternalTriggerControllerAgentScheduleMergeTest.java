package com.apimarketplace.trigger.controller;

import com.apimarketplace.trigger.domain.ScheduledExecutionEntity;
import com.apimarketplace.trigger.repository.ScheduledExecutionRepository;
import com.apimarketplace.trigger.repository.StandaloneChatEndpointRepository;
import com.apimarketplace.trigger.repository.StandaloneFormEndpointRepository;
import com.apimarketplace.trigger.repository.StandaloneWebhookRepository;
import com.apimarketplace.trigger.service.ScheduleCronParser;
import com.apimarketplace.trigger.service.StandaloneChatEndpointService;
import com.apimarketplace.trigger.service.StandaloneFormEndpointService;
import com.apimarketplace.trigger.service.StandaloneScheduleService;
import com.apimarketplace.trigger.service.StandaloneWebhookService;
import com.apimarketplace.trigger.service.TriggerLifecycleManager;
import com.apimarketplace.trigger.service.WebhookTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the agent-schedule upsert merge fix
 * ({@link InternalTriggerController#createAgentSchedule}).
 *
 * <p><b>Prod incident 2026-06-14:</b> the "App Factory" hourly agent silently stopped
 * doing any work. Its schedule kept firing, but every fire short-circuited on
 * "no effective prompt, skipping". Root cause: a partial edit that changed only the
 * cron cadence (15-minute to hourly) re-POSTed the agent schedule WITHOUT
 * {@code schedulePrompt}/{@code withMemory}, and the upsert did an unconditional
 * full-replace ({@code setSchedulePrompt(null)} / {@code setWithMemory(false)}),
 * wiping the stored prompt and resetting memory.
 *
 * <p>Contract under test: an existing row PRESERVES {@code schedule_prompt} and
 * {@code with_memory} when the caller omits them; an explicit value (including {@code ""}
 * to clear, or an explicit {@code withMemory}) still applies; a brand-new row defaults to
 * {@code null} prompt / {@code false} memory.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalTriggerController.createAgentSchedule - preserve prompt/with_memory on partial edit")
class InternalTriggerControllerAgentScheduleMergeTest {

    @Mock private WebhookTokenService tokenService;
    @Mock private StandaloneWebhookService standaloneWebhookService;
    @Mock private StandaloneScheduleService standaloneScheduleService;
    @Mock private StandaloneChatEndpointService chatEndpointService;
    @Mock private StandaloneFormEndpointService formEndpointService;
    @Mock private StandaloneWebhookRepository webhookRepository;
    @Mock private StandaloneChatEndpointRepository chatEndpointRepository;
    @Mock private StandaloneFormEndpointRepository formEndpointRepository;
    @Mock private ScheduledExecutionRepository scheduleRepository;
    @Mock private ScheduleCronParser cronParser;
    @Mock private TriggerLifecycleManager triggerLifecycleManager;

    private InternalTriggerController controller;
    private final UUID agentId = UUID.randomUUID();
    private static final String ORG = "ORG-1";
    private static final String PROMPT = "Réveil 15 min : construis UNE nouvelle application maintenant.";

    @BeforeEach
    void setUp() {
        controller = new InternalTriggerController(
                tokenService, standaloneWebhookService, standaloneScheduleService,
                chatEndpointService, formEndpointService, webhookRepository,
                chatEndpointRepository, formEndpointRepository,
                scheduleRepository, cronParser, triggerLifecycleManager);
        lenient().when(scheduleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private ScheduledExecutionEntity existingAgentSchedule(String prompt, boolean withMemory) {
        ScheduledExecutionEntity e = new ScheduledExecutionEntity(
                null, null, "tenant-1", "*/15 * * * *", "UTC",
                Instant.parse("2026-06-14T00:15:00Z"));
        e.setId(UUID.randomUUID());
        e.setAgentEntityId(agentId);
        e.setOrganizationId(ORG);
        e.setSchedulePrompt(prompt);
        e.setWithMemory(withMemory);
        return e;
    }

    private Map<String, Object> baseBody(String cron) {
        Map<String, Object> body = new HashMap<>();
        body.put("agentEntityId", agentId.toString());
        body.put("tenantId", "tenant-1");
        body.put("organizationId", ORG);
        body.put("cronExpression", cron);
        body.put("timezone", "UTC");
        return body;
    }

    private ScheduledExecutionEntity saveCapture() {
        ArgumentCaptor<ScheduledExecutionEntity> captor =
                ArgumentCaptor.forClass(ScheduledExecutionEntity.class);
        verify(scheduleRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("cron-only edit on existing row preserves schedule_prompt and with_memory (the 2026-06-14 incident)")
    void cronOnlyEdit_preservesPromptAndMemory() {
        when(cronParser.isValid("0 * * * *")).thenReturn(true);
        when(cronParser.getNextExecution("0 * * * *", "UTC"))
                .thenReturn(Instant.parse("2026-06-14T08:00:00Z"));
        when(scheduleRepository.findAllByAgentEntityIdAndOrganizationIdStrict(agentId, ORG))
                .thenReturn(List.of(existingAgentSchedule(PROMPT, true)));

        // Body changes ONLY the cron - schedulePrompt and withMemory are absent.
        ResponseEntity<?> response = controller.createAgentSchedule(baseBody("0 * * * *"), ORG);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ScheduledExecutionEntity saved = saveCapture();
        assertThat(saved.getCronExpression()).as("cron is updated").isEqualTo("0 * * * *");
        assertThat(saved.getSchedulePrompt())
                .as("prompt must be preserved, NOT wiped, on a cron-only edit")
                .isEqualTo(PROMPT);
        assertThat(saved.getWithMemory())
                .as("with_memory must be preserved, NOT reset to false")
                .isTrue();
    }

    @Test
    @DisplayName("explicit schedulePrompt overwrites the stored prompt")
    void explicitPrompt_overwrites() {
        when(cronParser.isValid("0 * * * *")).thenReturn(true);
        when(cronParser.getNextExecution("0 * * * *", "UTC"))
                .thenReturn(Instant.parse("2026-06-14T08:00:00Z"));
        when(scheduleRepository.findAllByAgentEntityIdAndOrganizationIdStrict(agentId, ORG))
                .thenReturn(List.of(existingAgentSchedule(PROMPT, true)));

        Map<String, Object> body = baseBody("0 * * * *");
        body.put("schedulePrompt", "Brand new instructions.");

        controller.createAgentSchedule(body, ORG);

        assertThat(saveCapture().getSchedulePrompt()).isEqualTo("Brand new instructions.");
    }

    @Test
    @DisplayName("explicit empty schedulePrompt clears the prompt (intentional reset still works)")
    void explicitEmptyPrompt_clears() {
        when(cronParser.isValid("0 * * * *")).thenReturn(true);
        when(cronParser.getNextExecution("0 * * * *", "UTC"))
                .thenReturn(Instant.parse("2026-06-14T08:00:00Z"));
        when(scheduleRepository.findAllByAgentEntityIdAndOrganizationIdStrict(agentId, ORG))
                .thenReturn(List.of(existingAgentSchedule(PROMPT, true)));

        Map<String, Object> body = baseBody("0 * * * *");
        body.put("schedulePrompt", "");

        controller.createAgentSchedule(body, ORG);

        assertThat(saveCapture().getSchedulePrompt()).isEqualTo("");
    }

    @Test
    @DisplayName("explicit withMemory=false on existing row is honoured (explicit toggle still works)")
    void explicitWithMemoryFalse_isHonoured() {
        when(cronParser.isValid("0 * * * *")).thenReturn(true);
        when(cronParser.getNextExecution("0 * * * *", "UTC"))
                .thenReturn(Instant.parse("2026-06-14T08:00:00Z"));
        when(scheduleRepository.findAllByAgentEntityIdAndOrganizationIdStrict(agentId, ORG))
                .thenReturn(List.of(existingAgentSchedule(PROMPT, true)));

        Map<String, Object> body = baseBody("0 * * * *");
        body.put("withMemory", false);

        controller.createAgentSchedule(body, ORG);

        ScheduledExecutionEntity saved = saveCapture();
        assertThat(saved.getWithMemory()).as("explicit false must override stored true").isFalse();
        assertThat(saved.getSchedulePrompt()).as("prompt still preserved when only memory toggled").isEqualTo(PROMPT);
    }

    @Test
    @DisplayName("new schedule defaults to null prompt and with_memory=false when fields are omitted")
    void newSchedule_defaults() {
        when(cronParser.isValid("0 * * * *")).thenReturn(true);
        when(cronParser.getNextExecution("0 * * * *", "UTC"))
                .thenReturn(Instant.parse("2026-06-14T08:00:00Z"));
        when(scheduleRepository.findAllByAgentEntityIdAndOrganizationIdStrict(agentId, ORG))
                .thenReturn(List.of());

        controller.createAgentSchedule(baseBody("0 * * * *"), ORG);

        ScheduledExecutionEntity saved = saveCapture();
        assertThat(saved.getSchedulePrompt()).isNull();
        assertThat(saved.getWithMemory()).isFalse();
    }
}
