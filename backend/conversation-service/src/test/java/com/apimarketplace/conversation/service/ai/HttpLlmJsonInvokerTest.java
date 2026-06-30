package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.agent.client.dto.execution.JsonCompletionRequestDto;
import com.apimarketplace.agent.client.dto.execution.JsonCompletionResponseDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HttpLlmJsonInvoker} - the HTTP-backed production
 * impl of {@link ColdSummarizerService.LlmJsonInvoker} that forwards the
 * COLD-summary prompt to agent-service's internal json-completion
 * endpoint.
 *
 * <p>Coverage matrix:
 * <ul>
 *   <li>Happy path - correct URL, body, {@code X-User-ID} header when
 *       tenantId is supplied.</li>
 *   <li>Tenant-less overload omits {@code X-User-ID}.</li>
 *   <li>Blank / null content in response → explicit failure (the
 *       summariser needs a parseable body; an empty 200 is a server
 *       contract violation).</li>
 *   <li>Downstream 5xx propagates.</li>
 * </ul>
 */
@DisplayName("HttpLlmJsonInvoker")
@ExtendWith(MockitoExtension.class)
class HttpLlmJsonInvokerTest {

    private static final String AGENT_URL = "http://agent-service:8090";

    @Mock RestTemplate restTemplate;

    private HttpLlmJsonInvoker invoker;

    @BeforeEach
    void setUp() {
        invoker = new HttpLlmJsonInvoker(AGENT_URL, restTemplate);
    }

    @Test
    @DisplayName("Dispatches to /api/internal/agent/execute/json-completion with body and X-User-ID")
    void dispatchesWithTenantHeader() {
        when(restTemplate.exchange(
                eq(AGENT_URL + "/api/internal/agent/execute/json-completion"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(JsonCompletionResponseDto.class)
        )).thenReturn(ResponseEntity.ok(new JsonCompletionResponseDto("{\"a\":1}")));

        String json = invoker.invoke("google", "gemini-3-flash", "SYS", "USER", "tenant-42");

        assertThat(json).isEqualTo("{\"a\":1}");
        ArgumentCaptor<HttpEntity<JsonCompletionRequestDto>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        org.mockito.Mockito.verify(restTemplate).exchange(
                eq(AGENT_URL + "/api/internal/agent/execute/json-completion"),
                eq(HttpMethod.POST),
                captor.capture(),
                eq(JsonCompletionResponseDto.class));
        HttpEntity<JsonCompletionRequestDto> entity = captor.getValue();
        assertThat(entity.getHeaders().getFirst("X-User-ID")).isEqualTo("tenant-42");
        assertThat(entity.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        JsonCompletionRequestDto body = entity.getBody();
        assertThat(body).isNotNull();
        assertThat(body.provider()).isEqualTo("google");
        assertThat(body.model()).isEqualTo("gemini-3-flash");
        assertThat(body.system()).isEqualTo("SYS");
        assertThat(body.user()).isEqualTo("USER");
        assertThat(body.tenantId()).isEqualTo("tenant-42");
    }

    @Test
    @DisplayName("Three-arg LlmJsonInvoker overload omits X-User-ID when tenantId is null")
    void threeArgOverloadNoTenantHeader() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class),
                eq(JsonCompletionResponseDto.class)))
                .thenReturn(ResponseEntity.ok(new JsonCompletionResponseDto("{}")));

        invoker.invoke("anthropic", "claude-sonnet-4-6", "s", "u");

        ArgumentCaptor<HttpEntity<JsonCompletionRequestDto>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        org.mockito.Mockito.verify(restTemplate).exchange(any(String.class), eq(HttpMethod.POST),
                captor.capture(), eq(JsonCompletionResponseDto.class));
        assertThat(captor.getValue().getHeaders().getFirst("X-User-ID")).isNull();
        assertThat(captor.getValue().getBody().tenantId()).isNull();
    }

    @Test
    @DisplayName("Blank tenantId omits X-User-ID header (treated as unset)")
    void blankTenantIdOmitsHeader() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class),
                eq(JsonCompletionResponseDto.class)))
                .thenReturn(ResponseEntity.ok(new JsonCompletionResponseDto("{}")));

        invoker.invoke("google", "m", "s", "u", "   ");

        ArgumentCaptor<HttpEntity<JsonCompletionRequestDto>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        org.mockito.Mockito.verify(restTemplate).exchange(any(String.class), eq(HttpMethod.POST),
                captor.capture(), eq(JsonCompletionResponseDto.class));
        assertThat(captor.getValue().getHeaders().getFirst("X-User-ID")).isNull();
    }

    @Test
    @DisplayName("Null body in 200 response throws IllegalStateException")
    void nullBodyThrows() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class),
                eq(JsonCompletionResponseDto.class)))
                .thenReturn(ResponseEntity.ok(null));

        assertThatThrownBy(() -> invoker.invoke("google", "m", "s", "u"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty body");
    }

    @Test
    @DisplayName("Null content field throws IllegalStateException")
    void nullContentThrows() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class),
                eq(JsonCompletionResponseDto.class)))
                .thenReturn(ResponseEntity.ok(new JsonCompletionResponseDto(null)));

        assertThatThrownBy(() -> invoker.invoke("google", "m", "s", "u"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty body");
    }

    @Test
    @DisplayName("Blank content throws IllegalStateException")
    void blankContentThrows() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class),
                eq(JsonCompletionResponseDto.class)))
                .thenReturn(ResponseEntity.ok(new JsonCompletionResponseDto("  \n\t ")));

        assertThatThrownBy(() -> invoker.invoke("google", "m", "s", "u"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty body");
    }

    @Test
    @DisplayName("Downstream 5xx propagates to caller (treated as Failed by summariser)")
    void downstream5xxPropagates() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class),
                eq(JsonCompletionResponseDto.class)))
                .thenThrow(HttpServerErrorException.create(
                        HttpStatusCode.valueOf(503), "Service Unavailable",
                        org.springframework.http.HttpHeaders.EMPTY, new byte[0], null));

        assertThatThrownBy(() -> invoker.invoke("google", "m", "s", "u"))
                .isInstanceOf(HttpServerErrorException.class);
    }
}
