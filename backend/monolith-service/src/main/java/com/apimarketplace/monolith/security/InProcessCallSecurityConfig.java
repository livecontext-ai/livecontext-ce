package com.apimarketplace.monolith.security;

import com.apimarketplace.common.web.MonolithSecurityFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires the LC-032 in-process call secret into the CE monolith.
 *
 * <p>Two halves that must ship together. {@code MonolithSecurityFilter} stops trusting a loopback
 * peer address and starts requiring this boot's secret; the beans here put that secret on the calls
 * the monolith makes to itself. Landing the check without the stamping is not a hardening, it is a
 * CE outage - every internal hop answers 401 or 404 - which is why the two live in one change.
 */
@Configuration
public class InProcessCallSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(InProcessCallSecurityConfig.class);

    /**
     * {@code static} because a {@link BeanPostProcessor} has to be instantiated before the beans it
     * post-processes; a non-static factory method would drag this whole configuration class into
     * the earliest phase of the context and take every bean it references with it. The
     * {@link Environment} is the only dependency, and it exists before any bean does.
     */
    @Bean
    public static InProcessCallStampingBeanPostProcessor inProcessCallStampingBeanPostProcessor(
            Environment environment) {
        int configuredPort = environment.getProperty("server.port", Integer.class, 8080);
        return new InProcessCallStampingBeanPostProcessor(new InProcessCallTarget(configuredPort));
    }

    /**
     * Applies the operator kill switch to the filter.
     *
     * <p>A post-processor rather than a line in the filter's own {@code @Bean} method: the filter
     * is built elsewhere, and the enforcement defaults to ON inside the filter itself, so this only
     * ever has to carry the "turn it off" case. Same shape and same purpose as
     * {@code auth.deny-by-default}: one configuration change gets an install running again if its
     * internal hop is being refused, and the endpoint can then be reported.
     */
    @Bean
    public static BeanPostProcessor inProcessSecretSwitch(Environment environment) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof MonolithSecurityFilter filter) {
                    filter.withInProcessSecretRequired(
                            environment.getProperty("auth.in-process-secret.required", Boolean.class, true));
                }
                return bean;
            }
        };
    }

    /**
     * Records the port the web server actually bound, and reports how much the walk covered.
     *
     * <p>The count is the one operational signal this mechanism has: zero would mean the walk found
     * nothing and every internal hop is about to be refused, which is worth seeing in the log of a
     * failing install rather than deducing from 401s.
     */
    @Bean
    public ApplicationListener<WebServerInitializedEvent> inProcessCallPortBinder(
            InProcessCallStampingBeanPostProcessor stamper) {
        return event -> {
            stamper.target().bindPort(event.getWebServer().getPort());
            log.info("CE in-process call secret armed: stamping {} HTTP client(s) addressed to loopback:{}",
                    stamper.stampedCount(), stamper.target().port());
        };
    }

}
