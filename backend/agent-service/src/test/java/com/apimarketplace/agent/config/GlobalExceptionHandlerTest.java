package com.apimarketplace.agent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

    @Test
    @DisplayName("Maps duplicate agent-name races to duplicate resource responses")
    void duplicateAgentNameRaceReturnsClientError() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        DataIntegrityViolationException exception = new DataIntegrityViolationException("""
                could not execute statement [ERROR: duplicate key value violates unique constraint "uq_agents_tenant_name_active"
                Detail: Key (tenant_id, name)=(103, E2E DeepSeek Runtime Agent) already exists.]
                """);

        ResponseEntity<Map<String, Object>> response = handler.handleDataIntegrity(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .containsEntry("error", "DUPLICATE_RESOURCE")
                .containsEntry("constraint", "uq_agents_tenant_name_active");
        assertThat(response.getBody())
                .extracting(body -> body.get("message"))
                .asString()
                .contains("already exists");
    }
}
