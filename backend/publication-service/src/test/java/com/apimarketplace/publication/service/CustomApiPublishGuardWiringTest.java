package com.apimarketplace.publication.service;

import com.apimarketplace.publication.config.CatalogInternalClient;
import com.apimarketplace.publication.config.ServiceClientConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The custom-API gate is FAIL-OPEN by design when its collaborator is missing: a null
 * {@code customApiPublishGuard} field means "no gate", and every unit test installs its own
 * stub by field assignment. That combination hides one regression completely - drop the
 * {@code @Component}, the {@code @Autowired}, or the {@code CatalogInternalClient} bean, and
 * every publish silently becomes unguarded with a fully green suite.
 *
 * <p>This test pins the wiring contract itself: a minimal scanned context that proves the
 * injection really happens (booting either publication service for real would need a
 * datasource), plus the annotations and the bean method that make it happen.
 */
@DisplayName("Custom-API gate wiring contract")
class CustomApiPublishGuardWiringTest {

    @Test
    @DisplayName("the guard is a Spring component, so the context has something to inject")
    void guardIsAComponent() {
        // AnnotatedElementUtils, not isAnnotationPresent: @Service / @Configuration are
        // @Component-annotated stereotypes, and a harmless rename must not fail this test.
        assertThat(AnnotatedElementUtils.hasAnnotation(CustomApiPublishGuard.class, Component.class))
                .as("without a stereotype the field stays null and no publish is ever gated")
                .isTrue();
    }

    @Test
    @DisplayName("a context that scans the two packages really does inject the guard into a service")
    void aScannedContextInjectsTheGuard() {
        new ApplicationContextRunner()
                .withUserConfiguration(GuardOnlyConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(CustomApiPublishGuard.class);
                    assertThat(context.getBean(GuardHolder.class).customApiPublishGuard)
                            .as("component scanning + @Autowired(required=false) must actually meet")
                            .isNotNull();
                });
    }

    /**
     * The smallest context that exercises the real mechanism: the guard found by scanning, its
     * client provided as a bean, and a holder declaring the same optional injection point the two
     * publication services declare. Booting either service for real would need a datasource.
     */
    @Configuration
    @ComponentScan(basePackageClasses = CustomApiPublishGuard.class,
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = CustomApiPublishGuard.class))
    static class GuardOnlyConfig {
        @Bean
        CatalogInternalClient catalogInternalClient() {
            return new CatalogInternalClient("http://catalog:8081");
        }

        @Bean
        GuardHolder guardHolder() {
            return new GuardHolder();
        }
    }

    /** Mirrors the injection point of both publication services. */
    static class GuardHolder {
        @Autowired(required = false)
        CustomApiPublishGuard customApiPublishGuard;
    }

    @Test
    @DisplayName("the guard takes its catalog client by constructor, and that client is a declared bean")
    void catalogClientIsADeclaredBean() throws Exception {
        assertThat(CustomApiPublishGuard.class.getDeclaredConstructors())
                .anySatisfy(constructor -> assertThat(constructor.getParameterTypes())
                        .containsExactly(CatalogInternalClient.class));

        Method beanMethod = ServiceClientConfig.class.getDeclaredMethod("catalogInternalClient");
        assertThat(beanMethod.isAnnotationPresent(Bean.class))
                .as("no bean means the guard cannot be constructed, which silently disables it")
                .isTrue();
        assertThat(beanMethod.getReturnType()).isEqualTo(CatalogInternalClient.class);
    }

    @Test
    @DisplayName("both publication services declare the guard as an injected (optional) collaborator")
    void bothServicesDeclareTheInjectionPoint() throws Exception {
        assertInjectedGuardField(WorkflowPublicationService.class);
        assertInjectedGuardField(AgentPublicationService.class);
    }

    private static void assertInjectedGuardField(Class<?> serviceType) throws Exception {
        Field field = serviceType.getDeclaredField("customApiPublishGuard");
        assertThat(field.getType()).isEqualTo(CustomApiPublishGuard.class);
        Autowired autowired = field.getAnnotation(Autowired.class);
        assertThat(autowired)
                .as("%s must ask Spring for the guard, else the gate never runs in production",
                        serviceType.getSimpleName())
                .isNotNull();
        // required=false is deliberate (the many unit constructions), and is exactly why the
        // annotation itself has to be pinned: its absence is invisible at run time.
        assertThat(autowired.required()).isFalse();
    }
}
