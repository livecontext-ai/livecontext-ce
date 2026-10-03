package com.apimarketplace.monolith.security;

import com.apimarketplace.common.web.MonolithSecurityFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LC-032: the {@code WebClient} callers are stamped too.
 *
 * <p>Not every loopback caller in the monolith is a {@code RestTemplate} - the storage mapping
 * resolver builds a {@code WebClient} against the catalog base URL, which in CE is this same JVM.
 * Missing it would not weaken the check; it would refuse that hop, which is why it is pinned here
 * rather than assumed.
 */
@DisplayName("LC-032 in-process stamping: WebClient")
class InProcessExchangeFilterTest {

    private final InProcessExchangeFilter filter =
            new InProcessExchangeFilter(new InProcessCallTarget(8080));

    @Test
    @DisplayName("a call to this monolith carries this boot's secret")
    void selfCallIsStamped() {
        ClientRequest sent = exchange("http://localhost:8080/api/internal/tools/mapping/1");

        assertThat(sent.headers().getFirst(MonolithSecurityFilter.IN_PROCESS_SECRET_HEADER))
                .isEqualTo(MonolithSecurityFilter.inProcessSecret());
    }

    @Test
    @DisplayName("a call anywhere else carries nothing")
    void foreignCallIsNotStamped() {
        assertThat(exchange("https://api.openai.com/v1/chat/completions")
                .headers().getFirst(MonolithSecurityFilter.IN_PROCESS_SECRET_HEADER)).isNull();
        assertThat(exchange("http://127.0.0.1:9000/workflow-files/x")
                .headers().getFirst(MonolithSecurityFilter.IN_PROCESS_SECRET_HEADER)).isNull();
    }

    @Test
    @DisplayName("the request is otherwise untouched")
    void everythingElseSurvives() {
        ClientRequest sent = exchange("http://localhost:8080/api/internal/tools/mapping/1");

        assertThat(sent.method()).isEqualTo(HttpMethod.GET);
        assertThat(sent.url()).isEqualTo(URI.create("http://localhost:8080/api/internal/tools/mapping/1"));
        assertThat(sent.headers().getFirst("X-User-ID")).isEqualTo("42");
    }

    /** Runs one request through the filter and returns what would have gone on the wire. */
    private ClientRequest exchange(String url) {
        ClientRequest request = ClientRequest.create(HttpMethod.GET, URI.create(url))
                .header("X-User-ID", "42")
                .build();
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        ExchangeFunction terminal = req -> {
            captured.set(req);
            return Mono.just(ClientResponse.create(org.springframework.http.HttpStatus.OK).build());
        };

        filter.filterFunction().filter(request, terminal).block();

        return captured.get();
    }

    @Test
    @DisplayName("a filter applied twice does not send the secret twice")
    void applyingTheFilterTwiceIsIdempotent() {
        // The builder beans in this application are hand-declared, so a future change could easily
        // attach the filter on two paths. Two identical values on one header is not a security
        // problem, but it is the kind of drift that turns into a confusing 401 somewhere else.
        ClientRequest request = ClientRequest.create(HttpMethod.GET,
                URI.create("http://localhost:8080/api/internal/tools/mapping/1")).build();
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        ExchangeFunction terminal = req -> {
            captured.set(req);
            return Mono.just(ClientResponse.create(org.springframework.http.HttpStatus.OK).build());
        };
        ExchangeFilterFunction twice = filter.filterFunction().andThen(filter.filterFunction());

        twice.filter(request, terminal).block();

        assertThat(captured.get().headers().get(MonolithSecurityFilter.IN_PROCESS_SECRET_HEADER))
                .containsExactly(MonolithSecurityFilter.inProcessSecret());
    }
}
