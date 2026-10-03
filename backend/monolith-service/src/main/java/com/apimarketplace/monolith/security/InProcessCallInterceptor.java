package com.apimarketplace.monolith.security;

import com.apimarketplace.common.web.MonolithSecurityFilter;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * Stamps this JVM's in-process secret on {@code RestTemplate} calls the monolith makes to itself
 * (LC-032).
 *
 * <p>This is the compliance half of {@code MonolithSecurityFilter.isInProcessRequest}. Without it
 * the filter's check is an outage: every internal hop in a CE install would be treated as external
 * traffic and answered 401 or 404.
 *
 * <p>It is attached to clients mechanically, by
 * {@link InProcessCallStampingBeanPostProcessor}, rather than added to each of the ~70 outbound
 * header-building methods by hand. A hand-maintained list rots on the first new client; a walk of
 * the bean graph does not.
 */
public class InProcessCallInterceptor implements ClientHttpRequestInterceptor {

    private final InProcessCallTarget target;

    public InProcessCallInterceptor(InProcessCallTarget target) {
        this.target = target;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (target.isSelf(request.getURI())) {
            request.getHeaders().set(MonolithSecurityFilter.IN_PROCESS_SECRET_HEADER,
                    MonolithSecurityFilter.inProcessSecret());
        }
        return execution.execute(request, body);
    }
}
