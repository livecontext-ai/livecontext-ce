package com.apimarketplace.trigger.client;

import com.apimarketplace.trigger.client.dto.StandaloneChatEndpointDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TriggerClient#findChatEndpointByTokenStrict} / {@link TriggerClient#findFormEndpointByTokenStrict}:
 * publication-service's share-link ownership check must tell "no such endpoint" (null, a refusal)
 * from "trigger-service could not be asked" (an exception, answered 503). The lenient variants fold
 * both into null, which told a legitimate owner their endpoint was not theirs during an outage.
 *
 * <p>No Mockito / spring-test on this module's classpath: a hand-rolled RestTemplate over the one
 * exchange overload the client uses, recording the URLs it was asked for.
 */
@DisplayName("TriggerClient - strict endpoint lookup by token")
class TriggerClientStrictEndpointLookupTest {

    private final List<String> urls = new ArrayList<>();

    private TriggerClient client(Supplier<Object> answer) {
        RestTemplate rt = new RestTemplate() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> ResponseEntity<T> exchange(String url, HttpMethod method,
                                                  HttpEntity<?> requestEntity, Class<T> responseType,
                                                  Object... uriVariables) {
                urls.add(url);
                Object result = answer.get();
                if (result instanceof RuntimeException e) {
                    throw e;
                }
                return ResponseEntity.ok((T) result);
            }
        };
        return new TriggerClient(rt, "http://trigger.test");
    }

    @Test
    @DisplayName("a known chat token answers the endpoint, from the chat by-token route")
    void chatFound() {
        StandaloneChatEndpointDto chat = new StandaloneChatEndpointDto();
        chat.setId(UUID.randomUUID());

        assertThat(client(() -> chat).findChatEndpointByTokenStrict("ch_a")).isSameAs(chat);
        assertThat(urls).containsExactly("http://trigger.test/api/internal/trigger/chat-endpoints/by-token/ch_a");
    }

    @Test
    @DisplayName("a 404 is 'no such endpoint': null, for chat and form alike")
    void notFoundIsNull() {
        Supplier<Object> notFound = () -> HttpClientErrorException.create(
                HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, new byte[0], null);

        assertThat(client(notFound).findChatEndpointByTokenStrict("ch_a")).isNull();
        assertThat(client(notFound).findFormEndpointByTokenStrict("fm_a")).isNull();
        assertThat(urls).last().isEqualTo("http://trigger.test/api/internal/trigger/form-endpoints/by-token/fm_a");
    }

    @Test
    @DisplayName("a 5xx throws SERVER_ERROR instead of reading as 'not found'")
    void serverErrorThrows() {
        TriggerClient client = client(() -> HttpServerErrorException.create(
                HttpStatus.SERVICE_UNAVAILABLE, "Unavailable", HttpHeaders.EMPTY, new byte[0], null));

        assertThatThrownBy(() -> client.findFormEndpointByTokenStrict("fm_a"))
                .isInstanceOfSatisfying(TriggerClientException.class,
                        e -> assertThat(e.getKind()).isEqualTo(TriggerClientException.Kind.SERVER_ERROR));
    }

    @Test
    @DisplayName("a transport failure throws TRANSPORT, and the token never reaches the message")
    void transportFailureThrowsWithoutToken() {
        TriggerClient client = client(() -> new ResourceAccessException(
                "I/O error on GET request for \"http://trigger.test/api/internal/trigger/chat-endpoints/by-token/ch_secret\""));

        assertThatThrownBy(() -> client.findChatEndpointByTokenStrict("ch_secret"))
                .isInstanceOfSatisfying(TriggerClientException.class, e -> {
                    assertThat(e.getKind()).isEqualTo(TriggerClientException.Kind.TRANSPORT);
                    assertThat(e.getMessage()).doesNotContain("ch_secret");
                });
    }

    @Test
    @DisplayName("a 4xx other than 404 throws CLIENT_ERROR")
    void clientErrorThrows() {
        TriggerClient client = client(() -> HttpClientErrorException.create(
                HttpStatus.FORBIDDEN, "Forbidden", HttpHeaders.EMPTY, new byte[0], null));

        assertThatThrownBy(() -> client.findChatEndpointByTokenStrict("ch_a"))
                .isInstanceOfSatisfying(TriggerClientException.class,
                        e -> assertThat(e.getKind()).isEqualTo(TriggerClientException.Kind.CLIENT_ERROR));
    }

    @Test
    @DisplayName("the lenient variant keeps folding a failure into null for its existing callers")
    void lenientVariantUnchanged() {
        TriggerClient client = client(() -> new ResourceAccessException("down"));

        assertThat(client.findChatEndpointByToken("ch_a")).isNull();
        assertThat(client.findFormEndpointByToken("fm_a")).isNull();
    }
}
