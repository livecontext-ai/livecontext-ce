package com.apimarketplace.common.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Registers {@link AppEditionProvider} via Spring Boot auto-configuration so
 * every service that depends on {@code common-lib} can inject it without
 * extending its {@code @ComponentScan} (services scan {@code common.security},
 * {@code common.event}, {@code common.storage}, etc. - NOT {@code common.web}).
 *
 * <p>No {@code @AutoConfigureBefore}/{@code @AutoConfigureAfter} is needed
 * today; add ordering hints if a future consumer auto-config injects
 * {@link AppEditionProvider} during its own bean creation.
 */
@AutoConfiguration
public class AppEditionAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AppEditionProvider appEditionProvider(Environment env) {
        return new AppEditionProvider(env);
    }
}
