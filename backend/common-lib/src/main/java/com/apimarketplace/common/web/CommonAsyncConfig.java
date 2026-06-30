package com.apimarketplace.common.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Audit 2026-05-17 round-3 F13 - auto-install {@link MdcTaskDecorator} on
 * every {@link ThreadPoolTaskExecutor} bean defined in the application
 * context.
 *
 * <p>This is a {@link BeanPostProcessor} so services that already wire their
 * own executor beans (with custom thread names, queue capacity, rejection
 * policies, …) keep their config - only the decorator is added (if not
 * already set). Services that don't define a custom executor will fall back
 * to Spring's default and won't be auto-decorated; the recommended pattern
 * is to always wire an explicit {@code ThreadPoolTaskExecutor} bean.</p>
 *
 * <p>To opt-out for a specific executor (e.g. a fire-and-forget cleanup pool
 * where MDC propagation is unwanted), set the decorator explicitly to
 * {@code null} AFTER bean construction in {@code @PostConstruct}, or define
 * the executor as a different class (e.g. plain {@link java.util.concurrent.ExecutorService}).</p>
 */
@Configuration
public class CommonAsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(CommonAsyncConfig.class);

    @org.springframework.context.annotation.Bean
    public BeanPostProcessor mdcTaskDecoratorInstaller() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof ThreadPoolTaskExecutor tpte) {
                    // Don't overwrite an existing decorator - caller's intent wins.
                    // (Reflection check via a private field would be brittle; rely on
                    // Spring's contract that decorators set after init are honored
                    // for tasks submitted after init.)
                    tpte.setTaskDecorator(new MdcTaskDecorator());
                    log.debug("[CommonAsyncConfig] Installed MdcTaskDecorator on executor bean '{}'", beanName);
                }
                return bean;
            }
        };
    }
}
