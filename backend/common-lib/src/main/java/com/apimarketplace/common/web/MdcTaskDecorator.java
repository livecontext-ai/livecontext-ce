package com.apimarketplace.common.web;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Map;

/**
 * Audit 2026-05-17 round-3 F13 - snapshot MDC + RequestContextHolder on the
 * calling thread and restore them on the worker thread.
 *
 * <p>Without a decorator, Spring's {@code @Async} / {@code @Scheduled} /
 * {@code CompletableFuture.supplyAsync(executor)} dispatches lose:
 * <ul>
 *   <li>MDC tags (logback's {@code %X{org}} prints "-")</li>
 *   <li>{@code RequestContextHolder.getRequestAttributes()} - this is what
 *       {@link OrgContextHeaderForwarder#forward(org.springframework.http.HttpHeaders)}
 *       (called by every {@code *-client}) reads to copy {@code X-Organization-ID}
 *       onto outgoing HTTP calls. Without it, daemon-thread callers silently drop
 *       the org header and hit the strict-tenant fallback at the backend.</li>
 * </ul>
 *
 * <p>Install this decorator on EVERY {@code ThreadPoolTaskExecutor} bean
 * across the platform. The recommended pattern is a shared
 * {@code AsyncConfigurer} in common-lib that returns a pre-decorated
 * executor (see {@link CommonAsyncConfig}).
 */
public final class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();
        RequestAttributes requestSnapshot = RequestContextHolder.getRequestAttributes();
        return () -> {
            Map<String, String> previousMdc = MDC.getCopyOfContextMap();
            RequestAttributes previousRequest = RequestContextHolder.getRequestAttributes();
            try {
                if (mdcSnapshot != null) {
                    MDC.setContextMap(mdcSnapshot);
                } else {
                    MDC.clear();
                }
                if (requestSnapshot != null) {
                    // setInheritable=true so a worker that spawns its OWN sub-tasks
                    // (e.g. via CompletableFuture chains) also sees the request.
                    RequestContextHolder.setRequestAttributes(requestSnapshot, true);
                }
                runnable.run();
            } finally {
                if (previousMdc != null) {
                    MDC.setContextMap(previousMdc);
                } else {
                    MDC.clear();
                }
                RequestContextHolder.setRequestAttributes(previousRequest, true);
            }
        };
    }
}
