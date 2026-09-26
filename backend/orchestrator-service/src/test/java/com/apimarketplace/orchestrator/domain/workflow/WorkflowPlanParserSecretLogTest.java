package com.apimarketplace.orchestrator.domain.workflow;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression, 2026-09-25: "[parseTriggers] Parsing trigger data" printed the whole trigger map at
 * INFO on every plan parse, and a webhook trigger keeps its auth secrets in clear in its params.
 */
@DisplayName("WorkflowPlanParser - a webhook trigger's auth secrets never reach the log")
class WorkflowPlanParserSecretLogTest {

    @Test
    @DisplayName("Regression: webhookToken, basicPassword, authHeaderValue and jwtSecretKey are withheld from the parse log")
    void webhookSecretsWithheld() {
        Map<String, Object> webhook = Map.of(
                "webhookToken", "wh_FAKEwebhookToken0123456789",
                "basicPassword", "FAKE-basic-password-42",
                "authHeaderValue", "Bearer FAKE-header-value-77",
                "jwtSecretKey", "FAKE-jwt-secret-key-99");
        Map<String, Object> trigger = Map.of(
                "id", "trigger:hook", "type", "webhook", "label", "Hook",
                "params", Map.of("webhook", webhook));
        Map<String, Object> plan = new java.util.HashMap<>();
        plan.put("triggers", new ArrayList<>(List.of(trigger)));
        plan.put("nodes", new ArrayList<>());
        plan.put("edges", new ArrayList<>());

        Logger logger = (Logger) LoggerFactory.getLogger(WorkflowPlanParser.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            WorkflowPlanParser.parse(plan, "tenant-1");
        } catch (RuntimeException ignored) {
            // Only the parse log matters here; a minimal plan may fail later validation.
        } finally {
            logger.detachAppender(logs);
        }

        String all = logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + b + "\n");
        assertThat(all).contains("Parsing trigger data");
        assertThat(all)
                .doesNotContain("wh_FAKEwebhookToken0123456789")
                .doesNotContain("FAKE-basic-password-42")
                .doesNotContain("FAKE-header-value-77")
                .doesNotContain("FAKE-jwt-secret-key-99");
    }
}
