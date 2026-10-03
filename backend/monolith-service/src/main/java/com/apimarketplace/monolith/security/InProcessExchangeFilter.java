package com.apimarketplace.monolith.security;

import com.apimarketplace.common.web.MonolithSecurityFilter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;

/**
 * The {@code WebClient} half of the in-process stamping (LC-032).
 *
 * <p>Not every loopback caller in the monolith is a {@code RestTemplate}: the storage mapping
 * resolver builds a {@code WebClient} against the catalog base URL, which in CE is this same JVM.
 * A caller that goes unstamped is refused, not silently trusted, so this exists to keep that hop
 * working rather than to add trust.
 *
 * <p><b>Why this is not a {@code WebClientCustomizer}.</b> That was the first shape, and it would
 * have been dead code: a customizer is applied by Spring Boot to ITS auto-configured
 * {@code WebClient.Builder}, and this application defines its own builder beans
 * ({@code CatalogWebClientConfig}, {@code StorageConfig}), which replace the auto-configured one.
 * The filter is therefore attached to every {@code WebClient.Builder} BEAN by
 * {@link InProcessCallStampingBeanPostProcessor}, whatever declared it.
 */
public final class InProcessExchangeFilter {

    private final InProcessCallTarget target;

    public InProcessExchangeFilter(InProcessCallTarget target) {
        this.target = target;
    }

    public ExchangeFilterFunction filterFunction() {
        return ExchangeFilterFunction.ofRequestProcessor(request -> {
            if (!target.isSelf(request.url())
                    || request.headers().containsKey(MonolithSecurityFilter.IN_PROCESS_SECRET_HEADER)) {
                return Mono.just(request);
            }
            return Mono.just(ClientRequest.from(request)
                    .header(MonolithSecurityFilter.IN_PROCESS_SECRET_HEADER,
                            MonolithSecurityFilter.inProcessSecret())
                    .build());
        });
    }
}
