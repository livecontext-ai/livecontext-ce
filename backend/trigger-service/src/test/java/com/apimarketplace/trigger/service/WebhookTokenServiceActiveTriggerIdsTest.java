package com.apimarketplace.trigger.service;

import com.apimarketplace.trigger.domain.WebhookTokenEntity;
import com.apimarketplace.trigger.repository.WebhookTokenRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WebhookTokenServiceActiveTriggerIdsTest {

    @Test
    void groupsActiveTriggerIdentitiesByWorkflow() {
        WebhookTokenRepository repository = mock(WebhookTokenRepository.class);
        WebhookTokenService service = new WebhookTokenService(repository);
        UUID firstWorkflow = UUID.randomUUID();
        UUID secondWorkflow = UUID.randomUUID();
        List<UUID> requested = List.of(firstWorkflow, secondWorkflow);
        when(repository.findActiveByWorkflowIdIn(requested)).thenReturn(List.of(
                token(firstWorkflow, "trigger:first"),
                token(firstWorkflow, "trigger:second"),
                token(secondWorkflow, "trigger:only")));

        assertThat(service.findActiveTriggerIdsByWorkflow(requested)).isEqualTo(Map.of(
                firstWorkflow, Set.of("trigger:first", "trigger:second"),
                secondWorkflow, Set.of("trigger:only")));
    }

    @Test
    void emptyRequestAvoidsTheRepository() {
        WebhookTokenRepository repository = mock(WebhookTokenRepository.class);
        WebhookTokenService service = new WebhookTokenService(repository);

        assertThat(service.findActiveTriggerIdsByWorkflow(List.of())).isEmpty();
        verifyNoInteractions(repository);
    }

    private static WebhookTokenEntity token(UUID workflowId, String triggerId) {
        WebhookTokenEntity token = new WebhookTokenEntity();
        token.setWorkflowId(workflowId);
        token.setTriggerId(triggerId);
        return token;
    }
}
